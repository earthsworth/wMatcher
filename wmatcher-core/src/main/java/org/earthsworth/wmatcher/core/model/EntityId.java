package org.earthsworth.wmatcher.core.model;

import java.util.Objects;

public record EntityId(EntityKind kind, String owner, String name, String descriptor) {
    public EntityId {
        Objects.requireNonNull(kind, "kind");
        owner = owner == null ? "" : owner;
        name = Objects.requireNonNull(name, "name");
        descriptor = descriptor == null ? "" : descriptor;
    }

    public static EntityId classId(String internalName) {
        return new EntityId(EntityKind.CLASS, "", internalName, "");
    }

    public static EntityId fieldId(String owner, String name, String descriptor) {
        return new EntityId(EntityKind.FIELD, owner, name, descriptor);
    }

    public static EntityId methodId(String owner, String name, String descriptor) {
        return new EntityId(EntityKind.METHOD, owner, name, descriptor);
    }

    public static EntityId resourceId(String path) {
        return new EntityId(EntityKind.RESOURCE, "", path, "");
    }

    public String externalName() {
        return switch (kind) {
            case CLASS, RESOURCE -> name;
            case FIELD, METHOD -> owner + '.' + name + descriptor;
        };
    }
}
