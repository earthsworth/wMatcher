package org.earthsworth.wmatcher.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import javax.swing.SwingUtilities;
import org.earthsworth.wmatcher.core.model.ArtifactSnapshot;
import org.earthsworth.wmatcher.core.model.ClassClassification;
import org.earthsworth.wmatcher.core.model.ClassPair;
import org.earthsworth.wmatcher.core.model.ComparisonOverrides;
import org.earthsworth.wmatcher.core.model.DecompileRequest;
import org.earthsworth.wmatcher.core.model.DetachedPair;
import org.earthsworth.wmatcher.core.model.DiffNode;
import org.earthsworth.wmatcher.core.model.DiffResult;
import org.earthsworth.wmatcher.core.model.DiffUpdate;
import org.earthsworth.wmatcher.core.model.EntityId;
import org.earthsworth.wmatcher.core.model.EntityKind;
import org.earthsworth.wmatcher.core.model.MatchDecision;
import org.earthsworth.wmatcher.core.model.MatchResult;
import org.earthsworth.wmatcher.core.model.MappingFileFormat;
import org.earthsworth.wmatcher.core.model.MappingImportResult;
import org.earthsworth.wmatcher.core.model.MappingNamespaces;
import org.earthsworth.wmatcher.core.model.MatchingUpdate;
import org.earthsworth.wmatcher.core.model.MatchingPolicy;
import org.earthsworth.wmatcher.core.model.ResourceContent;
import org.earthsworth.wmatcher.core.model.ScanOptions;
import org.earthsworth.wmatcher.core.project.ArtifactReference;
import org.earthsworth.wmatcher.core.project.ProjectUiState;
import org.earthsworth.wmatcher.core.project.WMatcherProject;
import org.earthsworth.wmatcher.core.service.ArtifactInspector;
import org.earthsworth.wmatcher.core.service.ArtifactLoader;
import org.earthsworth.wmatcher.core.service.DecompilerService;
import org.earthsworth.wmatcher.core.service.DiffEngine;
import org.earthsworth.wmatcher.core.service.DiffSession;
import org.earthsworth.wmatcher.core.service.MappingService;
import org.earthsworth.wmatcher.core.service.MatchingEngine;
import org.earthsworth.wmatcher.core.service.MatchingSession;
import org.earthsworth.wmatcher.core.service.ProjectRepository;
import org.earthsworth.wmatcher.core.task.CancellationToken;
import org.earthsworth.wmatcher.engine.decompile.VineflowerDecompilerService;
import org.earthsworth.wmatcher.engine.diff.DefaultDiffEngine;
import org.earthsworth.wmatcher.engine.jar.JarArtifactLoader;
import org.earthsworth.wmatcher.engine.jar.ZipArtifactInspector;
import org.earthsworth.wmatcher.engine.mapping.MappingIoService;
import org.earthsworth.wmatcher.engine.match.DefaultMatchingEngine;
import org.earthsworth.wmatcher.engine.project.JacksonProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WorkspaceController implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkspaceController.class);
    private final ArtifactLoader loader;
    private final MatchingEngine matcher;
    private final DiffEngine differ;
    private final DecompilerService decompiler;
    private final ArtifactInspector inspector;
    private final ProjectRepository projects;
    private final MappingService mappings;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Deque<ComparisonOverrides> undo = new ArrayDeque<>();
    private final Deque<ComparisonOverrides> redo = new ArrayDeque<>();
    private final List<Consumer<Boolean>> documentStateListeners = new CopyOnWriteArrayList<>();
    private final AtomicLong documentRevision = new AtomicLong();
    private final AtomicLong analysisGeneration = new AtomicLong();
    private volatile AtomicBoolean cancellation = new AtomicBoolean();
    private volatile Future<?> activeJob;
    private volatile Workspace workspace;
    private volatile MatchingSession matchingSession;
    private volatile DiffSession diffSession;
    private volatile AnalysisUpdate latestAnalysisUpdate;
    private volatile long savedRevision;

    public WorkspaceController() {
        this(new JarArtifactLoader(), new DefaultMatchingEngine(), new DefaultDiffEngine(),
                new VineflowerDecompilerService(), new ZipArtifactInspector(),
                new JacksonProjectRepository(), new MappingIoService());
    }

    WorkspaceController(
            ArtifactLoader loader,
            MatchingEngine matcher,
            DiffEngine differ,
            DecompilerService decompiler,
            ArtifactInspector inspector,
            ProjectRepository projects,
            MappingService mappings) {
        this.loader = loader;
        this.matcher = matcher;
        this.differ = differ;
        this.decompiler = decompiler;
        this.inspector = inspector;
        this.projects = projects;
        this.mappings = mappings;
    }

    public Workspace workspace() {
        return workspace;
    }

    public AnalysisUpdate latestAnalysisUpdate() {
        return latestAnalysisUpdate;
    }

    public boolean hasUnsavedChanges() {
        return workspace != null && documentRevision.get() != savedRevision;
    }

    public void addDocumentStateListener(Consumer<Boolean> listener) {
        documentStateListeners.add(listener);
    }

    public void compare(
            CompareRequest request,
            Consumer<ProgressUpdate> progress,
            Consumer<Workspace> success,
            Consumer<Throwable> failure) {
        cancel();
        AtomicBoolean jobCancellation = new AtomicBoolean();
        cancellation = jobCancellation;
        CancellationToken token = jobCancellation::get;
        activeJob = executor.submit(() -> {
            try {
                ScanOptions defaults = ScanOptions.productionDefaults();
                ScanOptions options = new ScanOptions(request.targetRelease(), defaults.maximumFileSize(),
                        defaults.maximumEntries(), defaults.maximumExpandedBytes());
                CompletableFuture<ArtifactSnapshot> leftFuture = CompletableFuture.supplyAsync(
                        () -> loadArtifact(request.leftJar(), options, "old", progress, token), executor);
                CompletableFuture<ArtifactSnapshot> rightFuture = CompletableFuture.supplyAsync(
                        () -> loadArtifact(request.rightJar(), options, "new", progress, token), executor);
                ArtifactSnapshot left = leftFuture.join();
                ArtifactSnapshot right = rightFuture.join();
                token.throwIfCancelled();
                FilteredOverrides filtered = validOverrides(request.initialOverrides(), left, right);
                progress(progress, new ProgressUpdate("Matching entities", 0, 1));
                closeAnalysisSessions();
                MatchingSession newMatchingSession = matcher.openSession(
                        left, right, MatchingPolicy.conservativeV1(),
                        (stage, completed, total) -> progress(progress,
                                new ProgressUpdate(stage, completed, total)), token);
                DiffSession newDiffSession = differ.openSession(left, right);
                matchingSession = newMatchingSession;
                diffSession = newDiffSession;
                MatchResult matchResult = newMatchingSession.match(filtered.overrides(),
                        (stage, completed, total) -> progress(progress, new ProgressUpdate(stage, completed, total)), token);
                FilteredOverrides validated = validClassifications(filtered, matchResult);
                DiffResult diffResult = newDiffSession.diff(matchResult, validated.overrides(),
                        (stage, completed, total) -> progress(progress, new ProgressUpdate(stage, completed, total)), token);
                String warning = warning(request, left, right, validated.dropped());
                Workspace result = new Workspace(left, right, matchResult, diffResult, validated.overrides(),
                        request.leftLibraries(), request.rightLibraries(), request.projectPath(), warning,
                        request.uiState());
                workspace = result;
                latestAnalysisUpdate = new AnalysisUpdate(result, Set.of(), true, PhaseTimings.ZERO);
                undo.clear();
                redo.clear();
                resetDocumentState(request.projectPath() != null);
                onEdt(() -> {
                    notifyDocumentStateListeners();
                    success.accept(result);
                });
            } catch (Throwable throwable) {
                Throwable cause = unwrap(throwable);
                if (!(cause instanceof java.util.concurrent.CancellationException)) {
                    onEdt(() -> failure.accept(cause));
                }
            }
        });
    }

    public void openProject(
            Path projectPath,
            Consumer<ProgressUpdate> progress,
            Consumer<Workspace> success,
            Consumer<Throwable> failure) {
        executor.submit(() -> {
            try {
                WMatcherProject project = projects.load(projectPath);
                CompareRequest request = new CompareRequest(
                        Path.of(project.left().path()),
                        Path.of(project.right().path()),
                        project.targetRelease(),
                        project.leftLibraries().stream().map(Path::of).toList(),
                        project.rightLibraries().stream().map(Path::of).toList(),
                        new ComparisonOverrides(project.lockedMappings(), project.confirmedRemoved(),
                                project.confirmedAdded(), project.detachedPairs(), project.classifications()),
                        projectPath,
                        project.left().sha256(),
                        project.right().sha256(),
                        project.uiState());
                onEdt(() -> compare(request, progress, success, failure));
            } catch (Throwable throwable) {
                onEdt(() -> failure.accept(unwrap(throwable)));
            }
        });
    }

    public void saveProject(Path path, ProjectUiState uiState, Runnable success, Consumer<Throwable> failure) {
        Workspace current = requireWorkspace();
        long revisionBeingSaved = documentRevision.get();
        executor.submit(() -> {
            try {
                WMatcherProject project = new WMatcherProject(
                        WMatcherProject.CURRENT_FORMAT,
                        reference(current.left()),
                        reference(current.right()),
                        current.left().targetRelease(),
                        MatchingPolicy.conservativeV1().version(),
                        current.lockedMappings(),
                        current.confirmedRemoved(),
                        current.confirmedAdded(),
                        current.overrides().detachedPairs(),
                        current.overrides().classifications(),
                        current.leftLibraries().stream().map(Path::toString).toList(),
                        current.rightLibraries().stream().map(Path::toString).toList(),
                        uiState);
                projects.save(path, project);
                Workspace latest = workspace;
                workspace = (latest == null ? current : latest).withProjectPath(path);
                savedRevision = revisionBeingSaved;
                onEdt(() -> {
                    notifyDocumentStateListeners();
                    success.run();
                });
            } catch (Throwable throwable) {
                onEdt(() -> failure.accept(unwrap(throwable)));
            }
        });
    }

    public void lockMapping(MatchDecision decision, Consumer<Workspace> success, Consumer<Throwable> failure) {
        Workspace current = requireWorkspace();
        Map<EntityId, EntityId> updated = new LinkedHashMap<>(current.lockedMappings());
        Set<EntityId> removed = new HashSet<>(current.confirmedRemoved());
        Set<EntityId> added = new HashSet<>(current.confirmedAdded());
        Set<DetachedPair> detached = new HashSet<>(current.overrides().detachedPairs());
        Map<ClassPair, ClassClassification> classifications = new LinkedHashMap<>(
                current.overrides().classifications());
        updated.entrySet().removeIf(entry -> entry.getKey().equals(decision.left())
                || entry.getValue().equals(decision.right()));
        removed.remove(decision.left());
        added.remove(decision.right());
        detached.removeIf(pair -> pair.involves(decision.left()) || pair.involves(decision.right()));
        ClassPair selectedPair = decision.left().kind() == EntityKind.CLASS
                ? new ClassPair(decision.left(), decision.right()) : null;
        classifications.keySet().removeIf(pair -> (pair.involves(decision.left())
                || pair.involves(decision.right())) && !pair.equals(selectedPair));
        updated.put(decision.left(), decision.right());
        if (decision.left().kind() == EntityKind.CLASS) {
            Map<EntityId, EntityId> projectedClasses = new LinkedHashMap<>();
            current.matches().confirmed().stream()
                    .filter(match -> match.left().kind() == EntityKind.CLASS)
                    .forEach(match -> projectedClasses.put(match.left(), match.right()));
            projectedClasses.entrySet().removeIf(entry -> entry.getKey().equals(decision.left())
                    || entry.getValue().equals(decision.right()));
            projectedClasses.put(decision.left(), decision.right());
            updated.entrySet().removeIf(entry -> isMember(entry.getKey())
                    && !ownersMatch(entry.getKey(), entry.getValue(), projectedClasses));
        }
        pushAndRecompute(current.overrides(), new ComparisonOverrides(
                updated, removed, added, detached, classifications),
                success, failure);
    }

    public void confirmMapping(MatchDecision decision, Consumer<Workspace> success, Consumer<Throwable> failure) {
        lockMapping(decision, success, failure);
    }

    public void unlockMapping(EntityId left, Consumer<Workspace> success, Consumer<Throwable> failure) {
        Workspace current = requireWorkspace();
        if (!current.lockedMappings().containsKey(left)) {
            return;
        }
        Map<EntityId, EntityId> updated = new LinkedHashMap<>(current.lockedMappings());
        EntityId previousRight = updated.remove(left);
        Map<ClassPair, ClassClassification> classifications = new LinkedHashMap<>(
                current.overrides().classifications());
        classifications.keySet().removeIf(pair -> pair.involves(left)
                || previousRight != null && pair.involves(previousRight));
        if (left.kind() == EntityKind.CLASS && previousRight != null) {
            updated.entrySet().removeIf(entry -> isMember(entry.getKey())
                    && (entry.getKey().owner().equals(left.name())
                    || entry.getValue().owner().equals(previousRight.name())));
        }
        pushAndRecompute(current.overrides(), new ComparisonOverrides(
                updated, current.confirmedRemoved(), current.confirmedAdded(), current.overrides().detachedPairs(),
                classifications),
                success, failure);
    }

    public void restoreAutomatic(EntityId left, Consumer<Workspace> success, Consumer<Throwable> failure) {
        unlockMapping(left, success, failure);
    }

    public void confirmSingleSided(
            DiffNode node, Consumer<Workspace> success, Consumer<Throwable> failure) {
        Workspace current = requireWorkspace();
        if ((node.left() == null) == (node.right() == null)) {
            return;
        }
        Set<EntityId> removed = new HashSet<>(current.confirmedRemoved());
        Set<EntityId> added = new HashSet<>(current.confirmedAdded());
        Set<DetachedPair> detached = new HashSet<>(current.overrides().detachedPairs());
        Map<ClassPair, ClassClassification> classifications = new LinkedHashMap<>(
                current.overrides().classifications());
        if (node.left() != null) {
            removed.add(node.left());
            detached.removeIf(pair -> pair.involves(node.left()));
            classifications.keySet().removeIf(pair -> pair.involves(node.left()));
        } else {
            added.add(node.right());
            detached.removeIf(pair -> pair.involves(node.right()));
            classifications.keySet().removeIf(pair -> pair.involves(node.right()));
        }
        pushAndRecompute(current.overrides(), new ComparisonOverrides(
                current.lockedMappings(), removed, added, detached, classifications), success, failure);
    }

    public void confirmSingleSided(
            Collection<DiffNode> nodes, Consumer<Workspace> success, Consumer<Throwable> failure) {
        Workspace current = requireWorkspace();
        Set<EntityId> removed = new HashSet<>(current.confirmedRemoved());
        Set<EntityId> added = new HashSet<>(current.confirmedAdded());
        Set<DetachedPair> detached = new HashSet<>(current.overrides().detachedPairs());
        Map<ClassPair, ClassClassification> classifications = new LinkedHashMap<>(
                current.overrides().classifications());
        for (DiffNode node : nodes) {
            if ((node.left() == null) == (node.right() == null) || isMember(node.left() != null ? node.left() : node.right())) {
                continue;
            }
            EntityId id = node.left() != null ? node.left() : node.right();
            if (node.left() != null) removed.add(id); else added.add(id);
            detached.removeIf(pair -> pair.involves(id));
            classifications.keySet().removeIf(pair -> pair.involves(id));
        }
        pushAndRecompute(current.overrides(), new ComparisonOverrides(
                current.lockedMappings(), removed, added, detached, classifications), success, failure);
    }

    public void clearSingleSidedResolution(
            DiffNode node, Consumer<Workspace> success, Consumer<Throwable> failure) {
        Workspace current = requireWorkspace();
        Set<EntityId> removed = new HashSet<>(current.confirmedRemoved());
        Set<EntityId> added = new HashSet<>(current.confirmedAdded());
        boolean changed = node.left() != null ? removed.remove(node.left())
                : node.right() != null && added.remove(node.right());
        if (!changed) {
            return;
        }
        pushAndRecompute(current.overrides(), new ComparisonOverrides(
                current.lockedMappings(), removed, added, current.overrides().detachedPairs(),
                current.overrides().classifications()), success, failure);
    }

    public void detachMapping(DiffNode node, Consumer<Workspace> success, Consumer<Throwable> failure) {
        if (node.left() == null || node.right() == null || isMember(node.left())) {
            return;
        }
        Workspace current = requireWorkspace();
        Map<EntityId, EntityId> mappings = new LinkedHashMap<>(current.lockedMappings());
        mappings.entrySet().removeIf(entry -> entry.getKey().equals(node.left())
                || entry.getValue().equals(node.right())
                || node.left().kind() == EntityKind.CLASS && isMember(entry.getKey())
                && (entry.getKey().owner().equals(node.left().name())
                || entry.getValue().owner().equals(node.right().name())));
        Set<DetachedPair> detached = new HashSet<>(current.overrides().detachedPairs());
        Map<ClassPair, ClassClassification> classifications = new LinkedHashMap<>(
                current.overrides().classifications());
        detached.removeIf(pair -> pair.involves(node.left()) || pair.involves(node.right()));
        detached.add(new DetachedPair(node.left(), node.right()));
        classifications.keySet().removeIf(pair -> pair.involves(node.left()) || pair.involves(node.right()));
        pushAndRecompute(current.overrides(), new ComparisonOverrides(
                mappings, current.confirmedRemoved(), current.confirmedAdded(), detached, classifications),
                success, failure);
    }

    public void restoreDetached(EntityId entity, Consumer<Workspace> success, Consumer<Throwable> failure) {
        Workspace current = requireWorkspace();
        Set<DetachedPair> detached = new HashSet<>(current.overrides().detachedPairs());
        if (!detached.removeIf(pair -> pair.involves(entity))) {
            return;
        }
        pushAndRecompute(current.overrides(), new ComparisonOverrides(
                current.lockedMappings(), current.confirmedRemoved(), current.confirmedAdded(), detached,
                current.overrides().classifications()),
                success, failure);
    }

    public void classifyClass(
            DiffNode node,
            ClassClassification classification,
            Consumer<Workspace> success,
            Consumer<Throwable> failure) {
        if (node.kind() != EntityKind.CLASS || node.left() == null || node.right() == null) {
            return;
        }
        Workspace current = requireWorkspace();
        ClassPair pair = new ClassPair(node.left(), node.right());
        Map<ClassPair, ClassClassification> classifications = new LinkedHashMap<>(
                current.overrides().classifications());
        if (classification == ClassClassification.AUTO) {
            classifications.remove(pair);
        } else {
            classifications.put(pair, classification);
        }
        ComparisonOverrides updated = new ComparisonOverrides(
                current.lockedMappings(), current.confirmedRemoved(), current.confirmedAdded(),
                current.detachedPairs(), classifications);
        pushAndRecompute(current.overrides(), updated, success, failure);
    }

    public void undoMappings(Consumer<Workspace> success, Consumer<Throwable> failure) {
        if (undo.isEmpty()) {
            return;
        }
        Workspace current = requireWorkspace();
        ComparisonOverrides target = undo.pop();
        redo.push(current.overrides());
        recompute(current.overrides(), target, success, failure);
    }

    public void redoMappings(Consumer<Workspace> success, Consumer<Throwable> failure) {
        if (redo.isEmpty()) {
            return;
        }
        Workspace current = requireWorkspace();
        ComparisonOverrides target = redo.pop();
        undo.push(current.overrides());
        recompute(current.overrides(), target, success, failure);
    }

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    public void importTiny(Path path, Consumer<Workspace> success, Consumer<Throwable> failure) {
        importMappings(() -> {
            Workspace current = requireWorkspace();
            return mappings.importTiny(path, current.left(), current.right());
        }, success, failure);
    }

    public void importProguard(
            Path left, Path right, Consumer<Workspace> success, Consumer<Throwable> failure) {
        importMappings(() -> {
            Workspace current = requireWorkspace();
            return mappings.importProguard(left, right, current.left(), current.right());
        }, success, failure);
    }

    public void exportTiny(Path path, Runnable success, Consumer<Throwable> failure) {
        Workspace current = requireWorkspace();
        executor.submit(() -> {
            try {
                mappings.exportTiny(path, current.matches());
                onEdt(success);
            } catch (Throwable throwable) {
                onEdt(() -> failure.accept(unwrap(throwable)));
            }
        });
    }

    public void importMappingFile(
            MappingFileFormat format,
            Path path,
            MappingNamespaces namespaces,
            BiConsumer<Workspace, MappingImportResult> success,
            Consumer<Throwable> failure) {
        Workspace current = requireWorkspace();
        executor.submit(() -> {
            try {
                MappingImportResult imported = mappings.importMappings(
                        format, path, namespaces, current.left(), current.right());
                Map<EntityId, EntityId> merged = mergeWithoutOverwriting(current.overrides(), imported.mappings());
                ComparisonOverrides updated = new ComparisonOverrides(
                        merged, current.confirmedRemoved(), current.confirmedAdded(),
                        current.overrides().detachedPairs(), current.overrides().classifications());
                pushAndRecompute(current.overrides(), updated,
                        workspace -> success.accept(workspace, imported), failure);
            } catch (Throwable throwable) {
                onEdt(() -> failure.accept(unwrap(throwable)));
            }
        });
    }

    public void mappingNamespaces(
            Path path,
            MappingFileFormat format,
            Consumer<List<String>> success,
            Consumer<Throwable> failure) {
        executor.submit(() -> {
            try {
                List<String> namespaces = mappings.namespaces(path, format);
                onEdt(() -> success.accept(namespaces));
            } catch (Throwable throwable) {
                onEdt(() -> failure.accept(unwrap(throwable)));
            }
        });
    }

    public java.util.concurrent.Future<?> search(
            SearchRequest request,
            Consumer<SearchHit> hit,
            Consumer<SearchProgress> progress,
            Consumer<Throwable> failure) {
        Workspace current = requireWorkspace();
        return executor.submit(() -> {
            try {
                String query = request.query().trim();
                if (query.isBlank()) return;
                java.util.function.Predicate<String> matcher = request.matcher().predicate(query);
                List<ArtifactSnapshot> artifacts = switch (request.side()) {
                    case OLD -> List.of(current.left());
                    case NEW -> List.of(current.right());
                    case ALL -> List.of(current.left(), current.right());
                };
                int total = artifacts.stream().mapToInt(artifact -> artifact.classes().size()
                        + artifact.resources().size()).sum();
                int completed = 0;
                for (ArtifactSnapshot artifact : artifacts) {
                    boolean leftSide = artifact == current.left();
                    if (request.types().contains(SearchType.CLASS)) {
                        for (var model : artifact.classes().values().stream()
                                .sorted(java.util.Comparator.comparing(org.earthsworth.wmatcher.core.model.ClassModel::internalName))
                                .toList()) {
                            if (matcher.test(model.internalName()) || matcher.test(model.internalName().replace('/', '.'))) {
                                hit.accept(new SearchHit(leftSide, SearchType.CLASS, model.id(), model.internalName(), 0, ""));
                            }
                            completed++;
                            progress.accept(new SearchProgress(completed, total));
                        }
                    }
                    if (request.types().contains(SearchType.MEMBER)) {
                        for (var model : artifact.classes().values().stream()
                                .sorted(java.util.Comparator.comparing(org.earthsworth.wmatcher.core.model.ClassModel::internalName))
                                .toList()) {
                            for (var field : model.fields()) {
                                if (matcher.test(field.name()) || matcher.test(field.descriptor())) {
                                    hit.accept(new SearchHit(leftSide, SearchType.MEMBER,
                                            EntityId.fieldId(model.internalName(), field.name(), field.descriptor()),
                                            model.internalName() + "." + field.name(), 0,
                                            field.descriptor()));
                                }
                            }
                            for (var method : model.methods()) {
                                if (matcher.test(method.name()) || matcher.test(method.descriptor())) {
                                    hit.accept(new SearchHit(leftSide, SearchType.MEMBER,
                                            EntityId.methodId(model.internalName(), method.name(), method.descriptor()),
                                            model.internalName() + "." + method.name(), 0,
                                            method.descriptor()));
                                }
                            }
                            completed++;
                            progress.accept(new SearchProgress(completed, total));
                        }
                    }
                    if (request.types().contains(SearchType.FILE)) {
                        for (var resource : artifact.resources().values().stream()
                                .sorted(java.util.Comparator.comparing(org.earthsworth.wmatcher.core.model.ResourceModel::path))
                                .toList()) {
                            if (matcher.test(resource.path())) {
                                hit.accept(new SearchHit(leftSide, SearchType.FILE, resource.id(), resource.path(), 0, ""));
                            }
                            completed++;
                            progress.accept(new SearchProgress(completed, total));
                        }
                    }
                    if (request.types().contains(SearchType.TEXT)) {
                        completed = searchText(current, artifact, leftSide, matcher, request.canonicalNames(), hit, progress,
                                completed, total, request.cancellation());
                    }
                }
            } catch (Throwable throwable) {
                if (!request.cancellation().isCancelled()) failure.accept(unwrap(throwable));
            }
        });
    }

    private int searchText(
            Workspace current,
            ArtifactSnapshot artifact,
            boolean leftSide,
            java.util.function.Predicate<String> matcher,
            CanonicalNamesDirection canonicalNames,
            Consumer<SearchHit> hit,
            Consumer<SearchProgress> progress,
            int completed,
            int total,
            CancellationToken cancellation) throws IOException {
        for (var resource : artifact.resources().values().stream()
                .filter(resource -> resource.likelyText() && resource.size() <= 5L * 1024 * 1024)
                .sorted(java.util.Comparator.comparing(org.earthsworth.wmatcher.core.model.ResourceModel::path)).toList()) {
            cancellation.throwIfCancelled();
            ResourceContent content = inspector.resourceContent(artifact, resource.path(), 5 * 1024 * 1024);
            emitTextHits(leftSide, resource.id(), resource.path(), new String(content.bytes(), StandardCharsets.UTF_8),
                    matcher, SearchType.TEXT, hit);
            progress.accept(new SearchProgress(++completed, total));
        }
        Set<String> topLevels = new java.util.TreeSet<>();
        artifact.classes().keySet().forEach(name -> topLevels.add(name.contains("$")
                ? name.substring(0, name.indexOf('$')) : name));
        for (String className : topLevels) {
            cancellation.throwIfCancelled();
            try {
                EntityId classId = EntityId.classId(className);
                boolean remap = canonicalNames.remaps(leftSide);
                DecompileRequest request = new DecompileRequest(artifact, className,
                        canonicalMappings(current, leftSide, remap), remap,
                        leftSide ? current.leftLibraries() : current.rightLibraries());
                String sourceText = decompiler.decompile(request, cancellation).source();
                emitTextHits(leftSide, classId, className, sourceText, matcher, SearchType.TEXT, hit);
            } catch (IOException ignored) {
                // A single damaged class should not hide results from the rest of the artifact.
            }
            progress.accept(new SearchProgress(++completed, total));
        }
        return completed;
    }

    private static void emitTextHits(
            boolean leftSide,
            EntityId id,
            String path,
            String value,
            java.util.function.Predicate<String> matcher,
            SearchType type,
            Consumer<SearchHit> hit) {
        String[] lines = value.split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            if (matcher.test(lines[index])) {
                hit.accept(new SearchHit(leftSide, type, id, path, index + 1, lines[index].strip()));
            }
        }
    }

    public void exportMappings(
            Path path,
            MappingFileFormat format,
            Runnable success,
            Consumer<Throwable> failure) {
        Workspace current = requireWorkspace();
        executor.submit(() -> {
            try {
                mappings.exportMappings(path, format, current.matches());
                onEdt(success);
            } catch (Throwable throwable) {
                onEdt(() -> failure.accept(unwrap(throwable)));
            }
        });
    }

    public void loadBytecode(
            DiffNode node, boolean leftSide, boolean semantic, Consumer<String> success, Consumer<Throwable> failure) {
        Workspace current = requireWorkspace();
        EntityId id = leftSide ? node.left() : node.right();
        if (id == null || id.kind() == EntityKind.RESOURCE) {
            success.accept("");
            return;
        }
        ArtifactSnapshot artifact = leftSide ? current.left() : current.right();
        String className = id.kind() == EntityKind.CLASS ? id.name() : id.owner();
        async(() -> inspector.bytecodeText(artifact, className, semantic), success, failure);
    }

    public void loadResource(
            DiffNode node, boolean leftSide, Consumer<String> success, Consumer<Throwable> failure) {
        Workspace current = requireWorkspace();
        EntityId id = leftSide ? node.left() : node.right();
        if (id == null || id.kind() != EntityKind.RESOURCE) {
            success.accept("");
            return;
        }
        ArtifactSnapshot artifact = leftSide ? current.left() : current.right();
        async(() -> renderResource(inspector.resourceContent(artifact, id.name(), 5 * 1024 * 1024)), success, failure);
    }

    public void loadSource(
            DiffNode node,
            boolean leftSide,
            CanonicalNamesDirection canonicalNames,
            Consumer<String> success,
            Consumer<Throwable> failure) {
        Workspace current = requireWorkspace();
        EntityId id = leftSide ? node.left() : node.right();
        if (id == null || id.kind() == EntityKind.RESOURCE) {
            success.accept("");
            return;
        }
        ArtifactSnapshot artifact = leftSide ? current.left() : current.right();
        List<Path> libraries = new ArrayList<>(leftSide ? current.leftLibraries() : current.rightLibraries());
        libraries.add(leftSide ? current.right().path() : current.left().path());
        boolean remap = canonicalNames.remaps(leftSide);
        DecompileRequest request = new DecompileRequest(
                artifact,
                id.kind() == EntityKind.CLASS ? id.name() : id.owner(),
                canonicalMappings(current, leftSide, remap),
                remap,
                libraries);
        AtomicBoolean requestCancellation = cancellation;
        async(() -> decompiler.decompile(request, requestCancellation::get).source(), success, failure);
    }

    public void loadSource(
            DiffNode node, boolean leftSide, boolean canonical, Consumer<String> success, Consumer<Throwable> failure) {
        loadSource(node, leftSide,
                canonical ? CanonicalNamesDirection.RIGHT_TO_LEFT : CanonicalNamesDirection.DISABLED,
                success, failure);
    }

    private static Map<EntityId, EntityId> canonicalMappings(
            Workspace workspace, boolean leftSide, boolean remap) {
        Map<EntityId, EntityId> mappings = workspace.matches().confirmedMappings();
        if (!remap || !leftSide) {
            return mappings;
        }
        Map<EntityId, EntityId> reversed = new LinkedHashMap<>();
        mappings.forEach((left, right) -> reversed.put(right, left));
        return reversed;
    }

    public void cancel() {
        cancellation.set(true);
        Future<?> job = activeJob;
        if (job != null) {
            job.cancel(true);
        }
    }

    @Override
    public void close() {
        cancel();
        closeAnalysisSessions();
        executor.shutdownNow();
    }

    private void closeAnalysisSessions() {
        MatchingSession oldMatching = matchingSession;
        DiffSession oldDiff = diffSession;
        matchingSession = null;
        diffSession = null;
        if (oldMatching != null) {
            try { oldMatching.close(); } catch (RuntimeException exception) {
                LOGGER.debug("Could not close matching session", exception);
            }
        }
        if (oldDiff != null) {
            try { oldDiff.close(); } catch (RuntimeException exception) {
                LOGGER.debug("Could not close diff session", exception);
            }
        }
    }

    private void pushAndRecompute(
            ComparisonOverrides previous,
            ComparisonOverrides updated,
            Consumer<Workspace> success,
            Consumer<Throwable> failure) {
        if (previous.equals(updated)) {
            onEdt(() -> success.accept(requireWorkspace()));
            return;
        }
        undo.push(previous);
        redo.clear();
        recompute(previous, updated, success, failure);
    }

    private void recompute(
            ComparisonOverrides previousOverrides,
            ComparisonOverrides overrides,
            Consumer<Workspace> success,
            Consumer<Throwable> failure) {
        Workspace current = requireWorkspace();
        if (matchingStateEquals(previousOverrides, overrides)) {
            Workspace updated = current.withAnalysis(current.matches(), current.differences(), overrides);
            workspace = updated;
            Set<EntityId> affected = classificationEntities(previousOverrides, overrides);
            latestAnalysisUpdate = new AnalysisUpdate(updated, affected, false, PhaseTimings.ZERO);
            documentRevision.incrementAndGet();
            onEdt(() -> {
                notifyDocumentStateListeners();
                success.accept(updated);
            });
            return;
        }
        long generation = analysisGeneration.incrementAndGet();
        executor.submit(() -> {
            try {
                long started = System.nanoTime();
                Set<EntityId> affected = affectedEntities(previousOverrides, overrides);
                long overridesResolved = System.nanoTime();
                int totalEntities = current.left().entryCount() + current.right().entryCount();
                boolean incremental = affected.size() <= 1_000
                        && affected.size() <= Math.max(2, totalEntities / 10);
                MatchingSession currentMatchingSession = matchingSession;
                DiffSession currentDiffSession = diffSession;
                MatchingUpdate matchingUpdate = incremental && currentMatchingSession != null
                        ? currentMatchingSession.rematchUpdate(current.matches(), previousOverrides, overrides,
                                affected, (stage, completed, total) -> { }, CancellationToken.NONE)
                        : new MatchingUpdate(incremental
                                ? matcher.rematch(current.left(), current.right(), current.matches(),
                                        previousOverrides, overrides, affected, MatchingPolicy.conservativeV1(),
                                        (stage, completed, total) -> { }, CancellationToken.NONE)
                                : matcher.match(current.left(), current.right(), overrides,
                                        MatchingPolicy.conservativeV1(), (stage, completed, total) -> { },
                                        CancellationToken.NONE), affected, !incremental);
                MatchResult matchResult = matchingUpdate.result();
                ComparisonOverrides effectiveOverrides = retainMatchingClassifications(overrides, matchResult);
                long matched = System.nanoTime();
                Set<EntityId> diffAffected = new HashSet<>(matchingUpdate.affectedEntities());
                addChangedMatchEntities(current.matches(), matchResult, diffAffected);
                DiffUpdate diffUpdate = incremental && currentDiffSession != null
                        ? currentDiffSession.rediffUpdate(matchResult, current.differences(), effectiveOverrides,
                                diffAffected, (stage, completed, total) -> { }, CancellationToken.NONE)
                        : new DiffUpdate(incremental
                                ? differ.rediff(current.left(), current.right(), matchResult,
                                        current.differences(), effectiveOverrides, diffAffected,
                                        (stage, completed, total) -> { }, CancellationToken.NONE)
                                : differ.diff(current.left(), current.right(), matchResult, effectiveOverrides,
                                        (stage, completed, total) -> { }, CancellationToken.NONE),
                                diffAffected, !incremental);
                DiffResult diffResult = diffUpdate.result();
                long diffed = System.nanoTime();
                Workspace updated = current.withAnalysis(matchResult, diffResult, effectiveOverrides);
                if (analysisGeneration.get() != generation) {
                    return;
                }
                workspace = updated;
                PhaseTimings timings = new PhaseTimings(
                        elapsedMillis(started, overridesResolved), elapsedMillis(overridesResolved, matched),
                        elapsedMillis(matched, diffed));
                latestAnalysisUpdate = new AnalysisUpdate(updated, diffUpdate.affectedEntities(),
                        matchingUpdate.fullRefresh() || diffUpdate.fullRefresh(), timings);
                documentRevision.incrementAndGet();
                onEdt(() -> {
                    notifyDocumentStateListeners();
                    success.accept(updated);
                });
                LOGGER.info("Incremental analysis: overrides={}ms, matching={}ms, diff={}ms, total={}ms, affected={}",
                        elapsedMillis(started, overridesResolved), elapsedMillis(overridesResolved, matched),
                        elapsedMillis(matched, diffed), elapsedMillis(started, diffed), affected.size());
            } catch (Throwable throwable) {
                onEdt(() -> failure.accept(unwrap(throwable)));
            }
        });
    }

    private static long elapsedMillis(long start, long end) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(end - start);
    }

    private void importMappings(
            IoSupplier<Map<EntityId, EntityId>> importer,
            Consumer<Workspace> success,
            Consumer<Throwable> failure) {
        Workspace current = requireWorkspace();
        executor.submit(() -> {
            try {
                Map<EntityId, EntityId> imported = importer.get();
                Map<EntityId, EntityId> merged = mergeWithoutOverwriting(current.overrides(), imported);
                ComparisonOverrides updated = new ComparisonOverrides(
                        merged, current.confirmedRemoved(), current.confirmedAdded(),
                        current.overrides().detachedPairs(), current.overrides().classifications());
                onEdt(() -> pushAndRecompute(current.overrides(), updated, success, failure));
            } catch (Throwable throwable) {
                onEdt(() -> failure.accept(unwrap(throwable)));
            }
        });
    }

    private static Map<EntityId, EntityId> mergeWithoutOverwriting(
            ComparisonOverrides existing, Map<EntityId, EntityId> imported) {
        Map<EntityId, EntityId> merged = new LinkedHashMap<>(existing.lockedMappings());
        Set<EntityId> usedRight = new HashSet<>(existing.lockedMappings().values());
        imported.forEach((left, right) -> {
            if (!existing.confirmedRemoved().contains(left)
                    && !existing.confirmedAdded().contains(right)
                    && existing.detachedPairs().stream().noneMatch(pair -> pair.involves(left) || pair.involves(right))
                    && !merged.containsKey(left) && usedRight.add(right)) {
                merged.put(left, right);
            }
        });
        return merged;
    }

    private static Set<EntityId> affectedEntities(
            ComparisonOverrides previous, ComparisonOverrides updated) {
        Set<EntityId> affected = new HashSet<>();
        previous.lockedMappings().forEach((left, right) -> {
            if (!right.equals(updated.lockedMappings().get(left))) {
                affected.add(left);
                affected.add(right);
            }
        });
        updated.lockedMappings().forEach((left, right) -> {
            if (!right.equals(previous.lockedMappings().get(left))) {
                affected.add(left);
                affected.add(right);
            }
        });
        symmetricDifference(previous.confirmedRemoved(), updated.confirmedRemoved(), affected);
        symmetricDifference(previous.confirmedAdded(), updated.confirmedAdded(), affected);
        Set<DetachedPair> detached = new HashSet<>(previous.detachedPairs());
        detached.addAll(updated.detachedPairs());
        for (DetachedPair pair : detached) {
            if (previous.detachedPairs().contains(pair) != updated.detachedPairs().contains(pair)) {
                affected.add(pair.left());
                affected.add(pair.right());
            }
        }
        return Set.copyOf(affected);
    }

    private static boolean matchingStateEquals(ComparisonOverrides first, ComparisonOverrides second) {
        return first.lockedMappings().equals(second.lockedMappings())
                && first.confirmedRemoved().equals(second.confirmedRemoved())
                && first.confirmedAdded().equals(second.confirmedAdded())
                && first.detachedPairs().equals(second.detachedPairs());
    }

    private static Set<EntityId> classificationEntities(
            ComparisonOverrides previous, ComparisonOverrides updated) {
        Set<EntityId> affected = new HashSet<>();
        Set<ClassPair> pairs = new HashSet<>(previous.classifications().keySet());
        pairs.addAll(updated.classifications().keySet());
        for (ClassPair pair : pairs) {
            if (previous.classifications().get(pair) != updated.classifications().get(pair)) {
                affected.add(pair.left());
                affected.add(pair.right());
            }
        }
        return Set.copyOf(affected);
    }

    private static ComparisonOverrides retainMatchingClassifications(
            ComparisonOverrides overrides, MatchResult matches) {
        Set<ClassPair> pairs = matches.confirmed().stream()
                .filter(match -> match.left().kind() == EntityKind.CLASS)
                .map(match -> new ClassPair(match.left(), match.right()))
                .collect(java.util.stream.Collectors.toSet());
        Map<ClassPair, ClassClassification> classifications = overrides.classifications().entrySet().stream()
                .filter(entry -> pairs.contains(entry.getKey()))
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey, Map.Entry::getValue, (first, ignored) -> first, LinkedHashMap::new));
        if (classifications.size() == overrides.classifications().size()) return overrides;
        return new ComparisonOverrides(overrides.lockedMappings(), overrides.confirmedRemoved(),
                overrides.confirmedAdded(), overrides.detachedPairs(), classifications);
    }

    private static void addChangedMatchEntities(
            MatchResult previous, MatchResult updated, Set<EntityId> affected) {
        Map<EntityId, EntityId> before = previous.confirmed().stream()
                .filter(match -> match.left().kind() == EntityKind.CLASS)
                .collect(java.util.stream.Collectors.toMap(MatchDecision::left, MatchDecision::right));
        Map<EntityId, EntityId> after = updated.confirmed().stream()
                .filter(match -> match.left().kind() == EntityKind.CLASS)
                .collect(java.util.stream.Collectors.toMap(MatchDecision::left, MatchDecision::right));
        before.forEach((left, right) -> {
            if (!right.equals(after.get(left))) {
                affected.add(left);
                affected.add(right);
            }
        });
        after.forEach((left, right) -> {
            if (!right.equals(before.get(left))) {
                affected.add(left);
                affected.add(right);
            }
        });
    }

    private static void symmetricDifference(Set<EntityId> first, Set<EntityId> second, Set<EntityId> target) {
        first.stream().filter(id -> !second.contains(id)).forEach(target::add);
        second.stream().filter(id -> !first.contains(id)).forEach(target::add);
    }

    private static boolean isMember(EntityId id) {
        return id.kind() == EntityKind.FIELD || id.kind() == EntityKind.METHOD;
    }

    private static boolean ownersMatch(
            EntityId left,
            EntityId right,
            Map<EntityId, EntityId> classMappings) {
        return EntityId.classId(right.owner()).equals(classMappings.get(EntityId.classId(left.owner())));
    }

    private <T> void async(IoSupplier<T> action, Consumer<T> success, Consumer<Throwable> failure) {
        executor.submit(() -> {
            try {
                T result = action.get();
                onEdt(() -> success.accept(result));
            } catch (Throwable throwable) {
                onEdt(() -> failure.accept(unwrap(throwable)));
            }
        });
    }

    private ArtifactSnapshot loadArtifact(
            Path path,
            ScanOptions options,
            String side,
            Consumer<ProgressUpdate> progress,
            CancellationToken token) {
        try {
            return loader.load(path, options,
                    (stage, completed, total) -> progress(progress,
                            new ProgressUpdate(side + ": " + stage, completed, total)), token);
        } catch (IOException exception) {
            throw new CompletionException(exception);
        }
    }

    private static FilteredOverrides validOverrides(
            ComparisonOverrides requested, ArtifactSnapshot left, ArtifactSnapshot right) {
        Set<EntityId> removed = requested.confirmedRemoved().stream()
                .filter(id -> entityExists(left, id))
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        Set<EntityId> added = requested.confirmedAdded().stream()
                .filter(id -> entityExists(right, id))
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        Set<DetachedPair> detached = requested.detachedPairs().stream()
                .filter(pair -> entityExists(left, pair.left()) && entityExists(right, pair.right()))
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        Map<ClassPair, ClassClassification> classifications = requested.classifications().entrySet().stream()
                .filter(entry -> entityExists(left, entry.getKey().left())
                        && entityExists(right, entry.getKey().right()))
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey, Map.Entry::getValue, (first, ignored) -> first, LinkedHashMap::new));
        Map<EntityId, EntityId> result = new LinkedHashMap<>();
        Set<EntityId> rightUsed = new HashSet<>();
        int dropped = requested.confirmedRemoved().size() - removed.size()
                + requested.confirmedAdded().size() - added.size()
                + requested.detachedPairs().size() - detached.size()
                + requested.classifications().size() - classifications.size();
        for (Map.Entry<EntityId, EntityId> entry : requested.lockedMappings().entrySet()) {
            if (entityExists(left, entry.getKey()) && entityExists(right, entry.getValue())
                    && !removed.contains(entry.getKey()) && !added.contains(entry.getValue())
                    && rightUsed.add(entry.getValue())) {
                result.put(entry.getKey(), entry.getValue());
            } else {
                dropped++;
            }
        }
        int detachedBeforeConflicts = detached.size();
        detached.removeIf(pair -> result.containsKey(pair.left()) || result.containsValue(pair.right())
                || removed.contains(pair.left()) || added.contains(pair.right()));
        dropped += detachedBeforeConflicts - detached.size();
        int classificationsBeforeConflicts = classifications.size();
        classifications.keySet().removeIf(pair -> detached.stream().anyMatch(detachedPair ->
                detachedPair.left().equals(pair.left()) || detachedPair.right().equals(pair.right())));
        dropped += classificationsBeforeConflicts - classifications.size();
        return new FilteredOverrides(new ComparisonOverrides(
                result, removed, added, detached, classifications), dropped);
    }

    private static boolean entityExists(ArtifactSnapshot artifact, EntityId id) {
        if (id.kind() == EntityKind.RESOURCE) {
            return artifact.resources().containsKey(id.name());
        }
        var owner = artifact.classes().get(id.kind() == EntityKind.CLASS ? id.name() : id.owner());
        if (owner == null) {
            return false;
        }
        return switch (id.kind()) {
            case CLASS -> true;
            case FIELD -> owner.fields().stream().anyMatch(field -> field.name().equals(id.name())
                    && field.descriptor().equals(id.descriptor()));
            case METHOD -> owner.methods().stream().anyMatch(method -> method.name().equals(id.name())
                    && method.descriptor().equals(id.descriptor()));
            case RESOURCE -> false;
        };
    }

    private static FilteredOverrides validClassifications(FilteredOverrides filtered, MatchResult matches) {
        Set<ClassPair> pairedClasses = matches.confirmed().stream()
                .filter(match -> match.left().kind() == EntityKind.CLASS)
                .map(match -> new ClassPair(match.left(), match.right()))
                .collect(java.util.stream.Collectors.toSet());
        Map<ClassPair, ClassClassification> valid = filtered.overrides().classifications().entrySet().stream()
                .filter(entry -> pairedClasses.contains(entry.getKey()))
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey, Map.Entry::getValue, (first, ignored) -> first, LinkedHashMap::new));
        int dropped = filtered.dropped()
                + filtered.overrides().classifications().size() - valid.size();
        ComparisonOverrides overrides = filtered.overrides();
        return new FilteredOverrides(new ComparisonOverrides(
                overrides.lockedMappings(), overrides.confirmedRemoved(), overrides.confirmedAdded(),
                overrides.detachedPairs(), valid), dropped);
    }

    private static String warning(CompareRequest request, ArtifactSnapshot left, ArtifactSnapshot right, int dropped) {
        List<String> warnings = new ArrayList<>();
        if (!request.expectedLeftHash().isBlank() && !request.expectedLeftHash().equals(left.sha256())) {
            warnings.add("Old Jar hash changed");
        }
        if (!request.expectedRightHash().isBlank() && !request.expectedRightHash().equals(right.sha256())) {
            warnings.add("New Jar hash changed");
        }
        if (dropped > 0) {
            warnings.add(dropped + " saved mappings or resolutions could not be validated");
        }
        long missingLibraries = java.util.stream.Stream.concat(
                        request.leftLibraries().stream(), request.rightLibraries().stream())
                .filter(path -> !java.nio.file.Files.isRegularFile(path) && !java.nio.file.Files.isDirectory(path))
                .distinct()
                .count();
        if (missingLibraries > 0) {
            warnings.add(missingLibraries + " dependency paths are missing");
        }
        return String.join("; ", warnings);
    }

    private static String renderResource(ResourceContent content) {
        if (content.text()) {
            String text = new String(content.bytes(), StandardCharsets.UTF_8);
            return content.truncated() ? text + "\n\n… [preview truncated]" : text;
        }
        StringBuilder result = new StringBuilder();
        byte[] bytes = content.bytes();
        for (int offset = 0; offset < bytes.length; offset += 16) {
            result.append(String.format("%08x  ", offset));
            int end = Math.min(bytes.length, offset + 16);
            for (int index = offset; index < offset + 16; index++) {
                result.append(index < end ? String.format("%02x ", bytes[index] & 0xff) : "   ");
            }
            result.append(' ');
            for (int index = offset; index < end; index++) {
                int value = bytes[index] & 0xff;
                result.append(value >= 32 && value < 127 ? (char) value : '.');
            }
            result.append('\n');
        }
        if (content.truncated()) {
            result.append("\n… [preview truncated]\n");
        }
        return result.toString();
    }

    private static ArtifactReference reference(ArtifactSnapshot artifact) {
        return new ArtifactReference(artifact.path().toString(), artifact.sha256(), artifact.fileSize(),
                artifact.lastModified());
    }

    private Workspace requireWorkspace() {
        Workspace current = workspace;
        if (current == null) {
            throw new IllegalStateException("No comparison workspace is open");
        }
        return current;
    }

    private void resetDocumentState(boolean savedProject) {
        documentRevision.set(savedProject ? 0 : 1);
        savedRevision = 0;
    }

    private void notifyDocumentStateListeners() {
        boolean unsaved = hasUnsavedChanges();
        for (Consumer<Boolean> listener : documentStateListeners) {
            listener.accept(unsaved);
        }
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static void onEdt(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }

    private static void progress(Consumer<ProgressUpdate> listener, ProgressUpdate update) {
        onEdt(() -> listener.accept(update));
    }

    @FunctionalInterface
    private interface IoSupplier<T> {
        T get() throws Exception;
    }

    private record FilteredOverrides(ComparisonOverrides overrides, int dropped) { }

    public enum SearchSide { OLD, NEW, ALL }

    public enum SearchType { CLASS, MEMBER, FILE, TEXT }

    public enum CanonicalNamesDirection {
        LEFT_TO_RIGHT,
        RIGHT_TO_LEFT,
        DISABLED;

        public boolean remaps(boolean leftSide) {
            return switch (this) {
                case LEFT_TO_RIGHT -> leftSide;
                case RIGHT_TO_LEFT -> !leftSide;
                case DISABLED -> false;
            };
        }
    }

    public record SearchRequest(
            String query,
            SearchMatcher matcher,
            SearchSide side,
            Set<SearchType> types,
            CanonicalNamesDirection canonicalNames,
            CancellationToken cancellation) {
        public SearchRequest {
            query = query == null ? "" : query;
            matcher = matcher == null ? SearchMatcher.CONTAINS_IGNORE_CASE : matcher;
            side = side == null ? SearchSide.ALL : side;
            types = types == null || types.isEmpty() ? Set.of(SearchType.CLASS, SearchType.MEMBER,
                    SearchType.FILE, SearchType.TEXT) : Set.copyOf(types);
            canonicalNames = canonicalNames == null ? CanonicalNamesDirection.DISABLED : canonicalNames;
            cancellation = cancellation == null ? CancellationToken.NONE : cancellation;
        }

        public SearchRequest(
                String query,
                SearchMatcher matcher,
                SearchSide side,
                Set<SearchType> types,
                boolean canonical,
                CancellationToken cancellation) {
            this(query, matcher, side, types,
                    canonical ? CanonicalNamesDirection.RIGHT_TO_LEFT : CanonicalNamesDirection.DISABLED,
                    cancellation);
        }

        public SearchRequest(
                String query,
                SearchSide side,
                Set<SearchType> types,
                boolean canonical,
                CancellationToken cancellation) {
            this(query, SearchMatcher.CONTAINS_IGNORE_CASE, side, types,
                    canonical ? CanonicalNamesDirection.RIGHT_TO_LEFT : CanonicalNamesDirection.DISABLED,
                    cancellation);
        }
    }

    public record SearchHit(
            boolean leftSide,
            SearchType type,
            EntityId entity,
            String path,
            int line,
            String preview) { }

    public record SearchProgress(int completed, int total) { }

    public record CompareRequest(
            Path leftJar,
            Path rightJar,
            int targetRelease,
            List<Path> leftLibraries,
            List<Path> rightLibraries,
            ComparisonOverrides initialOverrides,
            Path projectPath,
            String expectedLeftHash,
            String expectedRightHash,
            ProjectUiState uiState) {
        public CompareRequest {
            leftLibraries = List.copyOf(leftLibraries);
            rightLibraries = List.copyOf(rightLibraries);
            initialOverrides = initialOverrides == null ? ComparisonOverrides.EMPTY : initialOverrides;
            expectedLeftHash = expectedLeftHash == null ? "" : expectedLeftHash;
            expectedRightHash = expectedRightHash == null ? "" : expectedRightHash;
            uiState = uiState == null ? ProjectUiState.empty() : uiState;
        }

        public static CompareRequest fresh(
                Path left, Path right, int release, List<Path> leftLibraries, List<Path> rightLibraries) {
            return new CompareRequest(left, right, release, leftLibraries, rightLibraries,
                    ComparisonOverrides.EMPTY, null, "", "",
                    ProjectUiState.empty());
        }

        public static CompareRequest fresh(
                Path left, Path right, List<Path> leftLibraries, List<Path> rightLibraries) {
            return fresh(left, right, 21, leftLibraries, rightLibraries);
        }
    }

    public record ProgressUpdate(String stage, long completed, long total) { }

    public record PhaseTimings(long overridesMillis, long matchingMillis, long diffMillis) {
        public static final PhaseTimings ZERO = new PhaseTimings(0, 0, 0);
    }

    public record AnalysisUpdate(
            Workspace workspace,
            Set<EntityId> affectedEntities,
            boolean fullRefresh,
            PhaseTimings timings) {
        public AnalysisUpdate {
            affectedEntities = Set.copyOf(affectedEntities);
        }
    }

    public record Workspace(
            ArtifactSnapshot left,
            ArtifactSnapshot right,
            MatchResult matches,
            DiffResult differences,
            ComparisonOverrides overrides,
            List<Path> leftLibraries,
            List<Path> rightLibraries,
            Path projectPath,
            String warning,
            ProjectUiState uiState) {
        public Workspace {
            overrides = overrides == null ? ComparisonOverrides.EMPTY : overrides;
            leftLibraries = List.copyOf(leftLibraries);
            rightLibraries = List.copyOf(rightLibraries);
            warning = warning == null ? "" : warning;
            uiState = uiState == null ? ProjectUiState.empty() : uiState;
        }

        public Workspace(
                ArtifactSnapshot left,
                ArtifactSnapshot right,
                MatchResult matches,
                DiffResult differences,
                Map<EntityId, EntityId> lockedMappings,
                List<Path> leftLibraries,
                List<Path> rightLibraries,
                Path projectPath,
                String warning,
                ProjectUiState uiState) {
            this(left, right, matches, differences, new ComparisonOverrides(lockedMappings), leftLibraries,
                    rightLibraries, projectPath, warning, uiState);
        }

        public Map<EntityId, EntityId> lockedMappings() {
            return overrides.lockedMappings();
        }

        public Set<EntityId> confirmedRemoved() {
            return overrides.confirmedRemoved();
        }

        public Set<EntityId> confirmedAdded() {
            return overrides.confirmedAdded();
        }

        public Set<DetachedPair> detachedPairs() {
            return overrides.detachedPairs();
        }

        Workspace withAnalysis(
                MatchResult newMatches, DiffResult newDifferences, ComparisonOverrides newOverrides) {
            return new Workspace(left, right, newMatches, newDifferences, newOverrides, leftLibraries, rightLibraries,
                    projectPath, warning, uiState);
        }

        Workspace withProjectPath(Path path) {
            return new Workspace(left, right, matches, differences, overrides, leftLibraries, rightLibraries,
                    path, warning, uiState);
        }

        Workspace withUiState(ProjectUiState state) {
            return new Workspace(left, right, matches, differences, overrides, leftLibraries, rightLibraries,
                    projectPath, warning, state);
        }
    }
}
