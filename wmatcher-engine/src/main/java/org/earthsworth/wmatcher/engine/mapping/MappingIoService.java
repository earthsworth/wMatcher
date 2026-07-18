package org.earthsworth.wmatcher.engine.mapping;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.fabricmc.mappingio.MappedElementKind;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.MappingWriter;
import net.fabricmc.mappingio.format.MappingFormat;
import net.fabricmc.mappingio.tree.MappingTreeView;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import org.earthsworth.wmatcher.core.model.ArtifactSnapshot;
import org.earthsworth.wmatcher.core.model.ClassModel;
import org.earthsworth.wmatcher.core.model.EntityId;
import org.earthsworth.wmatcher.core.model.EntityKind;
import org.earthsworth.wmatcher.core.model.MatchDecision;
import org.earthsworth.wmatcher.core.model.MappingFileFormat;
import org.earthsworth.wmatcher.core.model.MappingImportResult;
import org.earthsworth.wmatcher.core.model.MappingNamespaces;
import org.earthsworth.wmatcher.core.model.MatchResult;
import org.earthsworth.wmatcher.core.service.MappingService;

public final class MappingIoService implements MappingService {
    @Override
    public MappingImportResult importMappings(
            MappingFileFormat format,
            Path path,
            MappingNamespaces namespaces,
            ArtifactSnapshot left,
            ArtifactSnapshot right) throws IOException {
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(path, "path");
        MemoryMappingTree tree = readTree(path, readFormat(format, path));
        int sourceNamespace = namespaceId(tree, namespaces == null ? tree.getSrcNamespace() : namespaces.source());
        int targetNamespace = namespaceId(tree, namespaces == null
                ? firstTargetNamespace(tree) : namespaces.target());
        if (sourceNamespace == MappingTreeView.NULL_NAMESPACE_ID
                || targetNamespace == MappingTreeView.NULL_NAMESPACE_ID) {
            throw new IOException("Mapping namespaces are not present in the selected file");
        }
        Map<EntityId, EntityId> result = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        int[] skipped = {0};
        Map<String, String> classNames = new LinkedHashMap<>();
        for (MappingTreeView.ClassMappingView mappedClass : tree.getClasses()) {
            String leftName = className(mappedClass.getName(sourceNamespace));
            String rightName = className(mappedClass.getName(targetNamespace));
            if (leftName == null || rightName == null) {
                skipped[0]++;
                continue;
            }
            EntityId leftId = EntityId.classId(leftName);
            EntityId rightId = EntityId.classId(rightName);
            if (!entityExists(left, leftId) || !entityExists(right, rightId)
                    || !putOneToOne(result, leftId, rightId)) {
                skipped[0]++;
                continue;
            }
            classNames.put(leftName, rightName);
        }
        for (MappingTreeView.ClassMappingView mappedClass : tree.getClasses()) {
            String leftOwner = className(mappedClass.getName(sourceNamespace));
            String rightOwner = className(mappedClass.getName(targetNamespace));
            if (leftOwner == null || rightOwner == null
                    || !result.containsKey(EntityId.classId(leftOwner))) {
                continue;
            }
            for (MappingTreeView.FieldMappingView field : mappedClass.getFields()) {
                addMemberMapping(field, EntityKind.FIELD, leftOwner, rightOwner, sourceNamespace,
                        targetNamespace, classNames, result, left, right, skipped);
            }
            for (MappingTreeView.MethodMappingView method : mappedClass.getMethods()) {
                addMemberMapping(method, EntityKind.METHOD, leftOwner, rightOwner, sourceNamespace,
                        targetNamespace, classNames, result, left, right, skipped);
            }
        }
        if (skipped[0] > 0) {
            warnings.add(skipped[0] + " mapping entries could not be matched to both artifacts");
        }
        return new MappingImportResult(result, skipped[0], warnings);
    }

    @Override
    public List<String> namespaces(Path path, MappingFileFormat format) throws IOException {
        MappingFormat mappingFormat = readFormat(format, path);
        if (!mappingFormat.features().hasNamespaces()) {
            return List.of("source", "target");
        }
        return MappingReader.getNamespaces(path, mappingFormat);
    }

    @Override
    public void exportMappings(Path path, MappingFileFormat format, MatchResult matches) throws IOException {
        MappingFormat mappingFormat = writeFormat(format, path);
        validateExportCompatibility(format, matches);
        MemoryMappingTree tree = mappingTree(matches);
        Path destination = path.toAbsolutePath().normalize();
        Path parent = destination.getParent();
        if (parent == null) {
            throw new IOException("Mapping destination has no parent directory");
        }
        Files.createDirectories(parent);
        Path temporary = mappingFormat == MappingFormat.ENIGMA_DIR
                ? parent.resolve(destination.getFileName() + ".tmp-" + UUID.randomUUID())
                : Files.createTempFile(parent, destination.getFileName().toString(), ".tmp");
        try {
            if (mappingFormat == MappingFormat.ENIGMA_DIR) {
                Files.createDirectories(temporary);
            }
            MappingWriter writer = MappingWriter.create(temporary, mappingFormat);
            if (writer == null) {
                throw new IOException("Mapping format cannot be written: " + mappingFormat.name);
            }
            try (writer) {
                tree.accept(writer);
            }
            if (Files.isDirectory(destination)) {
                deleteTree(destination);
            }
            try {
                Files.move(temporary, destination, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            if (Files.isDirectory(temporary)) {
                deleteTree(temporary);
            } else {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static void validateExportCompatibility(MappingFileFormat format, MatchResult matches)
            throws IOException {
        if (format != MappingFileFormat.JADX_LEGACY) return;
        boolean packageMove = matches.confirmedMappings().entrySet().stream()
                .filter(entry -> entry.getKey().kind() == EntityKind.CLASS)
                .anyMatch(entry -> packageName(entry.getKey().name()).equals(packageName(entry.getValue().name())) == false);
        if (packageMove) {
            throw new IOException("Jadx (Legacy) cannot represent class package moves");
        }
    }

    private static String packageName(String name) {
        int slash = name.lastIndexOf('/');
        return slash < 0 ? "" : name.substring(0, slash);
    }

    private static MappingFormat readFormat(MappingFileFormat format, Path path) throws IOException {
        MappingFormat expected = switch (format) {
            case ENIGMA -> Files.isDirectory(path) ? MappingFormat.ENIGMA_DIR : MappingFormat.ENIGMA_FILE;
            case JADX_LEGACY -> MappingFormat.JOBF_FILE;
            case PROGUARD -> MappingFormat.PROGUARD_FILE;
            case SRG -> MappingFormat.SRG_FILE;
            case SIMPLE -> MappingFormat.RECAF_SIMPLE_FILE;
            case TINY_V1 -> MappingFormat.TINY_FILE;
            case TINY_V2 -> MappingFormat.TINY_2_FILE;
        };
        if (Files.isDirectory(path) && expected != MappingFormat.ENIGMA_DIR) {
            throw new IOException(format + " mappings must be a file");
        }
        return expected;
    }

    private static MappingFormat writeFormat(MappingFileFormat format, Path path) {
        return format == MappingFileFormat.ENIGMA && Files.isDirectory(path)
                ? MappingFormat.ENIGMA_DIR : switch (format) {
                    case ENIGMA -> MappingFormat.ENIGMA_FILE;
                    case JADX_LEGACY -> MappingFormat.JOBF_FILE;
                    case PROGUARD -> MappingFormat.PROGUARD_FILE;
                    case SRG -> MappingFormat.SRG_FILE;
                    case SIMPLE -> MappingFormat.RECAF_SIMPLE_FILE;
                    case TINY_V1 -> MappingFormat.TINY_FILE;
                    case TINY_V2 -> MappingFormat.TINY_2_FILE;
                };
    }

    private static String firstTargetNamespace(MemoryMappingTree tree) throws IOException {
        if (tree.getDstNamespaces().isEmpty()) {
            throw new IOException("Mapping does not contain a destination namespace");
        }
        return tree.getDstNamespaces().getFirst();
    }

    private static int namespaceId(MemoryMappingTree tree, String namespace) {
        if (tree.getSrcNamespace().equals(namespace)) return MappingTreeView.SRC_NAMESPACE_ID;
        return tree.getNamespaceId(namespace);
    }

    private static String className(String value) {
        return value == null ? null : value.replace('.', '/');
    }

    private static void addMemberMapping(
            MappingTreeView.MemberMappingView member,
            EntityKind kind,
            String leftOwner,
            String rightOwner,
            int sourceNamespace,
            int targetNamespace,
            Map<String, String> classNames,
            Map<EntityId, EntityId> result,
            ArtifactSnapshot left,
            ArtifactSnapshot right,
            int[] skipped) {
        String leftName = member.getName(sourceNamespace);
        String rightName = member.getName(targetNamespace);
        String sourceDescriptor = member.getDesc(sourceNamespace);
        String targetDescriptor = member.getDesc(targetNamespace);
        if (sourceDescriptor == null) sourceDescriptor = member.getSrcDesc();
        if (targetDescriptor == null && sourceDescriptor != null) {
            targetDescriptor = remapDescriptor(sourceDescriptor, classNames);
        }
        if (sourceDescriptor == null) {
            sourceDescriptor = uniqueDescriptor(left, kind, leftOwner, leftName);
        }
        if (targetDescriptor == null) {
            targetDescriptor = uniqueDescriptor(right, kind, rightOwner, rightName);
        }
        if (leftName == null || rightName == null || sourceDescriptor == null || targetDescriptor == null) {
            skipped[0]++;
            return;
        }
        EntityId leftId = new EntityId(kind, leftOwner, leftName, sourceDescriptor);
        EntityId rightId = new EntityId(kind, rightOwner, rightName, targetDescriptor);
        if (!entityExists(left, leftId) || !entityExists(right, rightId)
                || !putOneToOne(result, leftId, rightId)) {
            skipped[0]++;
        }
    }

    private static String remapDescriptor(String descriptor, Map<String, String> classNames) {
        String result = descriptor;
        List<Map.Entry<String, String>> entries = classNames.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparingInt(String::length).reversed()))
                .toList();
        for (Map.Entry<String, String> entry : entries) {
            result = result.replace("L" + entry.getKey() + ";", "L" + entry.getValue() + ";");
        }
        return result;
    }

    private static String uniqueDescriptor(
            ArtifactSnapshot artifact, EntityKind kind, String owner, String name) {
        if (name == null) return null;
        if (kind == EntityKind.FIELD) {
            List<String> descriptors = artifact.classes().get(owner).fields().stream()
                    .filter(field -> field.name().equals(name)).map(org.earthsworth.wmatcher.core.model.FieldModel::descriptor)
                    .toList();
            return descriptors.size() == 1 ? descriptors.getFirst() : null;
        }
        List<String> descriptors = artifact.classes().get(owner).methods().stream()
                .filter(method -> method.name().equals(name)).map(org.earthsworth.wmatcher.core.model.MethodModel::descriptor)
                .toList();
        return descriptors.size() == 1 ? descriptors.getFirst() : null;
    }

    private static boolean putOneToOne(Map<EntityId, EntityId> mappings, EntityId left, EntityId right) {
        if (mappings.containsKey(left) || mappings.containsValue(right)) return false;
        mappings.put(left, right);
        return true;
    }

    private static MemoryMappingTree mappingTree(MatchResult matches) throws IOException {
        MemoryMappingTree tree = new MemoryMappingTree();
        tree.visitNamespaces("old", List.of("new"));
        List<Map.Entry<EntityId, EntityId>> classes = matches.confirmedMappings().entrySet().stream()
                .filter(entry -> entry.getKey().kind() == EntityKind.CLASS)
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(EntityId::externalName)))
                .toList();
        for (Map.Entry<EntityId, EntityId> classEntry : classes) {
            EntityId oldClass = classEntry.getKey();
            tree.visitClass(oldClass.name());
            tree.visitDstName(MappedElementKind.CLASS, 0, classEntry.getValue().name());
            tree.visitElementContent(MappedElementKind.CLASS);
            List<Map.Entry<EntityId, EntityId>> members = matches.confirmedMappings().entrySet().stream()
                    .filter(entry -> entry.getKey().owner().equals(oldClass.name()))
                    .sorted(Map.Entry.comparingByKey(Comparator.comparing(EntityId::externalName)))
                    .toList();
            for (Map.Entry<EntityId, EntityId> member : members) {
                visitMember(tree, member);
            }
        }
        tree.visitEnd();
        return tree;
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var stream = Files.walk(path)) {
            for (Path child : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(child);
            }
        }
    }

    @Override
    public Map<EntityId, EntityId> importTiny(
            Path path, ArtifactSnapshot left, ArtifactSnapshot right) throws IOException {
        MemoryMappingTree tree = readTree(path, MappingFormat.TINY_2_FILE);
        if (tree.getDstNamespaces().isEmpty()) {
            throw new IOException("Tiny v2 mapping does not contain a destination namespace");
        }
        Map<EntityId, EntityId> result = new LinkedHashMap<>();
        for (MappingTreeView.ClassMappingView classMapping : tree.getClasses()) {
            String leftName = classMapping.getSrcName();
            String rightName = classMapping.getDstName(0);
            if (rightName == null || !left.classes().containsKey(leftName) || !right.classes().containsKey(rightName)) {
                continue;
            }
            result.put(EntityId.classId(leftName), EntityId.classId(rightName));
            for (MappingTreeView.FieldMappingView field : classMapping.getFields()) {
                String rightFieldName = field.getDstName(0);
                String rightDescriptor = field.getDstDesc(0);
                if (rightFieldName != null && rightDescriptor != null) {
                    putIfPresent(result,
                            EntityId.fieldId(leftName, field.getSrcName(), field.getSrcDesc()),
                            EntityId.fieldId(rightName, rightFieldName, rightDescriptor), left, right);
                }
            }
            for (MappingTreeView.MethodMappingView method : classMapping.getMethods()) {
                String rightMethodName = method.getDstName(0);
                String rightDescriptor = method.getDstDesc(0);
                if (rightMethodName != null && rightDescriptor != null) {
                    putIfPresent(result,
                            EntityId.methodId(leftName, method.getSrcName(), method.getSrcDesc()),
                            EntityId.methodId(rightName, rightMethodName, rightDescriptor), left, right);
                }
            }
        }
        validateOneToOne(result);
        return Map.copyOf(result);
    }

    @Override
    public Map<EntityId, EntityId> importProguard(
            Path leftMapping,
            Path rightMapping,
            ArtifactSnapshot left,
            ArtifactSnapshot right) throws IOException {
        if (leftMapping == null || rightMapping == null) {
            throw new IOException("Both old-side and new-side ProGuard/R8 mappings are required");
        }
        Map<String, EntityId> leftCanonical = canonicalEntities(readTree(leftMapping, MappingFormat.PROGUARD_FILE), left);
        Map<String, EntityId> rightCanonical = canonicalEntities(readTree(rightMapping, MappingFormat.PROGUARD_FILE), right);
        Map<EntityId, EntityId> result = new LinkedHashMap<>();
        leftCanonical.forEach((canonical, leftId) -> {
            EntityId rightId = rightCanonical.get(canonical);
            if (rightId != null && leftId.kind() == rightId.kind()) {
                result.put(leftId, rightId);
            }
        });
        validateOneToOne(result);
        return Map.copyOf(result);
    }

    @Override
    public void exportTiny(Path path, MatchResult matches) throws IOException {
        MemoryMappingTree tree = new MemoryMappingTree();
        tree.visitNamespaces("old", List.of("new"));
        Map<EntityId, EntityId> mappings = matches.confirmedMappings();
        List<Map.Entry<EntityId, EntityId>> classes = mappings.entrySet().stream()
                .filter(entry -> entry.getKey().kind() == EntityKind.CLASS)
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(EntityId::externalName)))
                .toList();
        for (Map.Entry<EntityId, EntityId> classEntry : classes) {
            EntityId leftClass = classEntry.getKey();
            EntityId rightClass = classEntry.getValue();
            tree.visitClass(leftClass.name());
            tree.visitDstName(MappedElementKind.CLASS, 0, rightClass.name());
            tree.visitElementContent(MappedElementKind.CLASS);
            List<Map.Entry<EntityId, EntityId>> members = mappings.entrySet().stream()
                    .filter(entry -> entry.getKey().owner().equals(leftClass.name()))
                    .sorted(Map.Entry.comparingByKey(Comparator.comparing(EntityId::externalName)))
                    .toList();
            for (Map.Entry<EntityId, EntityId> member : members) {
                visitMember(tree, member);
            }
        }
        tree.visitEnd();
        try (MappingWriter writer = MappingWriter.create(path, MappingFormat.TINY_2_FILE)) {
            tree.accept(writer);
        }
    }

    private static void visitMember(MemoryMappingTree tree, Map.Entry<EntityId, EntityId> entry) throws IOException {
        EntityId left = entry.getKey();
        EntityId right = entry.getValue();
        MappedElementKind kind;
        if (left.kind() == EntityKind.FIELD) {
            tree.visitField(left.name(), left.descriptor());
            kind = MappedElementKind.FIELD;
        } else if (left.kind() == EntityKind.METHOD) {
            tree.visitMethod(left.name(), left.descriptor());
            kind = MappedElementKind.METHOD;
        } else {
            return;
        }
        tree.visitDstName(kind, 0, right.name());
        tree.visitDstDesc(kind, 0, right.descriptor());
        tree.visitElementContent(kind);
    }

    private static MemoryMappingTree readTree(Path path, MappingFormat expected) throws IOException {
        MappingFormat actual = MappingReader.detectFormat(path);
        boolean compatible = actual == expected
                || expected == MappingFormat.RECAF_SIMPLE_FILE && actual == null
                || expected == MappingFormat.SRG_FILE && actual == MappingFormat.XSRG_FILE;
        if (!compatible) {
            throw new IOException("Expected " + expected.name + " mapping but found "
                    + (actual == null ? "unknown" : actual.name));
        }
        MemoryMappingTree tree = new MemoryMappingTree();
        MappingReader.read(path, actual == null ? expected : actual, tree);
        return tree;
    }

    private static Map<String, EntityId> canonicalEntities(MemoryMappingTree tree, ArtifactSnapshot artifact) {
        Map<String, EntityId> result = new LinkedHashMap<>();
        for (MappingTreeView.ClassMappingView mappedClass : tree.getClasses()) {
            String obfuscatedClass = mappedClass.getDstName(0);
            if (obfuscatedClass == null || !artifact.classes().containsKey(obfuscatedClass)) {
                continue;
            }
            result.put("C:" + mappedClass.getSrcName(), EntityId.classId(obfuscatedClass));
            for (MappingTreeView.FieldMappingView field : mappedClass.getFields()) {
                EntityId id = mappedMember(artifact, EntityKind.FIELD, obfuscatedClass,
                        field.getDstName(0), field.getDstDesc(0));
                if (id != null) {
                    result.put("F:" + mappedClass.getSrcName() + ':' + field.getSrcName() + ':' + field.getSrcDesc(), id);
                }
            }
            for (MappingTreeView.MethodMappingView method : mappedClass.getMethods()) {
                EntityId id = mappedMember(artifact, EntityKind.METHOD, obfuscatedClass,
                        method.getDstName(0), method.getDstDesc(0));
                if (id != null) {
                    result.put("M:" + mappedClass.getSrcName() + ':' + method.getSrcName() + ':' + method.getSrcDesc(), id);
                }
            }
        }
        return result;
    }

    private static EntityId mappedMember(
            ArtifactSnapshot artifact, EntityKind kind, String owner, String name, String descriptor) {
        if (name == null || descriptor == null) {
            return null;
        }
        EntityId id = new EntityId(kind, owner, name, descriptor);
        return entityExists(artifact, id) ? id : null;
    }

    private static void putIfPresent(
            Map<EntityId, EntityId> result,
            EntityId leftId,
            EntityId rightId,
            ArtifactSnapshot left,
            ArtifactSnapshot right) {
        if (entityExists(left, leftId) && entityExists(right, rightId)) {
            result.put(leftId, rightId);
        }
    }

    private static boolean entityExists(ArtifactSnapshot artifact, EntityId id) {
        ClassModel owner = artifact.classes().get(id.kind() == EntityKind.CLASS ? id.name() : id.owner());
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

    private static void validateOneToOne(Map<EntityId, EntityId> mappings) throws IOException {
        Set<EntityId> right = mappings.values().stream().collect(Collectors.toSet());
        if (right.size() != mappings.size()) {
            throw new IOException("Mapping contains conflicting destination entities");
        }
    }

}
