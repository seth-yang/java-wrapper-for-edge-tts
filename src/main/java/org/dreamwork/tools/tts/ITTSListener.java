package org.dreamwork.tools.tts;

import java.nio.file.Path;

public interface ITTSListener {
    default void started (String text) {}
    default void finished (String text) {}
    default void idle () {}
    default void voiceSaved (String text, Path path) {}
}