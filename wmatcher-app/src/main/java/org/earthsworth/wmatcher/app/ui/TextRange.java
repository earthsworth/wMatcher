package org.earthsworth.wmatcher.app.ui;

record TextRange(int start, int end) {
    TextRange {
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("Invalid text range");
        }
    }
}
