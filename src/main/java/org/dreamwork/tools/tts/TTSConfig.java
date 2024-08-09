package org.dreamwork.tools.tts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.dreamwork.tools.tts.VoiceFormat.audio_24khz_48kbitrate_mono_mp3;
import static org.dreamwork.tools.tts.VoiceRole.Xiaoyi;

/**
 * 调用 edge-tts 的配置信息
 */
public class TTSConfig {
    private final Logger logger = LoggerFactory.getLogger (TTSConfig.class);
    /** 调用 edge-tts 的模拟 User-Agent */
    final String UA;
    /** edge-tts 调用的 url */
    final String WS_URL;
    /** edge-tts 调用的 token */
    final String TOKEN;
    /** edge-tts 调用的 origin */
    final String ORIGIN;

    /** 最终发送到 edge-tts 的内容 */
    final SSMLPayload payload = new SSMLPayload (Xiaoyi);

    /**
     * 转换任务的空闲时间，当两次任务的之间的间隔时间超出这个时间后，{@link TTS} 将进入 {@code Idle} 状态，
     * 断开 websocket 连接, 并且将触发 {@link ITTSListener#idle()} 事件.
     * <p><i>这个Idle 状态并不影响后续的转换任务</i></p>
     */
    volatile long timeout = 30_000L; // 30s

    /** 指示 {@link TTS} 在进入 {@code Idle} 状态后是否立即释放资源。 */
    volatile boolean oneShot = false;

    /** 音频输出格式 */
    volatile VoiceFormat format = audio_24khz_48kbitrate_mono_mp3;

    volatile String dir;

    /**
     * 运行模式.
     * <p>允许的运行模式有：</p>
     * <ul>
     *     <li>0x01 - 实时模式 (默认激活)</li>
     *     <li>0x02 - 保存文件</li>
     * </ul>
     */
    int mode = 1;

    public static final int MODE_REALTIME = 0x01;
    public static final int MODE_SAVE = 0x02;

    TTSConfig () {
        ClassLoader loader = getClass ().getClassLoader ();
        try (InputStream in = loader.getResourceAsStream ("edge-tts.properties")) {
            if (in != null) {
                Properties props = new Properties ();
                props.load (in);

                UA     = props.getProperty ("edge.tts.user-agent");
                WS_URL = props.getProperty ("edge.tts.url");
                TOKEN  = props.getProperty ("edge.tts.token");
                ORIGIN = props.getProperty ("edge.tts.origin");
            } else {
                throw new RuntimeException ("cannot load static config");
            }
        } catch (IOException ex) {
            throw new RuntimeException (ex);
        }
    }

    /**
     * 用传入的文本拼装即将提交给 edge-tts 的 payload
     * @param text 需要转换的文本
     * @return edge-tts 的 SSML 格式
     */
    SSMLPayload synthesis (String text) {
        payload.content = text;
        return payload;
    }

    /**
     * 设置音频输出的语音角色.
     * @param role 指定的语音角色
     * @return TTSConfig 实例本身
     * @see VoiceRole
     */
    public TTSConfig voice (VoiceRole role) {
        payload.role = role;
        return this;
    }

    /**
     * 设置音频输出格式.
     * <p><strong>仅在首次调用转换任务前调用有效</strong></p>
     * @param format 指定的音频格式
     * @return TTSConfig 实例本身
     */
    public TTSConfig format (VoiceFormat format) {
        this.format = format;
        return this;
    }

    /**
     * 设置进入 Idle 状态的超时时间
     * @param amount 时间
     * @param unit   时间单位
     * @return TTSConfig 实例本身
     */
    public TTSConfig timeout (int amount, TimeUnit unit) {
        if (amount < 0) {
            logger.warn ("wrong time amount: " + amount + " of " + unit + ", use the default value: 30s.");
            timeout = 500L;
        } else {
            timeout = unit.toMillis (amount);
        }
        return this;
    }

    /**
     * 设置 {@link TTS} 为 OneShot 模式。在改模式下，仅执行一次转换任务后就销魂实例，该实例不可再用
     * @return TTSConfig 实例本身
     */
    public TTSConfig oneShot () {
        oneShot = true;
        return this;
    }

    /**
     * 激活文件保存模式
     * @return TTSConfig 实例本身
     */
    public TTSConfig enableSaveMode () {
        mode |= MODE_SAVE;
        return this;
    }

    /**
     * 取消文件保存模式
     * @return TTSConfig 实例本身
     */
    public TTSConfig disableSaveMode () {
        mode &= ~MODE_SAVE;
        return this;
    }

    /**
     * 激活实时模式 (默认行为)
     * @return TTSConfig 实例本身
     */
    public TTSConfig enableRealtimeMode () {
        mode |= MODE_REALTIME;
        return this;
    }

    /**
     * 取消实施模式
     * @return TTSConfig 实例本身
     */
    public TTSConfig disableRealtimeMode () {
        mode &= ~MODE_REALTIME;
        return this;
    }

    /**
     * 设置保存语音转换结果文件的输出目录.
     * <p>如果文件保存模式未被激活，这个设置不会产生任何结果</p>
     * 若指定的 {@code dir} 目录不存，将会尝试创建这个目录。
     * <p><strong>仅在首次调用转换任务前调用有效</strong></p>
     * 您可以使用 '{@code ~}' 来代表操作系统的用户目录
     * @param dir 输出目录
     * @return TTSConfig 实例本身
     */
    public TTSConfig outputDir (String dir) {
        if (dir.startsWith ("~")) {
            String home = System.getProperty ("user.home");
            dir = home + dir.substring (1);
        }
        Path path = Paths.get (dir);
        if (Files.notExists (path)) {
            try {
                Files.createDirectories (path);
            } catch (IOException ex) {
                throw new RuntimeException (ex);
            }
        }
        if (!Files.isWritable (path)) {
            throw new RuntimeException ("dir " + dir + " cannot be written.");
        }
        this.dir = dir;
        return this;
    }
}