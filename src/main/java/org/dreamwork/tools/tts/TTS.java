package org.dreamwork.tools.tts;

import javazoom.jl.player.advanced.AdvancedPlayer;
import javazoom.jl.player.advanced.PlaybackEvent;
import javazoom.jl.player.advanced.PlaybackListener;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static org.dreamwork.tools.tts.TTSConfig.*;
import static org.dreamwork.tools.tts.VoiceFormat.audio_24khz_48kbitrate_mono_mp3;

public class TTS {

    /** 音频流开始传输标记 */
    private static final String TURN_START = "turn.start";
    /** 音频流结束传输标记 */
    private static final String TURN_END = "turn.end";

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

    /** mp3 播放器 */
    private AdvancedPlayer player;

    /** websocket 客户端 */
    private WebSocketClient client;

    /** 指示是否正在合成语音的治时期 */
    private volatile boolean synthesising = false;

    private volatile boolean running = true;

    /** TTS Listener */
    private volatile ITTSListener listener;

    /** 最近一次从 websocket 服务器端接收数据 */
    private volatile long timestamp = -1;

    /** 当前正在处理的文本 */
    private volatile String current;

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

    /**
     * 获取转换配置
     * @return TTSConfig 实例
     */
    public TTSConfig config () {
        return config;
    }

    /**
     * 添加一段文本到等待合成的队列中
     * @param text 待合成的文本
     */
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

    /**
     * 销毁实例.
     * <p>当一个 {@code TTS} 实例被销毁后，<strong>不能再</strong>进行语音转换</p>
     */
    public void dispose () {
        running = false;

        try {
            locker.lockInterruptibly ();
            c.signalAll ();
        } catch (InterruptedException ignore) {}
        finally {
            locker.unlock ();
        }
        if (logger.isDebugEnabled ()) {
            logger.debug ("release the locker");
        }

        if (player != null) {
            player.stop ();
        }

        try {
            tasks.clear ();
            tasks.put (QUIT);
        } catch (InterruptedException ignore) {
        }

        try {
            pis.close ();
        } catch (IOException ignore) {}
        try {
            pos.close ();
        } catch (IOException ignore) {}

        closeWebsocket ();

        if (!futures.isEmpty ()) {
            for (Future<?> future : futures) {
                future.cancel (true);
            }
            futures.clear ();
        }
    }

    /**
     * 向服务器端发送希望接收的语音格式
     */
    private void setVoiceFormat () {
        VoiceFormat format = config.format;
        if (format == null) {
            format = config.format = audio_24khz_48kbitrate_mono_mp3;
        }
        getOrCreateWebsocketClient ().send (VoiceFormat.asJson (format));
    }

    private synchronized WebSocketClient getOrCreateWebsocketClient () {
        if (client == null) {
            String url = config.WS_URL + "?Retry-After=200" +
                         "&TrustedClientToken=" + config.TOKEN +
                         "&ConnectionId=" + UUID.randomUUID().toString().replace("-", "");
            Map<String, String> header = new HashMap<> ();
            header.put ("User-Agent", config.UA);
            header.put ("Origin", config.ORIGIN);
            try {
                client = new WebSocketClient (new URI (url), header) {
                    private OutputStream stream;
                    private Path target;
                    private final SimpleDateFormat sdf = new SimpleDateFormat ("yyyy-MM-dd_HH-mm-ss");

                    @Override
                    public void onOpen (ServerHandshake handshake) {
                        // update the timestamp
                        timestamp = System.currentTimeMillis ();
                        // 检查是否开启了文件保存模式，若是，则准备好待写入的文件
                        if ((config.mode & MODE_SAVE) != 0 && config.dir != null && !config.dir.isEmpty ()) {
                            try {
                                String format = config.format.toString ();
                                int position = format.lastIndexOf ('_');
                                String ext = format.substring (position + 1);
                                String fileName = sdf.format (System.currentTimeMillis ()) + "." + ext;
                                target = Paths.get (config.dir, fileName);
                                stream = Files.newOutputStream (target);
                            } catch (IOException ex) {
                                logger.warn (ex.getMessage (), ex);
                            }
                        }
                        logger.info ("websocket opened.");
                    }

                    @Override
                    public void onMessage (String text) {
                        if (logger.isDebugEnabled ()) {
                            logger.debug ("received a message: {}", text);
                        }
                        // update the timestamp
                        timestamp = System.currentTimeMillis ();
                        if (logger.isTraceEnabled ()) {
                            logger.trace ("receive a text message: {}", text);
                        }

                        if (text.contains (TURN_START)) {
                            // 开始合成一段文本，触发监听器
                            if (listener != null) {
                                if (!tasks.offer (() -> listener.started (current))) {
                                    logger.warn ("cannot offer the listener when start");
                                }
                            }
                        } else if (text.contains(TURN_END)) {
                            // 一段文本合成完成
                            synthesising = false;   // 一段解码结束
                            // close the file stream
                            closeStream ();
                            // Send a signal to announce that a speech synthesis is completed
                            // and the next task can be carried out
                            try {
                                locker.lockInterruptibly ();
                                c.signalAll ();
                            } catch (InterruptedException ignore) {
                            } finally {
                                locker.unlock ();
                            }
                            // trigger the listener
                            if (listener != null) {
                                if (!tasks.offer (() -> listener.finished (current))) {
                                    logger.warn ("cannot offer the listener.finished when end");
                                }
                                if ((config.mode & MODE_SAVE) != 0 && stream != null) {
                                    if (!tasks.offer (() -> {
                                        try {
                                            listener.voiceSaved (current, target);
                                        } finally {
                                            target = null;
                                        }
                                    })) {
                                        logger.warn ("cannot offer the listener.voiceSaved");
                                    }
                                }
                            }

                            // 如果是 on shot，直接销毁实例
                            if (config.oneShot) {
                                dispose ();
                            }
                        }
                    }

                    @Override
                    public void onMessage (ByteBuffer bytes) {
                        timestamp = System.currentTimeMillis ();
                        // 至少一个模式被激活了
                        if (config.mode != 0) {
                            String line;
                            while (!(line = readLine (bytes)).isEmpty ()) {
                                if ("Path:audio".equals (line.trim ())) {
                                    break;
                                }
                            }
                            // the voice data length
                            int remains = bytes.remaining ();
                            byte[] buff = new byte[remains];
                            bytes.get (buff);
                            // 实时模式，将数据复制到播放器中
                            if ((config.mode & MODE_REALTIME) != 0) {
                                copy (buff, pos);
                            }
                            // 转发模式，将数据复制到输出流中
                            if ((config.mode & MODE_FORWARDING) != 0 && config.output != null) {
                                copy (buff, config.output);
                            }
                            // 文件保存模式，将数据复制到文件流中
                            if ((config.mode & MODE_SAVE) != 0 && stream != null) {
                                copy (buff, stream);
                            }
                        }
                    }

                    @Override
                    public void onClose (int code, String reason, boolean remote) {
                        logger.info ("websocket closed, code = {}, reason = {}", code, reason);
                        // reset the timestamp
                        timestamp = -1;
                        // close and clean file stream
                        closeStream ();

                        if (code != 1000) {
                            // something happened
                            dispose ();
                            throw new RuntimeException ("websocket closed unexpected: code = " + code + ", reason = " + reason);
                        }
                    }

                    @Override
                    public void onError (Exception ex) {}

                    private void closeStream () {
                        if (stream != null) {
                            try {
                                stream.flush ();
                                stream.close ();
                            } catch (IOException ignore) {}
                            finally {
                                stream = null;
                            }
                        }
                    }
                };
                client.connectBlocking ();
                // When the websocket connection is complete,
                // send the desired audio format to the server
                setVoiceFormat ();
            } catch (Exception ex) {
                throw new RuntimeException (ex);
            }
        }
        return client;
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
                logger.warn (
                        "No data has been received for more than {} milliseconds, " +
                        "entering Idle mode and disconnecting the websocket connection",
                        config.timeout
                );
                closeWebsocket ();

                if (listener != null) {
                    if (!tasks.offer (() -> listener.idle ())) {
                        logger.warn ("cannot offer the listener idle");
                    }
                }
                timestamp = -1;
            }
            // take next message.
            String message = queue.poll ();
            if (message != null && !message.isEmpty ()) {
                if (logger.isTraceEnabled ()) {
                    logger.trace ("a new text[{}] take.", message);
                }
                // set the synthesising.
                synthesising = true;
                current = message;
                SSMLPayload payload = config.synthesis (message);
                getOrCreateWebsocketClient ().send (payload.toString ());
                // Waiting for the previous task to complete
                while (synthesising && running) {
                    try {
                        if (logger.isDebugEnabled ()) {
                            logger.debug ("waiting for lock be released.");
                        }
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
        }
        player = null;
        logger.info ("player done.");
    }

    private void closeWebsocket () {
        if (client != null) {
            client.close (1000, "bye");
            client = null;
        }
    }

    private String readLine (ByteBuffer buffer) {
        byte[] target = new byte[128];
        int index = 0, remains = buffer.remaining ();
        if (remains == 0) {
            return "";
        }
        while (index < remains) {
            if (index >= target.length) {
                byte[] temp = new byte[target.length << 1];
                System.arraycopy (target, 0, temp, 0, target.length);
                target = temp;
            }
            target[index] = buffer.get ();
            if (target[index ++] == '\n') {
                break;
            }
        }
        return new String (target, 0, index, StandardCharsets.UTF_8);
    }

    private void copy (byte[] buff, OutputStream out) {
        try {
            out.write (buff);
            out.flush ();
        } catch (IOException ex) {
            logger.warn (ex.getMessage (), ex);
        }
    }
}