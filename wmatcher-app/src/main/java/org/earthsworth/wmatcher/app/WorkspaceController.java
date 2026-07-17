package org.earthsworth.wmatcher.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
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
import javax.swing.SwingUtilities;
import org.earthsworth.wmatcher.core.model.ArtifactSnapshot;
import org.earthsworth.wmatcher.core.model.ComparisonOverrides;
import org.earthsworth.wmatcher.core.model.DecompileRequest;
import org.earthsworth.wmatcher.core.model.DiffNode;
import org.earthsworth.wmatcher.core.model.DiffResult;
import org.earthsworth.wmatcher.core.model.EntityId;
import org.earthsworth.wmatcher.core.model.EntityKind;
import org.earthsworth.wmatcher.core.model.MatchDecision;
import org.earthsworth.wmatcher.core.model.MatchResult;
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
import org.earthsworth.wmatcher.core.service.MappingService;
import org.earthsworth.wmatcher.core.service.MatchingEngine;
import org.earthsworth.wmatcher.core.service.ProjectRepository;
import org.earthsworth.wmatcher.core.task.CancellationToken;
import org.earthsworth.wmatcher.engine.decompile.VineflowerDecompilerService;
import org.earthsworth.wmatcher.engine.diff.DefaultDiffEngine;
import org.earthsworth.wmatcher.engine.jar.JarArtifactLoader;
import org.earthsworth.wmatcher.engine.jar.ZipArtifactInspector;
import org.earthsworth.wmatcher.engine.mapping.MappingIoService;
import org.earthsworth.wmatcher.engine.match.DefaultMatchingEngine;
import org.earthsworth.wmatcher.engine.project.JacksonProjectRepository;

public final class WorkspaceController implements AutoCloseable {
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
    private volatile AtomicBoolean cancellation = new AtomicBoolean();
    private volatile Future<?> activeJob;
    private volatile Workspace workspace;
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
                MatchResult matchResult = matcher.match(left, right, filtered.overrides(),
                        MatchingPolicy.conservativeV1(),
                        (stage, completed, total) -> progress(progress, new ProgressUpdate(stage, completed, total)), token);
                DiffResult diffResult = differ.diff(left, right, matchResult, filtered.overrides(),
                        (stage, completed, total) -> progress(progress, new ProgressUpdate(stage, completed, total)), token);
                String warning = warning(request, left, right, filtered.dropped());
                Workspace result = new Workspace(left, right, matchResult, diffResult, filtered.overrides(),
                        request.leftLibraries(), request.rightLibraries(), request.projectPath(), warning,
                        request.uiState());
                workspace = result;
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
                        List.of(),
                        List.of(),
                        new ComparisonOverrides(project.lockedMappings(), project.confirmedRemoved(),
                                project.confirmedAdded()),
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
        updated.entrySet().removeIf(entry -> entry.getKey().equals(decision.left())
                || entry.getValue().equals(decision.right()));
        removed.remove(decision.left());
        added.remove(decision.right());
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
        pushAndRecompute(current.overrides(), new ComparisonOverrides(updated, removed, added), success, failure);
    }

    public void unlockMapping(EntityId left, Consumer<Workspace> success, Consumer<Throwable> failure) {
        Workspace current = requireWorkspace();
        if (!current.lockedMappings().containsKey(left)) {
            return;
        }
        Map<EntityId, EntityId> updated = new LinkedHashMap<>(current.lockedMappings());
        EntityId previousRight = updated.remove(left);
        if (left.kind() == EntityKind.CLASS && previousRight != null) {
            updated.entrySet().removeIf(entry -> isMember(entry.getKey())
                    && (entry.getKey().owner().equals(left.name())
                    || entry.getValue().owner().equals(previousRight.name())));
        }
        pushAndRecompute(current.overrides(), new ComparisonOverrides(
                updated, current.confirmedRemoved(), current.confirmedAdded()), success, failure);
    }

    public void confirmSingleSided(
            DiffNode node, Consumer<Workspace> success, Consumer<Throwable> failure) {
        Workspace current = requireWorkspace();
        if ((node.left() == null) == (node.right() == null)) {
            return;
        }
        Set<EntityId> removed = new HashSet<>(current.confirmedRemoved());
        Set<EntityId> added = new HashSet<>(current.confirmedAdded());
        if (node.left() != null) {
            removed.add(node.left());
        } else {
            added.add(node.right());
        }
        pushAndRecompute(current.overrides(), new ComparisonOverrides(
                current.lockedMappings(), removed, added), success, failure);
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
                current.lockedMappings(), removed, added), success, failure);
    }

    public void undoMappings(Consumer<Workspace> success, Consumer<Throwable> failure) {
        if (undo.isEmpty()) {
            return;
        }
        Workspace current = requireWorkspace();
        ComparisonOverrides target = undo.pop();
        redo.push(current.overrides());
        recompute(target, success, failure);
    }

    public void redoMappings(Consumer<Workspace> success, Consumer<Throwable> failure) {
        if (redo.isEmpty()) {
            return;
        }
        Workspace current = requireWorkspace();
        ComparisonOverrides target = redo.pop();
        undo.push(current.overrides());
        recompute(target, success, failure);
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
            DiffNode node, boolean leftSide, boolean canonical, Consumer<String> success, Consumer<Throwable> failure) {
        Workspace current = requireWorkspace();
        EntityId id = leftSide ? node.left() : node.right();
        if (id == null || id.kind() == EntityKind.RESOURCE) {
            success.accept("");
            return;
        }
        ArtifactSnapshot artifact = leftSide ? current.left() : current.right();
        List<Path> libraries = new ArrayList<>(leftSide ? current.leftLibraries() : current.rightLibraries());
        libraries.add(leftSide ? current.right().path() : current.left().path());
        DecompileRequest request = new DecompileRequest(
                artifact,
                id.kind() == EntityKind.CLASS ? id.name() : id.owner(),
                current.matches().confirmedMappings(),
                canonical && !leftSide,
                libraries);
        AtomicBoolean requestCancellation = cancellation;
        async(() -> decompiler.decompile(request, requestCancellation::get).source(), success, failure);
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
        executor.shutdownNow();
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
        recompute(updated, success, failure);
    }

    private void recompute(
            ComparisonOverrides overrides,
            Consumer<Workspace> success,
            Consumer<Throwable> failure) {
        Workspace current = requireWorkspace();
        executor.submit(() -> {
            try {
                MatchResult matchResult = matcher.match(current.left(), current.right(), overrides,
                        MatchingPolicy.conservativeV1(), (stage, completed, total) -> { }, CancellationToken.NONE);
                DiffResult diffResult = differ.diff(current.left(), current.right(), matchResult, overrides,
                        (stage, completed, total) -> { }, CancellationToken.NONE);
                Workspace updated = current.withAnalysis(matchResult, diffResult, overrides);
                workspace = updated;
                documentRevision.incrementAndGet();
                onEdt(() -> {
                    notifyDocumentStateListeners();
                    success.accept(updated);
                });
            } catch (Throwable throwable) {
                onEdt(() -> failure.accept(unwrap(throwable)));
            }
        });
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
                        merged, current.confirmedRemoved(), current.confirmedAdded());
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
                    && !merged.containsKey(left) && usedRight.add(right)) {
                merged.put(left, right);
            }
        });
        return merged;
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
        Map<EntityId, EntityId> result = new LinkedHashMap<>();
        Set<EntityId> rightUsed = new HashSet<>();
        int dropped = requested.confirmedRemoved().size() - removed.size()
                + requested.confirmedAdded().size() - added.size();
        for (Map.Entry<EntityId, EntityId> entry : requested.lockedMappings().entrySet()) {
            if (entityExists(left, entry.getKey()) && entityExists(right, entry.getValue())
                    && !removed.contains(entry.getKey()) && !added.contains(entry.getValue())
                    && rightUsed.add(entry.getValue())) {
                result.put(entry.getKey(), entry.getValue());
            } else {
                dropped++;
            }
        }
        return new FilteredOverrides(new ComparisonOverrides(result, removed, added), dropped);
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
    }

    public record ProgressUpdate(String stage, long completed, long total) { }

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

        Workspace withAnalysis(
                MatchResult newMatches, DiffResult newDifferences, ComparisonOverrides newOverrides) {
            return new Workspace(left, right, newMatches, newDifferences, newOverrides, leftLibraries, rightLibraries,
                    projectPath, warning, uiState);
        }

        Workspace withProjectPath(Path path) {
            return new Workspace(left, right, matches, differences, overrides, leftLibraries, rightLibraries,
                    path, warning, uiState);
        }
    }
}
