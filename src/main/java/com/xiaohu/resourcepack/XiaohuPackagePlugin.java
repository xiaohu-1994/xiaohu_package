package com.xiaohu.resourcepack;

import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bukkit.event.player.PlayerResourcePackStatusEvent;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * 主类：材质包分发插件（Paper 26.2）。
 * <p>
 * onEnable：读配置 -> 扫描 packs/*.zip -> 启动 HTTP(S) -> 注册事件与命令 -> 打印彩色版头；
 * 资源包的扫描/合成/拆分在后台线程完成。onDisable：停止内置下载服务。
 */
public final class XiaohuPackagePlugin extends JavaPlugin {

    private BandwidthAllocator allocator;
    private ResourcePackManager manager;
    private PackServer server;
    private PlayerTracker playerTracker;
    private FileConfiguration cfg;

    private final Map<UUID, BukkitTask> askTasks = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> askTimeouts = new ConcurrentHashMap<>();
    private final Map<UUID, PackSummary> packSummaries = new ConcurrentHashMap<>();

    /**
     * 是否正在后台扫描/合成资源包。合成几百 MB 的包要几十秒，绝不能占用服务器主线程
     * （曾因此触发 Paper 看门狗 "The server has not responded for 25 seconds"）。
     * 生成期间不向玩家发包（旧产物已被删除，发了也是死链），改为登记待补发。
     */
    private final AtomicBoolean regenerating = new AtomicBoolean(false);

    /** 生成期间进服 / 被 resend 的玩家，生成完成后自动补发。 */
    private final Set<UUID> pendingResend = ConcurrentHashMap.newKeySet();

    private static final String DEFAULT_PROMPT = "&e是否下载本服务器的重要资源包？";

    /** 插件版本，与 build.gradle.kts 的 version 保持一致（仅用于版头显示）。 */
    private static final String VERSION = "1.9.0";

    @Override
    public void onEnable() {
        Log.init(this);
        saveDefaultConfig();
        cfg = getConfig();

        // 1) 公平带宽分配器（全局总速率，会话间按活跃数分片）。
        int mbps = cfg.getInt("bandwidth.mbps", 20);
        allocator = new BandwidthAllocator(mbps);

        // 2) 玩家 IP 追踪（用于把下载会话归属到具体玩家）。
        playerTracker = new PlayerTracker();

        // 3) 资源包管理器（扫描 packs/*.zip）。
        manager = new ResourcePackManager(getDataFolder(), cfg, baseUrl(cfg));
        loadBuiltinIcon();

        // 4) 先把内置 HTTP(S) 下载服务起来（条目暂时为空），
        //    扫描/合成/拆分放到后台线程做，避免开服时被几百 MB 的包卡住。
        server = buildServer(cfg);
        server.setEntries(manager.getByPath());
        if (!server.start()) {
            Log.warn("内置下载服务启动失败，插件仍运行（玩家下载不可用）");
        }

        // 5) 注册事件与命令。
        getServer().getPluginManager().registerEvents(playerTracker, this);
        getServer().getPluginManager().registerEvents(new PlayerPackListener(this, manager), this);
        PluginCommand cmd = getCommand("xiaohu_package");
        if (cmd != null) {
            cmd.setExecutor(new PackCommandExecutor(this));
            cmd.setTabCompleter(new PackTabCompleter());
        }
        PluginCommand answer = getCommand("xiaohu_packanswer");
        if (answer != null) {
            answer.setExecutor(new PackAnswerCommand(this));
        }

        // 6) 彩色版头 + 后台开始生成（开服不推送聊天进度）。
        printBanner();
        startAsyncRegeneration(false, false, null);
    }

    @Override
    public void onDisable() {
        for (BukkitTask t : askTasks.values()) {
            t.cancel();
        }
        askTasks.clear();
        for (BukkitTask t : askTimeouts.values()) {
            t.cancel();
        }
        askTimeouts.clear();
        if (server != null) {
            server.stop();
            server = null;
        }
        Log.info("xiaohu_package 已停用");
    }

    /**
     * 在后台线程重新扫描/合成/拆分资源包，完成后回主线程重建下载服务并补发。
     * <p>
     * 这些操作要读写几百 MB（删旧产物、合成、拆分、算 SHA-1），放主线程会直接卡服
     * （Paper 看门狗会打印 "The server has not responded for N seconds"）。
     * 全程不触碰 Bukkit 世界/玩家 API：线程里只做文件 IO 与配置读取。
     *
     * @param force         true = 无条件重新生成；false = 指纹未变则复用（开服 / 源包没改动时）
     * @param restartServer true = 完成后重建并重启下载服务（reload 时会先停服）
     * @param notifier      需要接收聊天进度的玩家（执行重载命令的 OP）；null = 只写控制台
     * @return false 表示已有一次生成在进行中
     */
    private boolean startAsyncRegeneration(boolean force, boolean restartServer, Player notifier) {
        if (!regenerating.compareAndSet(false, true)) {
            return false;
        }
        Log.info((force ? "正在后台重新生成资源包" : "正在后台检查资源包（源包未变则直接复用，不重新合成）")
                + (restartServer ? "（下载服务暂停，完成后自动恢复）" : "")
                + "... 该过程不占用服务器主线程");

        // 进度回调在后台线程触发 -> 切回主线程再发聊天。
        manager.setProgressListener(msg -> notifyProgress(notifier, msg));
        long startedAt = System.currentTimeMillis();

        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                if (force) {
                    manager.reload();
                } else {
                    manager.load();
                }
            } catch (Throwable t) {
                Log.error("生成资源包失败: " + t);
                notifyProgress(notifier, "&c生成失败：" + t.getMessage() + "（详见控制台）");
            } finally {
                if (!isEnabled()) {
                    // 生成期间插件被停用：不能再调度任务（会抛 Plugin attempted to register task while disabled）。
                    Log.warn("插件已停用，放弃本次资源包生成后的服务恢复");
                    regenerating.set(false);
                    return;
                }
                // 回到主线程做 Bukkit 相关收尾。
                getServer().getScheduler().runTask(this, () -> {
                    try {
                        if (restartServer) {
                            if (server != null) {
                                server.stop();
                                server = null;
                            }
                            notifyProgress(notifier, "正在重启下载服务…");
                            server = buildServer(cfg);
                            if (!server.start()) {
                                Log.warn("启动内置下载服务失败，插件仍运行（玩家下载不可用）");
                                notifyProgress(notifier, "&c下载服务启动失败，详见控制台（端口可能被占用）");
                            }
                        }
                        if (server != null) {
                            server.setEntries(manager.getByPath());
                        }
                    } finally {
                        regenerating.set(false);
                        manager.setProgressListener(null);
                    }
                    double seconds = (System.currentTimeMillis() - startedAt) / 1000.0;
                    Log.success("资源包已就绪：可下发 " + servedCount() + " 个（源包 "
                            + (manager == null ? 0 : manager.getSourceCount()) + " 个）");
                    if (notifier != null) {
                        send(notifier, "&a资源包重载完成：可下发 &f" + servedCount() + "&a 个"
                                + "（源包 " + (manager == null ? 0 : manager.getSourceCount())
                                + " 个），用时 &f" + fmt(seconds) + "&a 秒。");
                    }
                    resendPendingPlayers();
                });
            }
        });
        return true;
    }

    /**
     * 把一条后台进度切回主线程后发给管理员（聊天）。后台线程不能直接碰 Bukkit API。
     */
    private void notifyProgress(Player notifier, String message) {
        if (notifier == null) {
            return;
        }
        if (!isEnabled()) {
            return;
        }
        Runnable send = () -> {
            if (notifier.isOnline()) {
                send(notifier, message);
            }
        };
        if (getServer().isPrimaryThread()) {
            send.run();
        } else {
            getServer().getScheduler().runTask(this, send);
        }
    }

    /** 带前缀给单个玩家发聊天消息（内部用，避免散落）。 */
    private void send(Player player, String message) {
        Messenger.send(player, message);
    }

    /** 是否正在后台生成资源包（期间不向玩家发包）。 */
    public boolean isRegenerating() {
        return regenerating.get();
    }

    /**
     * 玩家进服时的资源包处理：授权无效直接拒绝；生成期间只登记、不发包
     * （旧产物已删，发了也是死链）。命令 resend 与进服共用
     * {@link #pushPacksTo(Player)} 里的同一套登记逻辑。
     */
    public void handlePlayerJoin(Player player) {
        if (regenerating.get()) {
            pendingResend.add(player.getUniqueId());
            Messenger.send(player, "&e服务器正在生成资源包，完成后会自动下发给你。");
            Log.info("资源包生成中，玩家 " + player.getName() + " 加入：已登记，生成完成后自动补发");
            return;
        }
        // 互通服：基岩版玩家（Geyser / Floodgate）默认跳过，不发资源包。
        if (BedrockDetector.isBedrockPlayer(player.getUniqueId())) {
            Log.info("互通玩家 " + player.getName() + "（基岩版）跳过资源包下发");
            return;
        }
        if (manager.getEntries().isEmpty()) {
            Log.info("玩家 " + player.getName() + " 加入，但无资源包可下发");
            return;
        }
        if (cfg.getBoolean("packs.force", true)) {
            int n = pushPacksTo(player);
            Log.info("强制收包，已向玩家 " + player.getName() + " 直接下发 " + n + " 个资源包");
        } else {
            askPlayer(player);
        }
    }

    /** 生成完成后，给"生成期间进服/被 resend"的在线玩家自动补发。 */
    private void resendPendingPlayers() {
        if (pendingResend.isEmpty()) {
            return;
        }
        List<Player> targets = new ArrayList<>();
        for (UUID id : pendingResend) {
            Player p = getServer().getPlayer(id);
            if (p != null && p.isOnline()) {
                targets.add(p);
            }
        }
        pendingResend.clear();
        for (Player p : targets) {
            if (cfg.getBoolean("packs.force", true)) {
                int n = pushPacksTo(p);
                Messenger.send(p, "&a资源包已生成完毕，已下发 " + n + " 个资源包。");
                Log.info("已自动补发 " + n + " 个资源包给玩家 " + p.getName()
                        + "（生成期间进服或下载被中断）");
            } else {
                askPlayer(p);
            }
        }
    }

    /**
     * 读取插件内置图标（jar 内 {@code pack-icon.png}）并注入管理器。
     * 图标**写死在插件里**：不释放到数据目录、不读外部文件、不提供配置开关；
     * 换图标 = 替换 jar 内该资源后重新构建。
     */
    private void loadBuiltinIcon() {
        String resource = ResourcePackManager.BUILTIN_ICON_RESOURCE;
        try (InputStream in = getResource(resource)) {
            if (in == null) {
                Log.warn("jar 内缺少内置图标资源 " + resource + "，本次将沿用源包的 pack.png");
            } else {
                byte[] icon = in.readAllBytes();
                manager.setPackIcon(icon);
                Log.info("已加载插件内置资源包图标（" + icon.length + " 字节）");
            }
        } catch (IOException e) {
            Log.warn("读取内置图标失败: " + e.getMessage() + "，本次将沿用源包的 pack.png");
        }
        // 旧版本会把图标释放到数据目录；现在不再使用它了，存在就提示可删。
        File legacy = new File(getDataFolder(), resource);
        if (legacy.isFile()) {
            Log.info("检测到旧版释放的 " + resource + "（" + legacy.getAbsolutePath()
                    + "）：图标已改为插件内置，该文件不再被使用，可以删除");
        }
    }

    /**
     * 供 {@code reload} 命令调用：重读配置、按需重扫/重新合成资源包、重建并重启内置服务。
     * <p>
     * **重活全部放后台线程**（见 {@link #startAsyncRegeneration}）：主线程只做"判断是否需要重新合成 /
     * 停服 / 重读配置"，立即返回，不阻塞服务器 tick。监听器与命令引用的是同一 manager 实例（原地重扫），
     * 无需重建。
     *
     * @param notifier 执行命令的管理员（玩家）；进度会实时发到他的聊天栏。null = 只写控制台
     * @param force    true = 无条件重新合成；false = **源包与配置身份都没变就复用现有产物**
     * @return false = 已有一次生成在进行中，本次忽略
     */
    public boolean reloadResources(Player notifier, boolean force) {
        if (regenerating.get()) {
            return false;
        }
        reloadConfig();
        cfg = getConfig();

        int mbps = cfg.getInt("bandwidth.mbps", 20);
        allocator = new BandwidthAllocator(mbps);

        manager.setBaseUrl(baseUrl(cfg));
        manager.setConfig(cfg);

        // 关键：源包没改动、配置身份没变 → 不重新合成（只重读配置 + 重启下载服务）。
        boolean needRegen = force || manager.needsRegeneration();
        if (needRegen) {
            Log.info(force ? "管理员要求强制重新生成资源包" : "检测到源包或配置有变化，需要重新生成资源包");
        } else {
            Log.info("源包与配置均未变化：跳过重新合成，直接复用现有产物");
        }

        // 停下载服务会掐断正在进行的下载（客户端会判 FAILED_DOWNLOAD）：
        // 这些"还没下完"的玩家（packSummaries 里仍有记录）在完成后自动补发。
        if (!packSummaries.isEmpty()) {
            pendingResend.addAll(packSummaries.keySet());
            Log.info("有 " + packSummaries.size() + " 名玩家正在下载资源包，"
                    + "重载完成后会自动给他们补发");
        }

        // 先停下载服务：要重新生成时会删旧产物，若还有下载在读写会因文件占用删不掉。
        if (server != null) {
            server.stop();
            server = null;
        }

        if (!startAsyncRegeneration(needRegen, true, notifier)) {
            // 极端竞争：已有任务在跑（它不一定负责重建服务），这里自己把服务恢复起来。
            server = buildServer(cfg);
            server.setEntries(manager.getByPath());
            if (!server.start()) {
                Log.warn("重启内置下载服务失败，插件仍运行（玩家下载不可用）");
            }
            return false;
        }
        return true;
    }

    public ResourcePackManager getPackManager() {
        return manager;
    }

    public PackServer getPackServer() {
        return server;
    }

    /**
     * 生成"进服前面板"（Minecraft 资源包确认界面）的提示文字。
     * 描述的是客户端实际要下载的东西：合成/拆分后的包数、总大小、服务器总速度。
     */
    public String buildResourcePrompt() {
        int served = servedCount();
        int mergedFrom = manager == null ? 0 : manager.getMergedSourceCount();
        int oversized = manager == null ? 0 : manager.getOversizedCount();
        int limit = manager == null ? ResourcePackManager.DEFAULT_MAX_MB : manager.getMaxSizeMb();
        int mbps = cfg.getInt("bandwidth.mbps", 20);
        String lead = cfg.getString("packs.prompt", DEFAULT_PROMPT);

        StringBuilder sb = new StringBuilder(lead);
        sb.append("\n&e本服共需下载 &f").append(served).append("&e 个资源包，总大小 &f")
                .append(fmt(servedBytes() / 1024.0 / 1024.0)).append("&e MB。");
        if (mergedFrom > 1) {
            sb.append("\n&e已将 &f").append(mergedFrom).append("&e 个资源包合成一个下发。");
        }
        if (oversized > 0) {
            sb.append("\n&e另有 &f").append(oversized).append("&e 个资源包单个超过 &f").append(limit)
                    .append("&e MB，已单独分份下发。");
        }
        sb.append("\n&e服务器总速度 &f").append(mbps).append("&e Mbps。");
        return sb.toString();
    }

    /** 当前实际下发给客户端的资源包个数（合成/拆分后的结果）。 */
    private int servedCount() {
        return manager == null ? 0 : manager.getEntries().size();
    }

    /** 当前实际下发给客户端的所有包的总字节数（合成/拆分后的结果）。 */
    private long servedBytes() {
        long bytes = 0L;
        if (manager != null) {
            for (ResourcePackManager.ResourcePackEntry e : manager.getEntries()) {
                if (e.getFile() != null) {
                    bytes += e.getFile().length();
                }
            }
        }
        return bytes;
    }

    private PackServer buildServer(FileConfiguration c) {
        String ip = c.getString("server.ip", "127.0.0.1");
        int port = c.getInt("server.port", 8080);
        boolean https = c.getBoolean("server.https", false);
        String ks = c.getString("server.tls.keystore", "");
        String pass = c.getString("server.tls.password", "changeit");
        return new PackServer(ip, port, https, ks, pass, getDataFolder(), allocator,
                playerTracker, this);
    }

    private String baseUrl(FileConfiguration c) {
        boolean https = c.getBoolean("server.https", false);
        String ip = c.getString("server.ip", "127.0.0.1");
        int port = c.getInt("server.port", 8080);
        return (https ? "https://" : "http://") + ip + ":" + port + "/";
    }

    /**
     * 向玩家弹出"是否下载资源包"的聊天询问（可点击 [是]/[否]）。
     * 玩家未点选前，每隔 ask.repeat-seconds 秒重发，直到其选择。
     */
    public void askPlayer(Player player) {
        cancelAsk(player);
        sendAskMessage(player);
        int repeat = Math.max(1, cfg.getInt("ask.repeat-seconds", 7));
        UUID id = player.getUniqueId();

        // 重复询问：未点选前每 repeat 秒重发一次。
        BukkitTask repeatTask = getServer().getScheduler().runTaskTimer(this, () -> {
            if (player.isOnline() && askTasks.containsKey(id)) {
                sendAskMessage(player);
            }
        }, repeat * 20L, repeat * 20L);
        askTasks.put(id, repeatTask);

        // 超时兜底：ask.timeout-seconds（默认 60）内未点选，则自动下发并停止重发。0=不设超时。
        int timeout = Math.max(0, cfg.getInt("ask.timeout-seconds", 60));
        if (timeout > 0) {
            BukkitTask timeoutTask = getServer().getScheduler().runTaskLater(this, () -> {
                if (askTimeouts.remove(id) != null && player.isOnline()) {
                    cancelAsk(player);
                    int n = pushPacksTo(player);
                    Log.info("玩家 " + player.getName() + " 未在 " + timeout + " 秒内选择，自动下发 "
                            + n + " 个资源包");
                    if (n > 0) {
                        Messenger.send(player, "&a" + timeout + " 秒未选择，已自动下发 " + n + " 个资源包。");
                    }
                }
            }, timeout * 20L);
            askTimeouts.put(id, timeoutTask);
        }
    }

    private void sendAskMessage(Player player) {
        int served = servedCount();
        int mergedFrom = manager == null ? 0 : manager.getMergedSourceCount();
        int oversized = manager == null ? 0 : manager.getOversizedCount();
        StringBuilder note = new StringBuilder();
        if (mergedFrom > 1) {
            note.append("（").append(mergedFrom).append(" 个包合成为一个）");
        }
        if (oversized > 0) {
            note.append("（含 ").append(oversized).append(" 个超限包单独分份）");
        }
        int mbps = cfg.getInt("bandwidth.mbps", 20);
        Component form = Component.text()
                .append(Component.text("══════ 资源包 ══════\n", NamedTextColor.GOLD))
                .append(Component.text("是否下载本服务器的重要资源包？\n", NamedTextColor.YELLOW))
                .append(Component.text("共 " + served + " 个" + note + " · 总大小 "
                        + fmt(servedBytes() / 1024.0 / 1024.0)
                        + " MB · 服务器总速度 " + mbps + " Mbps\n", NamedTextColor.GRAY))
                .append(Component.text("[ 是 ]", NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.runCommand("/xiaohu_packanswer yes"))
                        .hoverEvent(HoverEvent.showText(Component.text("点击下载资源包", NamedTextColor.GREEN))))
                .append(Component.text("    ", NamedTextColor.GRAY))
                .append(Component.text("[ 否 ]", NamedTextColor.RED)
                        .clickEvent(ClickEvent.runCommand("/xiaohu_packanswer no"))
                        .hoverEvent(HoverEvent.showText(Component.text("点击拒绝", NamedTextColor.RED))))
                .build();
        player.sendMessage(form);
    }

    /** 取消某个玩家的未决询问（停止重发 + 取消超时）。 */
    public void cancelAsk(Player player) {
        BukkitTask t = askTasks.remove(player.getUniqueId());
        if (t != null) {
            t.cancel();
        }
        BukkitTask tt = askTimeouts.remove(player.getUniqueId());
        if (tt != null) {
            tt.cancel();
        }
    }

    /** 当前是否有未决的资源包询问（重复点击[是]/[否]时据此判"已过期/已处理"）。 */
    public boolean isAskPending(Player player) {
        UUID id = player.getUniqueId();
        return askTasks.containsKey(id) || askTimeouts.containsKey(id);
    }

    /** 玩家选择[是]：下发资源包（并停止重复询问）。若询问已过期则提示，不重复执行。 */
    public void handleYes(Player player) {
        if (!isAskPending(player)) {
            Messenger.send(player, "&e该资源包询问已过期或已处理，无需重复操作。");
            return;
        }
        cancelAsk(player);
        int n = pushPacksTo(player);
        if (n > 0) {
            Messenger.send(player, "&a已开始下载 " + n + " 个资源包。");
        } else {
            Messenger.send(player, "&c没有可下发的资源包。");
        }
    }

    /** 玩家选择[否]：按 force 决定踢出（true）或跳过（false）。若询问已过期则提示，不重复执行。 */
    public void handleNo(Player player) {
        if (!isAskPending(player)) {
            Messenger.send(player, "&e该资源包询问已过期或已处理，无需重复操作。");
            return;
        }
        cancelAsk(player);
        boolean force = cfg.getBoolean("packs.force", true);
        if (force) {
            Log.warn("玩家 " + player.getName() + " 拒绝资源包，强制收包，踢出");
            player.kickPlayer(org.bukkit.ChatColor.translateAlternateColorCodes('&',
                    "&c你拒绝了服务器要求的资源包。"));
        } else {
            Messenger.send(player, "&e已跳过资源包（未强制）。");
        }
    }

    /**
     * 向单个在线玩家下发当前所有资源包（基岩版跳过）；返回实际下发数量。
     * 命令 resend 共用这一份逻辑，避免行为不一致。
     */
    public int pushPacksTo(Player player) {
        if (manager == null || player == null) {
            return 0;
        }
        if (regenerating.get()) {
            // 生成期间旧产物已删除，发了也是死链：登记下来，等生成完成后自动补发。
            pendingResend.add(player.getUniqueId());
            Messenger.send(player, "&e资源包正在生成中，稍后会重新下发。");
            return 0;
        }
        if (BedrockDetector.isBedrockPlayer(player.getUniqueId())) {
            Log.info("互通玩家 " + player.getName() + "（基岩版）跳过资源包下发");
            return 0;
        }
        String prompt = buildResourcePrompt();
        boolean force = cfg.getBoolean("packs.force", true);
        player.removeResourcePacks();
        int n = 0;
        for (ResourcePackManager.ResourcePackEntry e : manager.getEntries()) {
            player.addResourcePack(e.getUuid(), e.getUrl(), e.getHash(),
                    Messenger.colorize(prompt), force);
            n++;
        }
        // 记录本次下发的包数，用于下载完成后汇总（成功/失败）。
        packSummaries.put(player.getUniqueId(), new PackSummary(n));
        return n;
    }

    /**
     * 记录某玩家的资源包下载状态。等全部包都到达终态后，向玩家发送汇总提示。
     */
    public void recordPackStatus(Player player, UUID packId, PlayerResourcePackStatusEvent.Status status) {
        PackSummary sum = packSummaries.get(player.getUniqueId());
        if (sum == null) {
            return;
        }
        switch (status) {
            case SUCCESSFULLY_LOADED, DOWNLOADED -> {
                if (sum.resolved.add(packId)) {
                    sum.success++;
                }
            }
            case FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD -> {
                if (sum.resolved.add(packId)) {
                    sum.fail++;
                }
            }
            case DECLINED, DISCARDED -> {
                // 玩家拒绝 / 包被丢弃：视为已处理，但不算成功/失败。
                sum.resolved.add(packId);
            }
            default -> {
                // ACCEPTED：客户端已接受、但仍在下载，不算终态；等 DOWNLOADED/SUCCESSFULLY_LOADED/FAILED。
            }
        }
        if (sum.resolved.size() >= sum.total) {
            String msg;
            if (sum.fail > 0) {
                msg = "&e当前总资源包：" + sum.total + "个，有 &a" + sum.success
                        + "&a个成功 / &c" + sum.fail + "&c个下载失败！&e请联系管理员重新给你发失败的资源包！";
            } else {
                msg = "&e当前总资源包：" + sum.total + "个，&a已全部下载成功！";
            }
            Log.info("玩家 " + player.getName() + " 资源包下载汇总：共 " + sum.total
                    + " 个，成功 " + sum.success + "，失败 " + sum.fail);
            Messenger.send(player, msg);
            packSummaries.remove(player.getUniqueId());
        }
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    private void printBanner() {
        int served = servedCount();
        String mergeState = manager == null ? "未知"
                : (regenerating.get() ? "正在后台生成…（完成后自动恢复下载服务）"
                        : (manager.isMergeEnabled()
                                ? "开（源包 " + manager.getSourceCount() + " 个：合成 "
                                        + manager.getMergedSourceCount() + " 个为一个；超限单独 "
                                        + manager.getOversizedCount() + " 个）-> 下发 " + served
                                        + " 个，单包上限 " + manager.getMaxSizeMb() + " MB"
                                : "关（逐包下发 " + served + " 个）"));
        var console = getServer().getConsoleSender();
        console.sendMessage(Messenger.colorize("&e===================================================="));
        console.sendMessage(Messenger.gradient("xiaohu_package")
                .append(Messenger.of("&e  材质包分发插件")));
        console.sendMessage(Messenger.colorize("&e  全局限速 / HTTP(S) 服务 / 强制收包"));
        console.sendMessage(Messenger.colorize("&e  资源包合成: &f" + mergeState));
        console.sendMessage(Messenger.colorize("&e  合成包名: &f"
                + cfg.getString("packs.name", ResourcePackMerger.DEFAULT_PACK_NAME) + "&e（&f"
                + cfg.getString("packs.name-color", ResourcePackMerger.DEFAULT_PACK_COLOR)
                + "&e）  图标: &f插件内置"));
        console.sendMessage(Messenger.colorize("&e  合成目录: &f" + new File(getDataFolder(),
                cfg.getString("packs.merge-folder", ResourcePackManager.DEFAULT_MERGE_FOLDER))
                .getAbsolutePath()));
        console.sendMessage(Messenger.colorize("&e  版本 " + VERSION + "   API Paper 26.2"));
        console.sendMessage(Messenger.colorize("&e===================================================="));
    }

    /** 单个玩家的资源包下载汇总（总数 / 成功 / 失败 / 已到达终态的包）。 */
    static final class PackSummary {
        final int total;
        int success;
        int fail;
        final Set<UUID> resolved = new HashSet<>();

        PackSummary(int total) {
            this.total = total;
        }
    }
}
