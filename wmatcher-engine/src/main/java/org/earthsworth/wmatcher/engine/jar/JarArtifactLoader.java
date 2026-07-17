package org.earthsworth.wmatcher.engine.jar;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import org.earthsworth.wmatcher.core.model.ArtifactSnapshot;
import org.earthsworth.wmatcher.core.model.ClassModel;
import org.earthsworth.wmatcher.core.model.ResourceModel;
import org.earthsworth.wmatcher.core.model.ScanOptions;
import org.earthsworth.wmatcher.core.service.ArtifactLoader;
import org.earthsworth.wmatcher.core.task.CancellationToken;
import org.earthsworth.wmatcher.core.task.ProgressListener;
import org.earthsworth.wmatcher.engine.asm.AsmClassModelReader;
import org.earthsworth.wmatcher.engine.util.Hashing;

public final class JarArtifactLoader implements ArtifactLoader {
    private static final long MAXIMUM_CLASS_BYTES = 64L * 1024 * 1024;
    private static final long MAXIMUM_MANIFEST_BYTES = 1024L * 1024;
    private final AsmClassModelReader classReader;

    public JarArtifactLoader() {
        this(new AsmClassModelReader());
    }

    JarArtifactLoader(AsmClassModelReader classReader) {
        this.classReader = classReader;
    }

    @Override
    public ArtifactSnapshot load(
            Path path,
            ScanOptions options,
            ProgressListener progress,
            CancellationToken cancellation) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new IOException("Jar does not exist or is not a regular file: " + normalized);
        }
        BasicFileAttributes attributes = Files.readAttributes(normalized, BasicFileAttributes.class);
        if (attributes.size() > options.maximumFileSize()) {
            throw new IOException("Jar exceeds the configured 1 GiB safety limit");
        }

        cancellation.throwIfCancelled();
        progress.onProgress("Hashing Jar", 0, attributes.size());
        String artifactHash = Hashing.sha256(normalized);

        try (ZipFile zip = new ZipFile(normalized.toFile())) {
            List<ZipEntry> entries = enumerateAndValidate(zip, options);
            Map<String, String> manifest = readManifest(zip);
            boolean multiRelease = Boolean.parseBoolean(manifest.getOrDefault("Multi-Release", "false"));
            Map<String, VersionedClassEntry> selectedClasses = selectClasses(entries, options.targetRelease(), multiRelease);
            Map<String, ClassModel> classes = new LinkedHashMap<>();
            Map<String, ResourceModel> resources = new LinkedHashMap<>();
            ExpandedBytes expanded = new ExpandedBytes(options.maximumExpandedBytes());

            List<VersionedClassEntry> orderedClasses = selectedClasses.values().stream()
                    .sorted(Comparator.comparing(VersionedClassEntry::logicalName))
                    .toList();
            long totalWork = orderedClasses.size() + entries.size();
            long completed = 0;
            for (VersionedClassEntry selected : orderedClasses) {
                cancellation.throwIfCancelled();
                byte[] bytes = readBytes(zip, selected.entry(), MAXIMUM_CLASS_BYTES, expanded);
                try {
                    ClassModel model = classReader.read(bytes, selected.entry().getName(), selected.release());
                    ClassModel duplicate = classes.put(model.internalName(), model);
                    if (duplicate != null) {
                        throw new IOException("Multiple effective classes use the same internal name: " + model.internalName());
                    }
                } catch (RuntimeException exception) {
                    throw new IOException("Invalid class entry: " + selected.entry().getName(), exception);
                }
                progress.onProgress("Reading classes", ++completed, totalWork);
            }

            for (ZipEntry entry : entries) {
                cancellation.throwIfCancelled();
                if (entry.isDirectory() || isClassEntry(entry.getName())) {
                    continue;
                }
                ResourceDigest digest = digestEntry(zip, entry, expanded);
                resources.put(entry.getName(), new ResourceModel(
                        entry.getName(), digest.size(), digest.sha256(), isLikelyText(entry.getName(), digest.sample())));
                progress.onProgress("Reading resources", ++completed, totalWork);
            }

            return new ArtifactSnapshot(
                    normalized,
                    artifactHash,
                    attributes.size(),
                    attributes.lastModifiedTime().toMillis(),
                    entries.size(),
                    options.targetRelease(),
                    manifest,
                    classes,
                    resources);
        } catch (ZipException exception) {
            throw new IOException("Invalid or unsupported Jar archive: " + normalized, exception);
        }
    }

    private static List<ZipEntry> enumerateAndValidate(ZipFile zip, ScanOptions options) throws IOException {
        List<ZipEntry> entries = new ArrayList<>();
        Set<String> names = new HashSet<>();
        long declaredExpanded = 0;
        Enumeration<? extends ZipEntry> enumeration = zip.entries();
        while (enumeration.hasMoreElements()) {
            ZipEntry entry = enumeration.nextElement();
            if (!names.add(entry.getName())) {
                throw new IOException("Jar contains a duplicate entry: " + entry.getName());
            }
            entries.add(entry);
            if (entries.size() > options.maximumEntries()) {
                throw new IOException("Jar exceeds the configured 100,000 entry safety limit");
            }
            if (entry.getSize() > 0) {
                if (entry.getSize() > options.maximumExpandedBytes() - declaredExpanded) {
                    throw new IOException("Jar exceeds the configured 8 GiB expanded-size safety limit");
                }
                declaredExpanded += entry.getSize();
            }
        }
        return entries;
    }

    private static Map<String, String> readManifest(ZipFile zip) throws IOException {
        ZipEntry entry = zip.getEntry("META-INF/MANIFEST.MF");
        if (entry == null) {
            return Map.of();
        }
        byte[] bytes = readLimited(zip.getInputStream(entry), MAXIMUM_MANIFEST_BYTES);
        Manifest parsed = new Manifest(new java.io.ByteArrayInputStream(bytes));
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> attribute : parsed.getMainAttributes().entrySet()) {
            values.put(attribute.getKey().toString(), attribute.getValue().toString());
        }
        return values;
    }

    private static Map<String, VersionedClassEntry> selectClasses(
            List<ZipEntry> entries, int targetRelease, boolean multiRelease) {
        Map<String, VersionedClassEntry> selected = new HashMap<>();
        for (ZipEntry entry : entries) {
            if (entry.isDirectory() || !isClassEntry(entry.getName())) {
                continue;
            }
            ParsedClassPath parsed = parseClassPath(entry.getName());
            if (parsed.release() > 0 && (!multiRelease || parsed.release() > targetRelease)) {
                continue;
            }
            VersionedClassEntry existing = selected.get(parsed.logicalName());
            if (existing == null || parsed.release() > existing.release()) {
                selected.put(parsed.logicalName(), new VersionedClassEntry(parsed.logicalName(), entry, parsed.release()));
            }
        }
        return selected;
    }

    private static ParsedClassPath parseClassPath(String name) {
        String prefix = "META-INF/versions/";
        if (!name.startsWith(prefix)) {
            return new ParsedClassPath(name, 0);
        }
        int slash = name.indexOf('/', prefix.length());
        if (slash < 0) {
            return new ParsedClassPath(name, 0);
        }
        try {
            int release = Integer.parseInt(name.substring(prefix.length(), slash));
            return release >= 9
                    ? new ParsedClassPath(name.substring(slash + 1), release)
                    : new ParsedClassPath(name, 0);
        } catch (NumberFormatException ignored) {
            return new ParsedClassPath(name, 0);
        }
    }

    private static byte[] readBytes(ZipFile zip, ZipEntry entry, long maximum, ExpandedBytes expanded) throws IOException {
        try (InputStream input = zip.getInputStream(entry); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[32 * 1024];
            long count = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                count += read;
                if (count > maximum) {
                    throw new IOException("Entry exceeds safety limit: " + entry.getName());
                }
                expanded.add(read);
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static ResourceDigest digestEntry(ZipFile zip, ZipEntry entry, ExpandedBytes expanded) throws IOException {
        MessageDigest digest = Hashing.sha256Digest();
        ByteArrayOutputStream sample = new ByteArrayOutputStream(4096);
        long size = 0;
        try (InputStream input = zip.getInputStream(entry)) {
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                size += read;
                expanded.add(read);
                digest.update(buffer, 0, read);
                if (sample.size() < 4096) {
                    sample.write(buffer, 0, Math.min(read, 4096 - sample.size()));
                }
            }
        }
        return new ResourceDigest(size, Hashing.hex(digest.digest()), sample.toByteArray());
    }

    private static byte[] readLimited(InputStream unclosed, long maximum) throws IOException {
        try (InputStream input = unclosed; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long count = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                count += read;
                if (count > maximum) {
                    throw new IOException("Manifest exceeds safety limit");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static boolean isLikelyText(String path, byte[] sample) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".zip") || lower.endsWith(".jar")
                || lower.endsWith(".class") || lower.endsWith(".ico") || lower.endsWith(".pdf")) {
            return false;
        }
        int suspicious = 0;
        for (byte value : sample) {
            int unsigned = value & 0xff;
            if (unsigned == 0) {
                return false;
            }
            if (unsigned < 0x09 || unsigned > 0x0d && unsigned < 0x20) {
                suspicious++;
            }
        }
        return sample.length == 0 || suspicious * 20 < sample.length;
    }

    private static boolean isClassEntry(String name) {
        return name.endsWith(".class");
    }

    private record ParsedClassPath(String logicalName, int release) { }

    private record VersionedClassEntry(String logicalName, ZipEntry entry, int release) { }

    private record ResourceDigest(long size, String sha256, byte[] sample) { }

    private static final class ExpandedBytes {
        private final long maximum;
        private long count;

        ExpandedBytes(long maximum) {
            this.maximum = maximum;
        }

        void add(long bytes) throws IOException {
            count += bytes;
            if (count > maximum) {
                throw new IOException("Jar exceeds the configured 8 GiB expanded-read safety limit");
            }
        }
    }
}
