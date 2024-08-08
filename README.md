# java-wrapper-for-edge-tts
A java wrapper for MS edge-tts

## Quick Start
### Dependency
```xml
<dependency>
    <groupId>io.github.seth-yang</groupId>
    <artifactId>java-wrapper-for-edge-tts</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Minimal example

```java
import org.dreamwork.tools.tts.TTS;

public class TtsMinimalExample {
    public static void main (String[] args) {
        TTS tts = new TTS ();
        tts.synthesis ("这是一段测试文本");
    }
}
```

### Continued synthesis example
```java
import org.dreamwork.tools.tts.ITTSListener;
import org.dreamwork.tools.tts.TTS;
import org.dreamwork.tools.tts.VoiceRole;

import java.util.concurrent.TimeUnit;

public class ContinuedSynthesisExample {
    public static void main (String[] args) throws Exception {
        final TTS tts = new TTS ();
        tts.getConfig ()
                .timeout (3, TimeUnit.SECONDS)  // enter idle after this amount of time unit
                .voice (VoiceRole.Xiaoxuan);    // voice role，一个萌妹子
        tts.setListener (new ITTSListener () {
            @Override
            public void idle () {
                tts.dispose ();
                System.exit (0);
            }
        });

        String[] messages = {
                "TTS是Text To Speech的缩写，即“从文本到语音”，是人机对话的一部分，让机器能够说话",
                "它是同时运用语言学和心理学的杰出之作，在内置芯片的支持之下，通过神经网络的设计，把文字智能地转化为自然语音流。",
                "TTS技术对文本文件进行实时转换，转换时间之短可以秒计算。在其特有智能语音控制器作用下，文本输出的语音音律流畅，" +
                "使得听者在听取信息时感觉自然，毫无机器语音输出的冷漠与生涩感。"
        };
        for (String text : messages) {
            tts.synthesis (text);
        }
    }
}
```

## Thanks
- [https://github.com/ikfly/java-tts.git](https://github.com/ikfly/java-tts.git)
- [https://github.com/ag2s20150909/TTS](https://github.com/ag2s20150909/TTS)
- [https://github.com/rany2/edge-tts](https://github.com/rany2/edge-tts)
- [https://github.com/Migushthe2nd/MsEdgeTTS](https://github.com/Migushthe2nd/MsEdgeTTS)
- [https://learn.microsoft.com/zh-cn/azure/cognitive-services/speech-service/index-text-to-speech](https://learn.microsoft.com/zh-cn/azure/cognitive-services/speech-service/index-text-to-speech)