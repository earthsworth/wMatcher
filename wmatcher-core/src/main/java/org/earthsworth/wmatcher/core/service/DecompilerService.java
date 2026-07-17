package org.earthsworth.wmatcher.core.service;

import java.io.IOException;
import org.earthsworth.wmatcher.core.model.DecompileRequest;
import org.earthsworth.wmatcher.core.model.DecompiledSource;
import org.earthsworth.wmatcher.core.task.CancellationToken;

public interface DecompilerService {
    DecompiledSource decompile(DecompileRequest request, CancellationToken cancellation) throws IOException;
}
