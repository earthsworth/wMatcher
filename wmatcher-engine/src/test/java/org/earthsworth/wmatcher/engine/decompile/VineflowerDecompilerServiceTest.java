package org.earthsworth.wmatcher.engine.decompile;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.earthsworth.wmatcher.core.model.DecompileRequest;
import org.earthsworth.wmatcher.core.model.ScanOptions;
import org.earthsworth.wmatcher.core.task.CancellationToken;
import org.earthsworth.wmatcher.core.task.ProgressListener;
import org.earthsworth.wmatcher.engine.jar.JarArtifactLoader;
import org.earthsworth.wmatcher.engine.support.TestArtifacts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VineflowerDecompilerServiceTest {
    @TempDir Path temporaryDirectory;

    @Test
    void decompilesSelectedClassInMemoryAndCachesResult() throws Exception {
        Path jar = TestArtifacts.writeJar(temporaryDirectory.resolve("source.jar"),
                Map.of("sample/Example.class", TestArtifacts.simpleClass("sample/Example", "answer", 42, 1)), false);
        var artifact = new JarArtifactLoader().load(jar, ScanOptions.productionDefaults(),
                ProgressListener.NONE, CancellationToken.NONE);
        VineflowerDecompilerService service = new VineflowerDecompilerService(temporaryDirectory.resolve("cache"));
        DecompileRequest request = new DecompileRequest(artifact, "sample/Example", Map.of(), false, List.of());

        var first = service.decompile(request, CancellationToken.NONE);
        var second = service.decompile(request, CancellationToken.NONE);

        assertThat(first.source()).contains("class Example").contains("answer()").contains("42");
        assertThat(first.fromCache()).isFalse();
        assertThat(second.fromCache()).isTrue();
        assertThat(second.source()).isEqualTo(first.source());
    }
}
