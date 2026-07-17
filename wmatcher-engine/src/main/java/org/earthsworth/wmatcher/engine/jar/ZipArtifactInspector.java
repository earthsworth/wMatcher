package org.earthsworth.wmatcher.engine.jar;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.earthsworth.wmatcher.core.model.ArtifactSnapshot;
import org.earthsworth.wmatcher.core.model.ClassModel;
import org.earthsworth.wmatcher.core.model.ResourceContent;
import org.earthsworth.wmatcher.core.model.ResourceModel;
import org.earthsworth.wmatcher.core.service.ArtifactInspector;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceClassVisitor;

public final class ZipArtifactInspector implements ArtifactInspector {
    @Override
    public String bytecodeText(ArtifactSnapshot artifact, String className, boolean semantic) throws IOException {
        ClassModel model = artifact.classes().get(className);
        if (model == null) {
            throw new IOException("Class not found: " + className);
        }
        byte[] bytes = readEntry(artifact, model.entryName(), 64 * 1024 * 1024);
        StringWriter output = new StringWriter();
        TraceClassVisitor trace = new TraceClassVisitor(null, new Textifier(), new PrintWriter(output));
        int flags = semantic ? ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES : 0;
        new ClassReader(bytes).accept(trace, flags);
        return output.toString();
    }

    @Override
    public ResourceContent resourceContent(ArtifactSnapshot artifact, String resourcePath, int maximumBytes)
            throws IOException {
        if (maximumBytes < 1) {
            throw new IllegalArgumentException("maximumBytes must be positive");
        }
        ResourceModel model = artifact.resources().get(resourcePath);
        if (model == null) {
            throw new IOException("Resource not found: " + resourcePath);
        }
        try (ZipFile zip = new ZipFile(artifact.path().toFile())) {
            ZipEntry entry = zip.getEntry(resourcePath);
            if (entry == null) {
                throw new IOException("Resource entry is missing: " + resourcePath);
            }
            try (InputStream input = zip.getInputStream(entry); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                boolean truncated = false;
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    int remaining = maximumBytes - output.size();
                    if (remaining <= 0) {
                        truncated = true;
                        break;
                    }
                    output.write(buffer, 0, Math.min(remaining, read));
                    if (read > remaining) {
                        truncated = true;
                        break;
                    }
                }
                return new ResourceContent(output.toByteArray(), truncated, model.likelyText());
            }
        }
    }

    private static byte[] readEntry(ArtifactSnapshot artifact, String entryName, int maximumBytes) throws IOException {
        try (ZipFile zip = new ZipFile(artifact.path().toFile())) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                throw new IOException("Jar entry is missing: " + entryName);
            }
            try (InputStream input = zip.getInputStream(entry); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (output.size() + read > maximumBytes) {
                        throw new IOException("Class entry exceeds inspection limit: " + entryName);
                    }
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            }
        }
    }
}
