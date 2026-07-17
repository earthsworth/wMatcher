package org.earthsworth.wmatcher.core.project;

public record ArtifactReference(String path, String sha256, long size, long lastModified) { }
