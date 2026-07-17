package org.earthsworth.wmatcher.core.model;

import java.util.Arrays;

public record ResourceContent(byte[] bytes, boolean truncated, boolean text) {
    public ResourceContent {
        bytes = Arrays.copyOf(bytes, bytes.length);
    }

    @Override
    public byte[] bytes() {
        return Arrays.copyOf(bytes, bytes.length);
    }
}
