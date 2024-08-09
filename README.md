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
## Examples

### OneShot example

- Process the text only once, then exit the application.
```java
import org.dreamwork.tools.tts.TTS;

import java.io.IOException;

public class OneShotExample {
    public static void main (String[] args) throws IOException {
        TTS tts = new TTS ();
        tts.config ().oneShot ();   // process the text then exit the application.
        tts.synthesis ("你好，TTS");
    }
}
```

- Process the text only once and save the resulting speech to a file, then exit the application.
```java
import org.dreamwork.tools.tts.ITTSListener;
import org.dreamwork.tools.tts.TTS;

import java.io.IOException;

public class OnsShotAndSaveFileExample {
    public static void main (String[] args) throws IOException {
        TTS tts = new TTS ();
        tts.setListener (new ITTSListener () {
            @Override
            public void voiceSaved (String text, Path path) {
                System.out.printf ("the result voice of text [%s] saved in %s%n", text, path);
            }
        });
        tts.config ().oneShot ()    // one shot
                .enableSaveMode ()  // enable save to file mode
                .outputDir ("~");   // the target dir which the files will be saved
        tts.synthesis ("你好，TTS");
    }
}
```

### Continued synthesis example
Continue processing all text and exit the application when entering the Idle state.
```java
import org.dreamwork.tools.tts.ITTSListener;
import org.dreamwork.tools.tts.TTS;
import org.dreamwork.tools.tts.VoiceRole;

import java.util.concurrent.TimeUnit;

public class ContinuedSynthesisExample {
    public static void main (String[] args) throws Exception {
        final TTS tts = new TTS ();
        tts.config ()
                .timeout (3, TimeUnit.SECONDS)  // If no data is received from the server after this time, 
                                                // it will enter idle mode
                .voice (VoiceRole.Xiaoxuan);    // voice role，一个萌妹子. A cute chinese girl.
        tts.setListener (new ITTSListener () {
            @Override
            public void idle () {
                tts.dispose ();
            }
        });

        String[] messages = {
                "TTS是Text To Speech的缩写，即“从文本到语音”，是人机对话的一部分，让机器能够说话",
                "它是同时运用语言学和心理学的杰出之作，在内置芯片的支持之下，通过神经网络的设计，把文字智能地转化为自然语音流。",
                "TTS技术对文本文件进行实时转换，转换时间之短可以秒计算。在其特有智能语音控制器作用下，文本输出的语音音律流畅，",
                "使得听者在听取信息时感觉自然，毫无机器语音输出的冷漠与生涩感。"
        };
        for (String text : messages) {
            tts.synthesis (text);
        }
    }
}
```
Changing the voice role
```java
import org.dreamwork.tools.tts.ITTSListener;
import org.dreamwork.tools.tts.TTS;
import org.dreamwork.tools.tts.VoiceRole;

import java.io.IOException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class ChangeVoiceRoleExample {
    static int index = 0;
    public static void main (String[] args) throws IOException {
        final VoiceRole[] roles = VoiceRole.byLocale ("en-GB").toArray (new VoiceRole[0]);
        final TTS tts = new TTS ();
        final Lock locker = new ReentrantLock ();
        final Condition c = locker.newCondition ();
        final String text = "Text to speech enables your applications, tools, " +
                            "or devices to convert text into human like synthesized speech.";
        tts.setListener (new ITTSListener () {
            @Override
            public void started (String text) {
                System.out.printf ("Now %s says ...%n", roles[index ++]);
            }

            @Override
            public void finished (String text) {
                try {
                    locker.lockInterruptibly ();
                    c.signalAll ();
                } catch (InterruptedException ignore) {}
                finally {
                    locker.unlock ();
                }
            }
        });

        for (VoiceRole role : roles) {
            tts.config ().voice (role); // change the voice role
            tts.synthesis (text);
            if (index < roles.length) {
                try {
                    locker.lockInterruptibly ();
                    c.awaitUninterruptibly ();
                } catch (InterruptedException ignore) {}
                finally {
                    locker.unlock ();
                }
            }
        }
        tts.dispose ();
    }
}
```
## Thanks
- [https://github.com/ikfly/java-tts.git](https://github.com/ikfly/java-tts.git)
- [https://github.com/ag2s20150909/TTS](https://github.com/ag2s20150909/TTS)
- [https://github.com/rany2/edge-tts](https://github.com/rany2/edge-tts)
- [https://github.com/Migushthe2nd/MsEdgeTTS](https://github.com/Migushthe2nd/MsEdgeTTS)
- [https://learn.microsoft.com/zh-cn/azure/cognitive-services/speech-service/index-text-to-speech](https://learn.microsoft.com/zh-cn/azure/cognitive-services/speech-service/index-text-to-speech)