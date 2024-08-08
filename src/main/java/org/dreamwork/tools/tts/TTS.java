package org.dreamwork.tools.tts;

import io.ikfly.constant.OutputFormat;
import io.ikfly.exceptions.TtsException;
import io.ikfly.model.SSML;
import io.ikfly.model.SpeechConfig;
import io.ikfly.util.Tools;
import javazoom.jl.player.advanced.AdvancedPlayer;
import javazoom.jl.player.advanced.PlaybackEvent;
import javazoom.jl.player.advanced.PlaybackListener;
import okhttp3.*;
import okio.ByteString;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static io.ikfly.constant.TtsConstants.*;

public class TTS {
    private static final byte[] BA_AUDIO_START = AUDIO_START.getBytes (StandardCharsets.UTF_8);
    private static final byte[] BA_AUDIO_CONTENT_TYPE = AUDIO_CONTENT_TYPE.getBytes(StandardCharsets.UTF_8);

    private final Logger logger = LoggerFactory.getLogger (TTS.class);
    private final PipedInputStream pis;
    private final PipedOutputStream pos;

    /** messages waiting for send to the websocket */
    private final Queue<String> queue = new LinkedList<> ();
    /** any listener actions */
    private final BlockingQueue<Runnable> tasks = new LinkedBlockingQueue<> ();
    /** the quit signal */
    private final Runnable QUIT = () -> {};
    private final Lock locker = new ReentrantLock (true);
    private final Condition c = locker.newCondition ();

    /** the main configurations */
    private final TTSConfig config = new TTSConfig ();
    /** executing tasks */
    private final List<Future<?>> futures = new ArrayList<> (3);

    private AdvancedPlayer player;
    private WebSocket websocket;
    private OkHttpClient okHttpClient;
    private volatile boolean synthesising = false;
    private volatile boolean running = true;
    private volatile OutputFormat outputFormat;

    private volatile ITTSListener listener;
    private volatile long timestamp = -1;

    private final WebSocketListener webSocketListener = new WebSocketListener() {
        @Override
        public void onOpen (@NotNull WebSocket webSocket, @NotNull Response response) {
            timestamp = System.currentTimeMillis ();
            logger.info ("websocket opened.");
        }

        @Override
        public void onMessage (@NotNull WebSocket webSocket, @NotNull String text) {
            timestamp = System.currentTimeMillis ();
            if (text.contains (TURN_START)) {
                if (listener != null) {
                    if (!tasks.offer (() -> listener.started (text))) {
                        logger.warn ("cannot offer the listener when start");
                    }
                }
            } else if (text.contains(TURN_END)) {
                if (logger.isTraceEnabled ()) {
                    logger.trace ("receive a text message: {}", text);
                }
                synthesising = false;   // 一段解码结束
                try {
                    locker.lockInterruptibly ();
                    c.signalAll ();
                } catch (InterruptedException ignore) {
                } finally {
                    locker.unlock ();
                }
                if (listener != null) {
                    if (!tasks.offer (() -> listener.finished (text))) {
                        logger.warn ("cannot offer the listener.finished when end");
                    }
                }
            }
        }

        @Override
        public void onMessage (@NotNull WebSocket webSocket, @NotNull ByteString bytes) {
            timestamp = System.currentTimeMillis ();
            int index = bytes.lastIndexOf(BA_AUDIO_START) + AUDIO_START.length ();
            int content = bytes.indexOf (BA_AUDIO_CONTENT_TYPE);
            if (index != -1 && content != -1) {
                try {
                    ByteString substring = bytes.substring (index);
                    substring.write (pos);
                    pos.flush ();
                } catch (Exception e) {
                    logger.error("onMessage Error," + e.getMessage(), e);
                }
            }
        }

        @Override
        public void onClosed (@NotNull WebSocket webSocket, int code, @NotNull String reason) {
            logger.info ("websocket closed, code = {}, reason = {}", code, reason);
            timestamp = -1;
        }
    };

    public TTS () throws IOException {
        pos = new PipedOutputStream ();
        pis = new PipedInputStream (pos);

        ExecutorService executor = Executors.newFixedThreadPool (3);
        // 启动播放器线程
        futures.add (executor.submit (this::play));
        // 启动语音合成线程
        futures.add (executor.submit (this::runSynthesisTask));
        // 启动专门用于处理外界监听器的线程
        futures.add (executor.submit (this::runInListenerThread));
        executor.shutdown ();
    }

    public TTS oneShot () {
        config.oneShot ();
        return this;
    }

    public TTSConfig getConfig () {
        return config;
    }

    public void synthesis (String text) {
        int retry = 3;
        while (retry --> 0) {
            if (queue.offer (text)) {
                if (logger.isTraceEnabled ()) {
                    logger.trace ("text[{}] cached.", text);
                }
                return;
            }
        }
        throw new RuntimeException ("cannot synthesis the text: " + text);
    }

    public void setListener (ITTSListener listener) {
        this.listener = listener;
    }

    public void dispose () {
        running = false;
        player.stop ();
        tasks.clear ();
        try {
            tasks.put (QUIT);
        } catch (InterruptedException ignore) {
        }

        try {
            pis.close ();
        } catch (IOException ignore) {}
        try {
            pos.close ();
        } catch (IOException ignore) {}
        if (websocket != null) {
            websocket.close (1000, "bye");
            websocket = null;
        }
        if (!futures.isEmpty ()) {
            for (Future<?> future : futures) {
                future.cancel (true);
            }
            futures.clear ();
        }
    }

    private void sendConfig (OutputFormat outputFormat) {
        SpeechConfig speechConfig = SpeechConfig.of (outputFormat);
        logger.debug ("audio config:{}", speechConfig);
        if (!getOrCreateWs ().send (speechConfig.toString ())) {
            throw TtsException.of ("语音输出格式配置失败...");
        }
        this.outputFormat = speechConfig.getOutputFormat ();
    }

    private synchronized WebSocket getOrCreateWs () {
        if (Objects.nonNull (websocket)) {
            return websocket;
        }

        String url = EDGE_SPEECH_WSS + "?Retry-After=200&TrustedClientToken=" +
                     TRUSTED_CLIENT_TOKEN + "&ConnectionId=" + Tools.getRandomId ();
        Request request = new Request.Builder ()
                .url (url)
                .addHeader ("User-Agent", UA)
                .addHeader ("Origin", EDGE_SPEECH_ORIGIN)
                .build ();
        websocket = getOkHttpClient ().newWebSocket (request, webSocketListener);
        sendConfig (outputFormat);
        return websocket;
    }

    private OkHttpClient getOkHttpClient () {
        if (okHttpClient == null) {
            okHttpClient = new OkHttpClient.Builder ()
                    .pingInterval (20, TimeUnit.SECONDS) // 设置 PING 帧发送间隔
                    .build ();
        }
        return okHttpClient;
    }

    private void delay () {
        try {
            Thread.sleep (10);
        } catch (InterruptedException ignore) {}
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    private void runSynthesisTask () {
        Thread.currentThread ().setName ("synthesis");
        while (running) {
            if (timestamp >= 0 && System.currentTimeMillis () - timestamp > config.timeout) {
                logger.warn ("there's over {} ms not receive any message, disconnect the websocket temp!", config.timeout);
                if (websocket != null) {
                    websocket.close (1000, "bye");
                    logger.info ("the websocket closed.");
                    websocket = null;
                }
                if (listener != null) {
                    if (!tasks.offer (() -> listener.idle ())) {
                        logger.warn ("cannot offer the listener idle");
                    }
                }
                timestamp = -1;
            }
            String message = queue.poll ();
            if (message != null && !message.isEmpty ()) {
                if (logger.isTraceEnabled ()) {
                    logger.trace ("a new text[{}] token.", message);
                }
                synthesising = true;
                SSML ssml = config.synthesis (message);

                if (Objects.nonNull (ssml.getOutputFormat ()) && !ssml.getOutputFormat ().equals (outputFormat)) {
                    sendConfig (ssml.getOutputFormat ());
                }
                if (!getOrCreateWs ().send (ssml.toString ())) {
                    logger.warn ("cannot send text: {}", message);
                } else if (logger.isTraceEnabled ()) {
                    logger.trace ("text[{}] send ok.", ssml);
                }
                while (synthesising) {
                    try {
                        locker.lockInterruptibly ();
                        c.awaitUninterruptibly ();
                    } catch (InterruptedException ex) {
                        // ignore
                    } finally {
                        locker.unlock ();
                    }
                }
            }
            delay ();
        }
        logger.info ("main synthesis loop finished.");
    }

    private void runInListenerThread () {
        Thread.currentThread ().setName ("TTSListener");
        while (running) {
            Runnable task;
            try {
                task = tasks.take ();
            } catch (InterruptedException ex) {
                continue;
            }

            if (task == QUIT) {
                break;
            }

            try {
                task.run ();
            } catch (Throwable t) {
                logger.warn (t.getMessage (), t);
            }
        }
        logger.info ("listener loop finished.");
    }

    private void play () {
        Thread.currentThread ().setName ("player");
        try {
            player = new AdvancedPlayer (pis);
            player.setPlayBackListener (new PlaybackListener () {
                @Override
                public void playbackFinished (PlaybackEvent evt) {
                    logger.info ("bye!");
                }
            });
            player.play ();
        } catch (Exception ex) {
            logger.warn (ex.getMessage (), ex);
            throw new RuntimeException (ex);
        }
        logger.info ("player done.");
    }
}