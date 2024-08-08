package org.dreamwork.tools.tts;

public interface ITTSListener {
    default void started (String text) {};
    default void finished (String text) {};
    default void idle () {};
}