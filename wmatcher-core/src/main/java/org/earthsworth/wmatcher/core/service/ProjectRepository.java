package org.earthsworth.wmatcher.core.service;

import java.io.IOException;
import java.nio.file.Path;
import org.earthsworth.wmatcher.core.project.WMatcherProject;

public interface ProjectRepository {
    WMatcherProject load(Path path) throws IOException;

    void save(Path path, WMatcherProject project) throws IOException;
}
