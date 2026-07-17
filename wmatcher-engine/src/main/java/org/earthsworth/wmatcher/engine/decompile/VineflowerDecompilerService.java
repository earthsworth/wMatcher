package org.earthsworth.wmatcher.engine.decompile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.earthsworth.wmatcher.core.model.ArtifactSnapshot;
import org.earthsworth.wmatcher.core.model.ClassModel;
import org.earthsworth.wmatcher.core.model.DecompileRequest;
import org.earthsworth.wmatcher.core.model.DecompiledSource;
import org.earthsworth.wmatcher.core.model.EntityId;
import org.earthsworth.wmatcher.core.model.EntityKind;
import org.earthsworth.wmatcher.core.service.DecompilerService;
import org.earthsworth.wmatcher.core.task.CancellationToken;
import org.earthsworth.wmatcher.engine.util.Hashing;
import org.jetbrains.java.decompiler.main.decompiler.BaseDecompiler;
import org.jetbrains.java.decompiler.main.extern.IContextSource;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.jetbrains.java.decompiler.main.extern.IResultSaver;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class VineflowerDecompilerService implements DecompilerService {
    private static final Logger LOGGER = LoggerFactory.getLogger(VineflowerDecompilerService.class);
    private static final long CACHE_LIMIT = 2L * 1024 * 1024 * 1024;
    private static final String DECOMPILER_VERSION = "vineflower-1.12.0";
    private final Path cacheDirectory;
    private final Semaphore permits = new Semaphore(2);

    public VineflowerDecompilerService() {
        this(defaultCacheDirectory());
    }

    public VineflowerDecompilerService(Path cacheDirectory) {
        this.cacheDirectory = cacheDirectory.toAbsolutePath().normalize();
    }

    @Override
    public DecompiledSource decompile(DecompileRequest request, CancellationToken cancellation) throws IOException {
        cancellation.throwIfCancelled();
        String cacheKey = cacheKey(request);
        Path cacheFile = cacheDirectory.resolve(cacheKey + ".java");
        if (Files.isRegularFile(cacheFile)) {
            Files.setLastModifiedTime(cacheFile, FileTime.fromMillis(System.currentTimeMillis()));
            return new DecompiledSource(request.className(), Files.readString(cacheFile, StandardCharsets.UTF_8), true);
        }

        boolean acquired = false;
        try {
            permits.acquire();
            acquired = true;
            cancellation.throwIfCancelled();
            Decompiled decompiled = decompileUncached(request);
            cancellation.throwIfCancelled();
            writeCache(cacheFile, decompiled.source());
            pruneCache();
            return new DecompiledSource(decompiled.className(), decompiled.source(), false);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the decompiler", exception);
        } finally {
            if (acquired) {
                permits.release();
            }
        }
    }

    private Decompiled decompileUncached(DecompileRequest request) throws IOException {
        ArtifactSnapshot artifact = request.artifact();
        ClassModel selected = artifact.classes().get(request.className());
        if (selected == null) {
            throw new IOException("Class not found: " + request.className());
        }
        String topLevel = topLevelName(selected.internalName());
        CanonicalRemapper remapper = request.remapToTarget()
                ? CanonicalRemapper.toLeftNamespace(request.canonicalMappings())
                : CanonicalRemapper.identity();
        Map<String, byte[]> sourceClasses = new LinkedHashMap<>();
        for (ClassModel model : artifact.classes().values()) {
            if (model.internalName().equals(topLevel) || model.internalName().startsWith(topLevel + '$')) {
                byte[] transformed = remap(readClassBytes(artifact, model), remapper);
                sourceClasses.put(new ClassReader(transformed).getClassName(), transformed);
            }
        }
        if (sourceClasses.isEmpty()) {
            throw new IOException("No source classes found for " + request.className());
        }

        CapturingSource source = new CapturingSource("selected:" + selected.internalName(), sourceClasses);
        Map<String, Object> preferences = Map.of(
                IFernflowerPreferences.THREADS, "1",
                IFernflowerPreferences.INCLUDE_JAVA_RUNTIME, "1",
                IFernflowerPreferences.LOG_LEVEL, "WARN",
                IFernflowerPreferences.BYTECODE_SOURCE_MAPPING, "1",
                IFernflowerPreferences.DUMP_EXCEPTION_ON_ERROR, "1",
                IFernflowerPreferences.NEW_LINE_SEPARATOR, "1");
        BaseDecompiler decompiler = new BaseDecompiler(NoOpSaver.INSTANCE, preferences, new Slf4jDecompilerLogger());
        decompiler.addSource(source);
        decompiler.addLibrary(new LazyArtifactSource(artifact, remapper, sourceClasses.keySet()));
        for (Path library : request.libraries()) {
            if (Files.isRegularFile(library)) {
                decompiler.addLibrary(library.toFile());
            }
        }
        try {
            decompiler.decompileContext();
        } catch (RuntimeException exception) {
            throw new IOException("Vineflower could not decompile " + request.className(), exception);
        }
        Map.Entry<String, String> output = source.output().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(remapper.map(topLevel)))
                .findFirst()
                .or(() -> source.output().entrySet().stream().findFirst())
                .orElseThrow(() -> new IOException("Vineflower produced no source for " + request.className()));
        return new Decompiled(output.getKey(), output.getValue());
    }

    private static byte[] remap(byte[] bytes, Remapper remapper) {
        if (remapper == CanonicalRemapper.IDENTITY) {
            return bytes;
        }
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(0);
        reader.accept(new ClassRemapper(writer, remapper), 0);
        return writer.toByteArray();
    }

    private static byte[] readClassBytes(ArtifactSnapshot artifact, ClassModel model) throws IOException {
        try (ZipFile zip = new ZipFile(artifact.path().toFile())) {
            ZipEntry entry = zip.getEntry(model.entryName());
            if (entry == null) {
                throw new IOException("Class entry is missing: " + model.entryName());
            }
            try (InputStream input = zip.getInputStream(entry); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (output.size() + read > 64 * 1024 * 1024) {
                        throw new IOException("Class exceeds decompilation safety limit: " + model.entryName());
                    }
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            }
        }
    }

    private static String topLevelName(String internalName) {
        int inner = internalName.indexOf('$');
        return inner < 0 ? internalName : internalName.substring(0, inner);
    }

    private static String cacheKey(DecompileRequest request) {
        String mappings = request.canonicalMappings().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(EntityId::externalName)))
                .map(entry -> entry.getKey().externalName() + "=" + entry.getValue().externalName())
                .collect(Collectors.joining("\n"));
        String libraries = request.libraries().stream().map(Path::toAbsolutePath).map(Path::toString).sorted()
                .collect(Collectors.joining("\n"));
        return Hashing.sha256(DECOMPILER_VERSION + '\n' + request.artifact().sha256() + '\n' + request.className()
                + '\n' + request.remapToTarget() + '\n' + mappings + '\n' + libraries);
    }

    private void writeCache(Path destination, String source) throws IOException {
        Files.createDirectories(cacheDirectory);
        Path temporary = Files.createTempFile(cacheDirectory, "source-", ".tmp");
        try {
            Files.writeString(temporary, source, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void pruneCache() {
        try {
            if (!Files.isDirectory(cacheDirectory)) {
                return;
            }
            List<Path> files;
            try (var stream = Files.list(cacheDirectory)) {
                files = stream.filter(Files::isRegularFile)
                        .sorted(Comparator.comparingLong(VineflowerDecompilerService::lastModified))
                        .toList();
            }
            long total = 0;
            for (Path file : files) {
                total += Files.size(file);
            }
            for (Path file : files) {
                if (total <= CACHE_LIMIT) {
                    break;
                }
                long size = Files.size(file);
                Files.deleteIfExists(file);
                total -= size;
            }
        } catch (IOException exception) {
            LOGGER.debug("Unable to prune Vineflower cache", exception);
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            return Long.MIN_VALUE;
        }
    }

    private static Path defaultCacheDirectory() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            return Path.of(localAppData, "wMatcher", "cache", "sources");
        }
        return Path.of(System.getProperty("user.home"), ".cache", "wmatcher", "sources");
    }

    private record Decompiled(String className, String source) { }

    private static final class CapturingSource implements IContextSource {
        private final String name;
        private final Map<String, byte[]> classes;
        private final Map<String, String> output = new LinkedHashMap<>();

        CapturingSource(String name, Map<String, byte[]> classes) {
            this.name = name;
            this.classes = Map.copyOf(classes);
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public Entries getEntries() {
            List<Entry> entries = classes.keySet().stream().sorted().map(Entry::atBase).toList();
            return new Entries(entries, List.of(), List.of());
        }

        @Override
        public InputStream getInputStream(String resource) {
            String className = resource.endsWith(CLASS_SUFFIX)
                    ? resource.substring(0, resource.length() - CLASS_SUFFIX.length()) : resource;
            byte[] bytes = classes.get(className);
            return bytes == null ? null : new ByteArrayInputStream(bytes);
        }

        @Override
        public IOutputSink createOutputSink(IResultSaver saver) {
            return new IOutputSink() {
                @Override
                public void begin() { }

                @Override
                public void acceptClass(String qualifiedName, String fileName, String content, int[] mapping) {
                    output.put(qualifiedName, content);
                }

                @Override
                public void acceptDirectory(String directory) { }

                @Override
                public void acceptOther(String path) { }

                @Override
                public void close() { }
            };
        }

        Map<String, String> output() {
            return output;
        }
    }

    private static final class LazyArtifactSource implements IContextSource {
        private final ArtifactSnapshot artifact;
        private final CanonicalRemapper remapper;
        private final Set<String> excluded;
        private final Map<String, ClassModel> exposedClasses;

        LazyArtifactSource(ArtifactSnapshot artifact, CanonicalRemapper remapper, Set<String> excluded) {
            this.artifact = artifact;
            this.remapper = remapper;
            this.excluded = Set.copyOf(excluded);
            Map<String, ClassModel> index = new HashMap<>();
            artifact.classes().values().forEach(model -> index.put(remapper.map(model.internalName()), model));
            exposedClasses = Map.copyOf(index);
        }

        @Override
        public String getName() {
            return "library:" + artifact.path().getFileName();
        }

        @Override
        public Entries getEntries() {
            return Entries.EMPTY;
        }

        @Override
        public boolean isLazy() {
            return true;
        }

        @Override
        public boolean hasClass(String className) {
            return !excluded.contains(className) && exposedClasses.containsKey(className);
        }

        @Override
        public byte[] getClassBytes(String className) throws IOException {
            ClassModel model = exposedClasses.get(className);
            return model == null || excluded.contains(className) ? null : remap(readClassBytes(artifact, model), remapper);
        }

        @Override
        public InputStream getInputStream(String resource) throws IOException {
            if (!resource.endsWith(CLASS_SUFFIX)) {
                return null;
            }
            byte[] bytes = getClassBytes(resource.substring(0, resource.length() - CLASS_SUFFIX.length()));
            return bytes == null ? null : new ByteArrayInputStream(bytes);
        }
    }

    private static final class CanonicalRemapper extends Remapper {
        private static final CanonicalRemapper IDENTITY = new CanonicalRemapper(Map.of(), Map.of(), Map.of());
        private final Map<String, String> classes;
        private final Map<EntityId, String> fields;
        private final Map<EntityId, String> methods;

        CanonicalRemapper(Map<String, String> classes, Map<EntityId, String> fields, Map<EntityId, String> methods) {
            super(Opcodes.ASM9);
            this.classes = Map.copyOf(classes);
            this.fields = Map.copyOf(fields);
            this.methods = Map.copyOf(methods);
        }

        static CanonicalRemapper identity() {
            return IDENTITY;
        }

        static CanonicalRemapper toLeftNamespace(Map<EntityId, EntityId> leftToRight) {
            Map<String, String> classes = new HashMap<>();
            Map<EntityId, String> fields = new HashMap<>();
            Map<EntityId, String> methods = new HashMap<>();
            leftToRight.forEach((left, right) -> {
                if (left.kind() == EntityKind.CLASS) {
                    classes.put(right.name(), left.name());
                } else if (left.kind() == EntityKind.FIELD) {
                    fields.put(right, left.name());
                } else if (left.kind() == EntityKind.METHOD) {
                    methods.put(right, left.name());
                }
            });
            return new CanonicalRemapper(classes, fields, methods);
        }

        @Override
        public String map(String internalName) {
            return classes.getOrDefault(internalName, internalName);
        }

        @Override
        public String mapFieldName(String owner, String name, String descriptor) {
            return fields.getOrDefault(EntityId.fieldId(owner, name, descriptor), name);
        }

        @Override
        public String mapMethodName(String owner, String name, String descriptor) {
            return methods.getOrDefault(EntityId.methodId(owner, name, descriptor), name);
        }

        @Override
        public String mapRecordComponentName(String owner, String name, String descriptor) {
            return mapFieldName(owner, name, descriptor);
        }
    }

    private static final class Slf4jDecompilerLogger extends IFernflowerLogger {
        @Override
        public void writeMessage(String message, Severity severity) {
            switch (severity) {
                case TRACE -> LOGGER.trace(message);
                case INFO -> LOGGER.info(message);
                case WARN -> LOGGER.warn(message);
                case ERROR -> LOGGER.error(message);
            }
        }

        @Override
        public void writeMessage(String message, Severity severity, Throwable throwable) {
            switch (severity) {
                case TRACE -> LOGGER.trace(message, throwable);
                case INFO -> LOGGER.info(message, throwable);
                case WARN -> LOGGER.warn(message, throwable);
                case ERROR -> LOGGER.error(message, throwable);
            }
        }
    }

    private enum NoOpSaver implements IResultSaver {
        INSTANCE;

        @Override public void saveFolder(String path) { }
        @Override public void copyFile(String source, String path, String entryName) { }
        @Override public void saveClassFile(String path, String qualifiedName, String entryName, String content, int[] mapping) { }
        @Override public void createArchive(String path, String archiveName, Manifest manifest) { }
        @Override public void saveDirEntry(String path, String archiveName, String entryName) { }
        @Override public void copyEntry(String source, String path, String archiveName, String entry) { }
        @Override public void saveClassEntry(String path, String archiveName, String qualifiedName, String entryName, String content) { }
        @Override public void closeArchive(String path, String archiveName) { }
    }
}
