package org.earthsworth.wmatcher.engine.jar;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.charset.StandardCharsets;
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
        if (Files.isDirectory(normalized)) {
            return loadDirectory(normalized, options, progress, cancellation);
        }
        if (!Files.isRegularFile(normalized)) {
            throw new IOException("Artifact does not exist or is not a regular file or directory: " + normalized);
        }
        BasicFileAttributes attributes = Files.readAttributes(normalized, BasicFileAttributes.class);
        if (attributes.size() > options.maximumFileSize()) {
            throw new IOException("Jar exceeds the configured 1 GiB safety limit");
        }

        cancellation.throwIfCancelled();
        progress.onProgress("Hashing archive", 0, attributes.size());
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
            throw new IOException("Invalid or unsupported archive: " + normalized, exception);
        }
    }

    private ArtifactSnapshot loadDirectory(
            Path root,
            ScanOptions options,
            ProgressListener progress,
            CancellationToken cancellation) throws IOException {
        BasicFileAttributes rootAttributes = Files.readAttributes(root, BasicFileAttributes.class);
        List<DirectoryEntry> entries = enumerateDirectory(root, options);
        long totalSize = entries.stream().filter(entry -> !entry.directory()).mapToLong(DirectoryEntry::size).sum();
        progress.onProgress("Hashing directory", 0, totalSize);
        String artifactHash = hashDirectory(entries, progress, cancellation, totalSize);

        Map<String, String> manifest = readDirectoryManifest(root);
        boolean multiRelease = Boolean.parseBoolean(manifest.getOrDefault("Multi-Release", "false"));
        Map<String, DirectoryVersionedClass> selectedClasses = selectDirectoryClasses(
                entries, options.targetRelease(), multiRelease);
        Map<String, ClassModel> classes = new LinkedHashMap<>();
        Map<String, ResourceModel> resources = new LinkedHashMap<>();
        ExpandedBytes expanded = new ExpandedBytes(options.maximumExpandedBytes());
        long totalWork = selectedClasses.size() + entries.size();
        long completed = 0;

        for (DirectoryVersionedClass selected : selectedClasses.values().stream()
                .sorted(Comparator.comparing(DirectoryVersionedClass::logicalName)).toList()) {
            cancellation.throwIfCancelled();
            byte[] bytes = readDirectoryBytes(selected.entry(), MAXIMUM_CLASS_BYTES, expanded);
            try {
                ClassModel model = classReader.read(bytes, selected.entry().name(), selected.release());
                ClassModel duplicate = classes.put(model.internalName(), model);
                if (duplicate != null) {
                    throw new IOException("Multiple effective classes use the same internal name: "
                            + model.internalName());
                }
            } catch (RuntimeException exception) {
                throw new IOException("Invalid class entry: " + selected.entry().name(), exception);
            }
            progress.onProgress("Reading classes", ++completed, totalWork);
        }

        for (DirectoryEntry entry : entries) {
            cancellation.throwIfCancelled();
            if (entry.directory() || isClassEntry(entry.name())) {
                continue;
            }
            ResourceDigest digest = digestDirectoryEntry(entry, expanded);
            resources.put(entry.name(), new ResourceModel(
                    entry.name(), digest.size(), digest.sha256(), isLikelyText(entry.name(), digest.sample())));
            progress.onProgress("Reading resources", ++completed, totalWork);
        }

        return new ArtifactSnapshot(
                root,
                artifactHash,
                totalSize,
                rootAttributes.lastModifiedTime().toMillis(),
                entries.size(),
                options.targetRelease(),
                manifest,
                classes,
                resources);
    }

    private static List<DirectoryEntry> enumerateDirectory(Path root, ScanOptions options) throws IOException {
        List<DirectoryEntry> entries = new ArrayList<>();
        long declaredBytes = 0;
        try (var stream = Files.walk(root)) {
            List<Path> paths = stream.filter(path -> !path.equals(root))
                    .sorted(Comparator.comparing(path -> entryName(root, path)))
                    .toList();
            for (Path path : paths) {
                if (Files.isSymbolicLink(path)) {
                    throw new IOException("Artifact directory contains a symbolic link: " + path);
                }
                boolean directory = Files.isDirectory(path);
                if (!directory && !Files.isRegularFile(path)) {
                    throw new IOException("Artifact directory contains an unsupported entry: " + path);
                }
                long size = directory ? 0 : Files.size(path);
                if (size > options.maximumFileSize()) {
                    throw new IOException("Artifact entry exceeds the configured 1 GiB safety limit: " + path);
                }
                if (size > options.maximumExpandedBytes() - declaredBytes) {
                    throw new IOException("Artifact directory exceeds the configured 8 GiB size limit");
                }
                declaredBytes += size;
                entries.add(new DirectoryEntry(entryName(root, path), path, size, directory));
                if (entries.size() > options.maximumEntries()) {
                    throw new IOException("Artifact directory exceeds the configured 100,000 entry safety limit");
                }
            }
        }
        return entries;
    }

    private static String entryName(Path root, Path path) {
        return root.relativize(path).toString().replace(java.io.File.separatorChar, '/');
    }

    private static String hashDirectory(
            List<DirectoryEntry> entries,
            ProgressListener progress,
            CancellationToken cancellation,
            long totalSize) throws IOException {
        MessageDigest digest = Hashing.sha256Digest();
        long completed = 0;
        byte[] buffer = new byte[64 * 1024];
        for (DirectoryEntry entry : entries) {
            cancellation.throwIfCancelled();
            if (entry.directory()) {
                continue;
            }
            digest.update(entry.name().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            try (InputStream input = Files.newInputStream(entry.path())) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                    completed += read;
                    progress.onProgress("Hashing directory", completed, totalSize);
                    cancellation.throwIfCancelled();
                }
            }
        }
        return Hashing.hex(digest.digest());
    }

    private static Map<String, String> readDirectoryManifest(Path root) throws IOException {
        Path manifestPath = root.resolve("META-INF").resolve("MANIFEST.MF");
        if (!Files.isRegularFile(manifestPath)) {
            return Map.of();
        }
        byte[] bytes;
        try (InputStream input = Files.newInputStream(manifestPath)) {
            bytes = readLimited(input, MAXIMUM_MANIFEST_BYTES);
        }
        Manifest parsed = new Manifest(new java.io.ByteArrayInputStream(bytes));
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> attribute : parsed.getMainAttributes().entrySet()) {
            values.put(attribute.getKey().toString(), attribute.getValue().toString());
        }
        return values;
    }

    private static Map<String, DirectoryVersionedClass> selectDirectoryClasses(
            List<DirectoryEntry> entries, int targetRelease, boolean multiRelease) {
        Map<String, DirectoryVersionedClass> selected = new HashMap<>();
        for (DirectoryEntry entry : entries) {
            if (entry.directory() || !isClassEntry(entry.name())) {
                continue;
            }
            ParsedClassPath parsed = parseClassPath(entry.name());
            if (parsed.release() > 0 && (!multiRelease || parsed.release() > targetRelease)) {
                continue;
            }
            DirectoryVersionedClass existing = selected.get(parsed.logicalName());
            if (existing == null || parsed.release() > existing.release()) {
                selected.put(parsed.logicalName(), new DirectoryVersionedClass(
                        parsed.logicalName(), entry, parsed.release()));
            }
        }
        return selected;
    }

    private static byte[] readDirectoryBytes(
            DirectoryEntry entry, long maximum, ExpandedBytes expanded) throws IOException {
        try (InputStream input = Files.newInputStream(entry.path());
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[32 * 1024];
            long count = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                count += read;
                if (count > maximum) {
                    throw new IOException("Entry exceeds safety limit: " + entry.name());
                }
                expanded.add(read);
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static ResourceDigest digestDirectoryEntry(DirectoryEntry entry, ExpandedBytes expanded)
            throws IOException {
        MessageDigest digest = Hashing.sha256Digest();
        ByteArrayOutputStream sample = new ByteArrayOutputStream(4096);
        long size = 0;
        try (InputStream input = Files.newInputStream(entry.path())) {
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

    private record DirectoryEntry(String name, Path path, long size, boolean directory) { }

    private record DirectoryVersionedClass(String logicalName, DirectoryEntry entry, int release) { }

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
