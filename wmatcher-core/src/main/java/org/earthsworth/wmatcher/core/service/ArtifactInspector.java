package org.earthsworth.wmatcher.core.service;

import java.io.IOException;
import org.earthsworth.wmatcher.core.model.ArtifactSnapshot;
import org.earthsworth.wmatcher.core.model.ResourceContent;

public interface ArtifactInspector {
    String bytecodeText(ArtifactSnapshot artifact, String className, boolean semantic) throws IOException;

    ResourceContent resourceContent(ArtifactSnapshot artifact, String resourcePath, int maximumBytes) throws IOException;
}
