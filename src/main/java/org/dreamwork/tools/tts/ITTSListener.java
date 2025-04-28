package org.dreamwork.tools.tts;

import java.nio.file.Path;

public interface ITTSListener {
    /**
     * 当开始合成一段文本时触发
     * @param target 即将被合成的文本，或一个mp3文件，或者一段mp3输入流
     */
    default void started (Object target) {}

    /**
     * 当一段文本合成成功后触发
     * @param target 被成功合成的文本，或一个mp3文件，或者一段mp3输入流
     */
    default void finished (Object target) {}

    /**
     * 进入空闲模式时触发
     */
    default void idle () {}

    /**
     * 但一段合成的语音文件被存盘后触发
     * @param target 原始文本，或一个mp3文件，或者一段mp3输入流
     * @param path 合成后的语音文件。 mp3 格式
     */
    default void voiceSaved (Object target, Path path) {}

    default void handleException (Object target, Exception ex) {}
}