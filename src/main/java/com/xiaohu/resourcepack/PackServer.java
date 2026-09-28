package com.xiaohu.resourcepack;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

/**
 * 内置 HTTP / HTTPS 资源包下载服务（JDK 自带 {@code com.sun.net.httpserver}）。
 * <p>
 * 每个请求按 {@link BandwidthAllocator.Session} 限速（公平分片），并把会话归属到具体玩家
 * （通过 {@link PlayerTracker} 按连接 IP 关联），从而在控制台输出"对某玩家的发包速度"，
 * 并通过屏幕底部 ActionBar 实时向玩家推送"当前几人下载 / 分给你多少 / 预计多久"。
 */
public final class PackServer {

    private HttpServer server;
    private ExecutorService executor;

    private final String ip;
    private final int port;
    private final boolean https;
    private final String keystorePath;
    private final String keystorePassword;
    private final File dataFolder;
    private final BandwidthAllocator allocator;
    private final PlayerTracker playerTracker;
    private final JavaPlugin plugin;

    private volatile Map<String, ResourcePackManager.ResourcePackEntry> entries =
            new ConcurrentHashMap<>();

    private volatile boolean started;

    public PackServer(String ip, int port, boolean https, String keystorePath, String keystorePassword,
                      File dataFolder, BandwidthAllocator allocator, PlayerTracker playerTracker,
                      JavaPlugin plugin) {
        this.ip = ip;
        this.port = port;
        this.https = https;
        this.keystorePath = keystorePath;
        this.keystorePassword = keystorePassword;
        this.dataFolder = dataFolder;
        this.allocator = allocator;
        this.playerTracker = playerTracker;
        this.plugin = plugin;
    }

    /** 原子替换条目引用（reload 时由插件调用），保证 handler 线程读取到最新条目。 */
    public void setEntries(Map<String, ResourcePackManager.ResourcePackEntry> entries) {
        if (entries == null) {
            entries = new ConcurrentHashMap<>();
        }
        this.entries = entries;
    }

    /** 启动内置下载服务；返回 true 表示绑定并监听成功。 */
    public boolean start() {
        try {
            InetSocketAddress addr = new InetSocketAddress(port);
            if (https) {
                SSLContext sslContext = buildSslContext();
                if (sslContext == null) {
                    Log.warn("无法构建 TLS 上下文，HTTPS 服务未启动（可预置 keystore 解决）");
                    return false;
                }
                HttpsServer s = HttpsServer.create(addr, 0);
                s.setHttpsConfigurator(new HttpsConfigurator(sslContext));
                this.server = s;
            } else {
                this.server = HttpServer.create(addr, 0);
            }

            this.server.createContext("/", this::handle);
            // 每个下载在写块之间会睡眠限速，因此线程池要留足并发的下载量。
            int threads = Math.max(16, Runtime.getRuntime().availableProcessors() * 4);
            this.executor = Executors.newFixedThreadPool(threads);
            this.server.setExecutor(this.executor);
            this.server.start();
            this.started = true;
            Log.success("内置 " + (https ? "HTTPS" : "HTTP") + " 下载服务已启动，监听 :" + port
                    + "，服务地址 " + baseUrl());
            return true;
        } catch (IOException e) {
            if (isBindError(e)) {
                Log.error("检测到端口 " + port + " 已被占用，无法启动内置下载服务。"
                        + "请修改 config.yml 的 server.port，或先释放该端口（可能被其他程序/某一线程占用）。");
            } else {
                Log.error("启动内置下载服务失败: " + e.getMessage());
            }
            this.started = false;
            return false;
        }
    }

    /**
     * 探测配置的端口当前能否被本机绑定（0.0.0.0:port）。供 {@code status} 命令显示"是否被占用"。
     * 注：若本插件服务正在该端口上监听，探测会返回 false（被自身占用，属正常）。
     */
    public boolean isPortBindable() {
        return isPortFree(port);
    }

    private static boolean isPortFree(int port) {
        try (ServerSocket ss = new ServerSocket()) {
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** 判断一个 IOException 是否由"地址被占用 / 无法绑定"引起。 */
    private static boolean isBindError(IOException e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof java.net.BindException) {
                return true;
            }
            String msg = t.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("address already in use") || lower.contains("bind")
                        || lower.contains("unable to assign")) {
                    return true;
                }
            }
            t = t.getCause();
        }
        return false;
    }

    /** 停止内置下载服务并释放线程池。 */
    public void stop() {
        if (this.server != null) {
            this.server.stop(0);
            this.server = null;
        }
        if (this.executor != null) {
            this.executor.shutdown();
            this.executor = null;
        }
        this.started = false;
    }

    /** 下载地址（供客户端请求用），由配置的 ip/port/protocol 构成。 */
    public String baseUrl() {
        return (https ? "https://" : "http://") + ip + ":" + port + "/";
    }

    public boolean isListening() {
        return started && server != null;
    }

    private SSLContext buildSslContext() {
        try {
            KeyStore ks;
            if (keystorePath != null && !keystorePath.isBlank()) {
                ks = KeyStore.getInstance("PKCS12");
                try (FileInputStream in = new FileInputStream(keystorePath)) {
                    ks.load(in, keystorePassword.toCharArray());
                }
            } else {
                ks = generateSelfSigned();
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, keystorePassword.toCharArray());
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), null, null);
            return ctx;
        } catch (Exception e) {
            Log.error("TLS 配置失败: " + e.getMessage());
            return null;
        }
    }

    /** 无预置 keystore 时，用 keytool 子进程生成一张自签 PKCS12 证书。 */
    private KeyStore generateSelfSigned() throws Exception {
        File p12 = new File(dataFolder, "xiaohu.p12");
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "keytool", "-genkeypair",
                    "-alias", "xiaohu",
                    "-keyalg", "RSA",
                    "-storetype", "PKCS12",
                    "-keystore", p12.getAbsolutePath(),
                    "-storepass", keystorePassword,
                    "-dname", "CN=xiaohu_package",
                    "-keypass", keystorePassword);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                while (reader.readLine() != null) {
                    // 消费输出，避免管道阻塞等待。
                }
            }
            int code = proc.waitFor();
            if (code != 0) {
                throw new IllegalStateException("keytool 返回码 " + code);
            }
        } catch (IOException e) {
            Log.warn("未找到 keytool 或生成自签证书失败: " + e.getMessage());
            throw e;
        }
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(p12)) {
            ks.load(in, keystorePassword.toCharArray());
        }
        return ks;
    }

    private void handle(HttpExchange exchange) throws IOException {
        BandwidthAllocator.Session session = null;
        try {
            String rawPath = exchange.getRequestURI().getRawPath();
            String path = URLDecoder.decode(rawPath, StandardCharsets.UTF_8);

            ResourcePackManager.ResourcePackEntry entry = entries.get(path);
            if (entry == null || entry.getFile() == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            File file = entry.getFile();
            if (!file.exists() || !file.isFile()) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }

            // 归属玩家（按连接 IP），用于上报速度与推送提示。
            Player player = playerTracker.resolve(exchange.getRemoteAddress());
            session = allocator.newSession(player, entry);
            long fileLen = file.length();

            if (player != null) {
                // 控制台：开始下载 + 分配给该玩家的份额。
                int active = allocator.activeSessionCount();
                double share = allocator.shareMbps(active);
                Log.info("玩家 " + player.getName() + " 开始下载资源包 [" + entry.getFileName()
                        + "]：当前共 " + active + " 个下载会话，服务器总 " + allocator.getMbps()
                        + " Mbps，本玩家分配 " + fmt(share) + " Mbps");
            }

            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.getResponseHeaders().set("Content-Disposition",
                    "attachment; filename=\"" + entry.getFileName() + "\"");
            exchange.sendResponseHeaders(200, fileLen);

            byte[] buf = new byte[64 * 1024];
            try (FileInputStream in = new FileInputStream(file);
                 OutputStream out = exchange.getResponseBody()) {
                int n;
                long lastActionBar = System.nanoTime();
                while ((n = in.read(buf)) > 0) {
                    allocator.pace(session, n);
                    out.write(buf, 0, n);

                    if (player != null) {
                        long now = System.nanoTime();
                        if (now - lastActionBar >= 1_000_000_000L) {
                            pushActionBar(player, session, fileLen);
                            lastActionBar = now;
                        }
                        if (session.elapsedSinceLogNanos() >= 5_000_000_000L) {
                            double pct = fileLen > 0 ? session.getBytesSent() * 100.0 / fileLen : 0;
                            Log.info("玩家 " + player.getName() + " 下载资源包 [" + entry.getFileName()
                                    + "] 进度 " + fmt(pct) + "%，当前速度 " + fmt(session.currentMbps()) + " Mbps");
                            session.markLogged();
                        }
                    }
                }
                out.flush();
                // 下载完成，清掉 ActionBar，避免残留"预计 X 秒"。
                if (player != null) {
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (player.isOnline()) {
                            player.sendActionBar("");
                        }
                    });
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        } catch (Exception e) {
            Log.error("处理下载请求出错: " + e.getMessage());
            try {
                exchange.sendResponseHeaders(500, -1);
            } catch (Exception ignored) {
                // 响应已发出，无法再写错误码。
            }
        } finally {
            // 关键：下载连接结束（成功或异常）都要移除会话，让 active 数只反映"当前正在下载"的连接，
            // 否则一个玩家下多个包时会话会越攒越多，公平分片把速度越分越小。
            if (session != null) {
                allocator.removeSession(session);
            }
            exchange.close();
        }
    }

    private static String fmt(double v) {
        // 保留 1 位小数。
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    /**
     * 通过屏幕底部 ActionBar 实时向玩家推送下载状态（当前几人 / 总网速 / 分给你 / 预计）。
     * 在 HTTP 线程调用，推送经 scheduler 切回主线程。预计时间按当前包剩余字节 ÷ 分得速度估算。
     */
    private void pushActionBar(Player player, BandwidthAllocator.Session session, long fileLen) {
        int active = allocator.activeSessionCount();
        int players = allocator.activePlayerCount();
        double share = allocator.shareMbps(active);
        double shareBps = allocator.shareBytesPerSec(active);
        long remaining = Math.max(0L, fileLen - session.getBytesSent());
        int estSec = shareBps <= 0 ? 0 : (int) Math.ceil(remaining / shareBps);

        String ab = "&f[资源包] &e当前 &f" + players + "&e 名玩家下载 · 服务器总 &f" + allocator.getMbps()
                + "&e Mbps · 分给你 &f" + fmt(share) + "&e Mbps · 预计 &f" + fmtTime(estSec);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                player.sendActionBar(Messenger.colorize(ab));
            }
        });
    }

    private static String fmtTime(int seconds) {
        if (seconds <= 0) {
            return "0 秒";
        }
        if (seconds < 60) {
            return seconds + " 秒";
        }
        int m = seconds / 60;
        int s = seconds % 60;
        return m + " 分 " + s + " 秒";
    }
}
