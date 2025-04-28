# java-wrapper-for-edge-tts
A java wrapper for MS edge-tts

## Quick Start

### Prerequisites
Before using this library, you need to follow the instructions of [https://github.com/linshenkx/edge-tts-openai-cf-worker](https://github.com/linshenkx/edge-tts-openai-cf-worker)
to create your own cf worker and prepare the calling url and api-key.

### configuration
To use this library, the following properties are required:
- `edge.tts.endpoint`
- `edge.tts.api-key`

The following properties are optional:
- `edge.tts.proxy.enabled`
- `edge.tts.proxy.server`
- `edge.tts.proxy.port`
- `edge.tts.proxy.user`
- `edge.tts.proxy.password`

You can provide values for them via a Java Properties file or via a JVM Option.

Or provides then via program arguments like:
```text
    -e  --endpoint=<endpoint>       the endpoint
    -k  --api-key=<api-key>         the api key for the endpoint
        --proxy-server=<host:port>  the http proxy
        --proxy-user=<user>         the user of proxy server
        --proxy-password=<password> the password for user of the proxy server
```

`org.dreamwork.tools.tts.TTS` provides two constructors, one for parsing command line arguments
and the other for parsing `Properties` file.

```java
public TTS (String... args) throws IOException;

public TTS (Properties props) throws IOException;
```

### Dependency
```xml
<dependency>
    <groupId>io.github.seth-yang</groupId>
    <artifactId>java-wrapper-for-edge-tts</artifactId>
    <version>1.0.3</version>
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
        TTS tts = new TTS (args);
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

public class OneShotAndSaveFileExample {
    public static void main (String[] args) throws IOException {
        TTS tts = new TTS (args);
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
        final TTS tts = new TTS (args);
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
        final TTS tts = new TTS (args);
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

## Voice Data Forwarding
```java
import org.dreamwork.tools.tts.ITTSListener;
import org.dreamwork.tools.tts.TTS;
import org.dreamwork.tools.tts.VoiceFormat;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;

public class DataFrowardExample {
    public static void main (String[] args) throws IOException, InterruptedException {
        TTS tts = new TTS (args);
        CountDownLatch latch = new CountDownLatch (1);
        tts.setListener (new ITTSListener () {
            @Override
            public void finished (String text) {
                latch.countDown ();
            }
        });
        Path target = Paths.get (System.getProperty ("java.io.tmpdir"), "forwarding.mp3");
        try (OutputStream out = Files.newOutputStream (target, StandardOpenOption.CREATE)) {
            tts.config ()
                    .format (VoiceFormat.audio_24khz_48kbitrate_mono_mp3)
                    .oneShot ()
                    .enableForwardMode ()
                    .forward (out);
            tts.synthesis ("数据转发示例");
            latch.await (); // wait for synthesis to complete
            out.flush ();
            System.out.println ("voice saved as: " + target.toRealPath ());
        }
    }
}
```

## Synthesis text and play local resources
```java
import org.dreamwork.tools.tts.ITTSListener;
import org.dreamwork.tools.tts.TTS;
import org.dreamwork.tools.tts.VoiceRole;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class SynthesisTextAndPlayLocalResourceExample {
    public static void main (String[] args) throws Exception {
        CountDownLatch latch = new CountDownLatch (3);
        TTS tts = new TTS (args);
        tts.config ()
                .timeout (500, TimeUnit.MILLISECONDS)
                .voice (VoiceRole.Xiaoyi);
        tts.setListener (new ITTSListener () {
            @Override
            public void started (Object target) {
                if (target instanceof String) {
                    System.out.printf ("starting synthesis text %s%n", target);
                } else if (target instanceof Path) {
                    System.out.printf ("starting play file: %s%n", target);
                } else if (target instanceof InputStream) {
                    System.out.println ("starting play mp3 within input stream");
                }
            }

            @Override
            public void finished (Object target) {
                System.out.println ("a voice finished");
                latch.countDown ();
            }
        });

        // synthesis text online
        tts.synthesis ("1.0.3版本可以混合文本转语音，也可以在队列中播放本地资源了");
        // play local mp3 file
        tts.play (Paths.get ("../voices/greetings-35.mp3"));
        // Play the input stream containing the mp3 audio stream
        // In the tts.play(InputStream) method, the InputStream object will be closed after the playback is completed. 
        // Therefore, you cannot call this method in the `try (resource) {}` way.
        tts.play (Files.newInputStream (Paths.get ("../voices/goodbye-9.mp3")));

        latch.await ();
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
- [https://github.com/linshenkx/edge-tts-openai-cf-worker](https://github.com/linshenkx/edge-tts-openai-cf-worker)
