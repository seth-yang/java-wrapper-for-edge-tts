package org.dreamwork.tools.tts;

import com.google.gson.Gson;
import javazoom.jl.player.advanced.AdvancedPlayer;
import javazoom.jl.player.advanced.PlaybackEvent;
import javazoom.jl.player.advanced.PlaybackListener;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static org.dreamwork.tools.tts.CommandLineHelper.isNotEmpty;
import static org.dreamwork.tools.tts.Const.*;
import static org.dreamwork.tools.tts.TTSConfig.*;

public class TTS {
    /** default buffer size */
    private static final int BUFF_SIZE = 1024;
    /** MIME-Type */
    private static final MediaType JSON = MediaType.get ("application/json;charset=utf-8");
    /** default config dir */
    private static final String[] CONFIGS = {
            "../conf/edge-tts-wrapper.conf", "../conf/edge-tts-wrapper.properties",
            "conf/edge-tts-wrapper.conf", "conf/edge-tts-wrapper.properties",
            "edge-tts-wrapper.conf", "edge-tts-wrapper.properties"
    };

    /** the ok http client connection pool */
    private final ConnectionPool cp = new ConnectionPool (3, 60, TimeUnit.SECONDS);

    private final Logger logger = LoggerFactory.getLogger (TTS.class);

    /** pipe in-out for mp3 player */
    private final PipedInputStream pis;
    private final PipedOutputStream pos;

    /** messages waiting for send to the websocket */
    private final Queue<Object> queue = new LinkedList<> ();

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

    /** the endpoint for synthesis */
    private String endpoint;
    /** the api key */
    private String API_KEY;
    /** the http proxy */
    private Proxy proxy;
    private String proxyUser, proxyPassword;

    /** 指示是否正在合成语音的治时期 */
    private volatile boolean synthesising = false;

    private volatile boolean running = true;

    /** TTS Listener */
    private volatile ITTSListener listener;

    /** 最近一次从 websocket 服务器端接收数据 */
    private volatile long timestamp = -1;

    private final OkHttpClient client;

    public TTS (String... args) throws IOException {
        // 命令行获取参数为第一优先级
        this (CommandLineHelper.loadFromCommandLineArgs (args));
    }

    public TTS (Properties props) throws IOException {
        // 若命令行未提供参数，从 jvm 参数获取
        if (props == null) {
            endpoint = System.getProperty (KEY_ENDPOINT);
            API_KEY  = System.getProperty (KEY_API_KEY);
            if (endpoint != null && !endpoint.trim ().isEmpty () &&
                    API_KEY  != null && !API_KEY.trim ().isEmpty ()) {
                props = new Properties (System.getProperties ());
            }
        }
        // 然后从 默认位置的配置文件获取参数
        if (props == null) {
            props = loadConfig ();
        }

        if (props != null) {
            initHttp (props);
        } else {
            throw new RuntimeException ("cannot load config");
        }

        client = createClient ();

        pos = new PipedOutputStream ();
        pis = new PipedInputStream (pos);

        ExecutorService executor = Executors.newFixedThreadPool (3);
        // 启动播放器线程
        futures.add (executor.submit (() -> play ()));
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
        if (running) {
            int retry = 3;
            while (retry-- > 0) {
                if (queue.offer (text)) {
                    if (logger.isTraceEnabled ()) {
                        logger.trace ("text[{}] cached.", text);
                    }
                    return;
                }
            }
            throw new RuntimeException ("cannot synthesis the text: " + text);
        } else {
            throw new IllegalStateException ("the TTS-Engine has been disposed.");
        }
    }

    public void play (Path path) {
        if (running) {
            if (!queue.offer (path)) {
                if (logger.isTraceEnabled ()) {
                    logger.warn ("cannot offer path");
                }
            }
        }
    }

    public void play (InputStream in) {
        if (running) {
            if (!queue.offer (in)) {
                if (logger.isTraceEnabled ()) {
                    logger.warn ("cannot offer input stream");
                }
            }
        }
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

        if (!futures.isEmpty ()) {
            for (Future<?> future : futures) {
                future.cancel (true);
            }
            futures.clear ();
        }

        cp.evictAll ();
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    private void runSynthesisTask () {
        Thread.currentThread ().setName ("synthesis");
        while (running) {
            if (timestamp >= 0 && System.currentTimeMillis () - timestamp > config.timeout) {
                logger.warn (
                        "No data has been received for more than {} milliseconds, entering Idle mode",
                        config.timeout
                );

                if (listener != null) {
                    if (!tasks.offer (() -> listener.idle ())) {
                        logger.warn ("cannot offer the listener idle");
                    }
                }
                timestamp = -1;
            }

            // take next message.
            Object obj = queue.poll ();
            if (obj != null) {
                try {
                    config.check ();
                } catch (RuntimeException ex) {
                    logger.error (ex.getMessage ());
                    continue;
                }

                // set the synthesising.
                synthesising = true;

                boolean playing = true;
                try {
                    if (listener != null && !tasks.offer (() -> listener.started (obj))) {
                        logger.warn ("cannot offer listener start");
                    }
                    if (obj instanceof CharSequence) {
                        String message = obj.toString ();
                        if (isNotEmpty (message)) {
                            send (message);
                        }
                    } else if (obj instanceof Path) {
                        try (InputStream in = Files.newInputStream ((Path) obj)) {
                            copy (in);
                        }
                    } else if (obj instanceof InputStream) {
                        copy ((InputStream) obj);
                    } else {
                        playing = false;
                        synthesising = false;
                        if (listener != null && !tasks.offer (() -> listener.handleException (obj, new RuntimeException ("unsupported target")))) {
                            logger.warn ("cannot offer listener handle exception.");
                        }
                    }
                } catch (Exception ex) {
                    logger.warn (ex.getMessage (), ex);
                    if (listener != null && !tasks.offer (() -> listener.handleException (obj, ex))) {
                        logger.warn ("cannot offer listener handle exception");
                    }
                } finally {
                    if (playing) {
                        onFinish (obj);
                    }
                }

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

                if (config.oneShot) {
                    dispose ();
                    break;
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

    private void send (String message) throws IOException {
        config.payload.input = message;
        RequestBody content = RequestBody.create (JSON, new Gson ().toJson (config.payload));
        Request request = new Request.Builder ().post (content)
                .url (endpoint)
                .addHeader ("Content-Type", "application/json;charset=utf-8")
                .addHeader ("Authorization", "Bearer " + API_KEY).build ();
        try (Response response = client.newCall (request).execute ()) {
            if (response.isSuccessful ()) {
                timestamp = System.currentTimeMillis ();

                ResponseBody body = response.body ();
                if (body != null && config.mode != 0) {
                    // 至少一个模式被激活了
                    try (InputStream in = body.byteStream ()) {
                        copy (in);
                    }
                }
            } else {
                logger.warn (response.message ());
            }
        }
    }

    private void copy (InputStream in) throws IOException {
        byte[] buff = new byte[BUFF_SIZE];
        int length;
        while ((length = in.read (buff)) != -1) {
            // 实时模式，将数据复制到播放器中
            if ((config.mode & MODE_REALTIME) != 0) {
                copy (buff, pos, length);
            }
            // 转发模式，将数据复制到输出流中
            if ((config.mode & MODE_FORWARDING) != 0 && config.output != null) {
                copy (buff, config.output, length);
            }
            // 文件保存模式，将数据复制到文件流中
            if ((config.mode & MODE_SAVE) != 0 && config.stream != null) {
                copy (buff, config.stream, length);
            }
        }
    }

    private void copy (byte[] buff, OutputStream out, int length) {
        timestamp = System.currentTimeMillis ();
        try {
            out.write (buff, 0, length);
            out.flush ();
        } catch (IOException ex) {
            logger.warn (ex.getMessage (), ex);
        }
    }

    private Properties loadConfig () {
        List<String> list = new ArrayList<> (Arrays.asList (CONFIGS));
        list.add (System.getProperty ("user.home") + "/.edge-tts-wrapper.conf");
        for (String config : list) {
            Path path = Paths.get (config);
            if (Files.exists (path)) {
                try (InputStream in = Files.newInputStream (path)) {
                    return loadConfig (in);
                } catch (IOException ex) {
                    logger.warn ("cannot read {}, because {}", path, ex.getMessage ());
                    if (logger.isTraceEnabled ()) {
                        logger.warn (ex.getMessage (), ex);
                    }
                }
            }
        }

        return null;
    }

    private Properties loadConfig (InputStream in) throws IOException {
        Properties props = new Properties ();
        props.load (in);
        return props;
    }

    private OkHttpClient createClient () {
        OkHttpClient.Builder builder = new OkHttpClient.Builder ().connectionPool (cp)
                .writeTimeout (30, TimeUnit.SECONDS)
                .connectTimeout (30, TimeUnit.SECONDS)
                .readTimeout (30, TimeUnit.SECONDS);
        if (proxy != null) {
            builder.proxy (proxy);
            if (isNotEmpty (proxyUser) && isNotEmpty (proxyPassword)) {
                builder.proxyAuthenticator ((route, response) -> {
                    String credential = Credentials.basic(proxyUser, proxyPassword);
                    return response.request().newBuilder()
                            .header("Proxy-Authorization", credential)
                            .build();
                });
            }
        }
        return builder.build ();
    }

    private void onFinish (Object message) {
        // 一段文本合成完成
        synthesising = false;   // 一段解码结束

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
            if (!tasks.offer (() -> listener.finished (message))) {
                logger.warn ("cannot offer the listener.finished when end");
            }
            if ((config.mode & MODE_SAVE) != 0 && config.stream != null) {
                if (!tasks.offer (() -> {
                    try {
                        listener.voiceSaved (message, config.target);
                    } finally {
                        config.target = null;

                        // close the file stream
                        config.closeStream ();
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

    private void initHttp (Properties props) {
        endpoint                = props.getProperty (KEY_ENDPOINT);
        API_KEY                 = props.getProperty (KEY_API_KEY);
        String proxyServer      = props.getProperty (KEY_PROXY_SERVER);
        String _enable          = props.getProperty (KEY_PROXY_ENABLED);
        String _port            = props.getProperty (KEY_PROXY_PORT);
        proxyUser               = props.getProperty (KEY_PROXY_USER);
        proxyPassword           = props.getProperty (KEY_PROXY_PASSWORD);
        boolean proxyEnabled    = _enable != null && !_enable.trim ().isEmpty ();
        int proxyPort;
        if (_port != null && !_port.trim ().isEmpty ()) {
            proxyPort = Integer.parseInt (_port.trim ());
        } else {
            proxyPort = 8080;
        }
        if (proxyEnabled) {
            proxy = new Proxy (Proxy.Type.HTTP, new InetSocketAddress (proxyServer, proxyPort));
        } else {
            proxy = null;
        }
    }

    private void delay () {
        try {
            Thread.sleep (0);
        } catch (InterruptedException ignore) {}
    }
}