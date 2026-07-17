package org.earthsworth.wmatcher.core.service;

import java.io.IOException;
import java.nio.file.Path;
import org.earthsworth.wmatcher.core.model.ArtifactSnapshot;
import org.earthsworth.wmatcher.core.model.ScanOptions;
import org.earthsworth.wmatcher.core.task.CancellationToken;
import org.earthsworth.wmatcher.core.task.ProgressListener;

public interface ArtifactLoader {
    ArtifactSnapshot load(Path path, ScanOptions options, ProgressListener progress, CancellationToken cancellation)
            throws IOException;
}
