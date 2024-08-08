package org.dreamwork.tools.tts;

import io.ikfly.constant.OutputFormat;
import io.ikfly.constant.TtsStyleEnum;
import io.ikfly.constant.VoiceEnum;
import io.ikfly.model.SSML;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

public class TTSConfig {
    private final Logger logger = LoggerFactory.getLogger (TTSConfig.class);

    final SSML.SSMLBuilder builder = SSML.builder ()
            .voice (VoiceEnum.zh_CN_XiaoyiNeural)
            .outputFormat (OutputFormat.audio_24khz_48kbitrate_mono_mp3);

    volatile long timeout = 30_000; // 30 seconds
    volatile boolean oneShot = false;

    public TTSConfig voice (VoiceRole role) {
        VoiceEnum voice = findVoice (role);
        if (voice != null) {
            builder.voice (voice);
        } else {
            logger.warn ("unknown voice role: " + role + ", use default: Xiaoyi");
        }
        return this;
    }

    public TTSConfig format (VoiceFormat format) {
        OutputFormat fmt = findFormat (format);
        if (fmt != null) {
            builder.outputFormat (fmt);
        } else {
            logger.warn ("unknown format: " + format + ", using default: audio_24khz_48k_bit_rate_mono_mp3");
        }
        return this;
    }

    public TTSConfig timeout (int amount, TimeUnit unit) {
        if (amount <= 0) {
            logger.warn ("wrong time amount: " + amount + " of " + unit + ", use the default value: 30s.");
            timeout = 30_000L;
        } else {
            timeout = unit.toMillis (amount);
        }
        return this;
    }

    public TTSConfig oneShot () {
        this.oneShot = true;
        return this;
    }

    SSML synthesis (String text) {
        return builder.synthesisText (text).build ();
    }

    private VoiceEnum findVoice (VoiceRole role) {
        if (role == null) {
            return null;
        }
        VoiceEnum[] values = VoiceEnum.values ();
        for (VoiceEnum value : values) {
            if (role.shortName.equals (value.getShortName ())) {
                return value;
            }
        }
        return null;
    }

    private OutputFormat findFormat (VoiceFormat format) {
        if (format == null) {
            return null;
        }
        OutputFormat[] values = OutputFormat.values ();
        for (OutputFormat value : values) {
            if (format.value.equals (value.getValue ())) {
                return value;
            }
        }
        return null;
    }

    private TtsStyleEnum findFormat (VoiceStyle style) {
        if (style == null) {
            return null;
        }
        TtsStyleEnum[] values = TtsStyleEnum.values ();
        for (TtsStyleEnum value : values) {
            if (style.value.equals (value.getValue ())) {
                return value;
            }
        }
        return null;
    }
}