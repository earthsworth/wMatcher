package org.earthsworth.wmatcher.app.ui;

@FunctionalInterface
interface TextLocator {
    TextRange locate(String text);
}
