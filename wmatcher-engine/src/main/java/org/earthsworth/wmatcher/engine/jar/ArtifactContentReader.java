package org.earthsworth.wmatcher.engine.jar;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.earthsworth.wmatcher.core.model.ArtifactSnapshot;

/** Reads an indexed artifact entry without exposing archive details to callers. */
public final class ArtifactContentReader {
    private ArtifactContentReader() { }

    public static InputStream open(ArtifactSnapshot artifact, String entryName) throws IOException {
        Path path = artifact.path();
        if (Files.isDirectory(path)) {
            Path root = path.toAbsolutePath().normalize();
            Path entry = root.resolve(entryName.replace('/', java.io.File.separatorChar)).normalize();
            if (!entry.startsWith(root) || !Files.isRegularFile(entry)) {
                throw new IOException("Artifact entry is missing: " + entryName);
            }
            return Files.newInputStream(entry);
        }
        ZipFile zip = new ZipFile(path.toFile());
        ZipEntry entry = zip.getEntry(entryName);
        if (entry == null) {
            zip.close();
            throw new IOException("Archive entry is missing: " + entryName);
        }
        InputStream input = zip.getInputStream(entry);
        return new java.io.FilterInputStream(input) {
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    zip.close();
                }
            }
        };
    }

    public static byte[] read(ArtifactSnapshot artifact, String entryName, int maximumBytes) throws IOException {
        try (InputStream input = open(artifact, entryName); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (output.size() + read > maximumBytes) {
                    throw new IOException("Artifact entry exceeds inspection limit: " + entryName);
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }
}
