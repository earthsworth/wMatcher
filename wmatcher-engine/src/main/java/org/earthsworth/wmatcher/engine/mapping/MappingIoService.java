package org.earthsworth.wmatcher.engine.mapping;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
import org.earthsworth.wmatcher.core.model.MatchResult;
import org.earthsworth.wmatcher.core.service.MappingService;

public final class MappingIoService implements MappingService {
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
        if (actual != expected) {
            throw new IOException("Expected " + expected.name + " mapping but found " + actual.name);
        }
        MemoryMappingTree tree = new MemoryMappingTree();
        MappingReader.read(path, expected, tree);
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
