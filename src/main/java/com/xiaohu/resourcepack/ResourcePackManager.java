package com.xiaohu.resourcepack;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * 资源包管理器：扫描 {@code packs/*.zip}，按配置把它们合成"一个"资源包（默认），
 * 计算 SHA-1 与稳定 UUID，组装 URL 与 {@link ResourcePackEntry}，
 * 供 {@link PackServer} 查询、{@link PlayerPackListener} 发包。
 * <p>
 * 合成产物集中放在"合成目录"（默认 {@code merged/}，由插件管理）：
 * 未超单包上限时是其中的一个 zip；超限则用 {@link ResourcePackSplitter}
 * 拆成 {@code merged/parts/*.zip} 多份分别下发。启动时按源包指纹复用产物，
 * 热加载（{@code reload}）时无条件重新合成。
 * <p>
 * {@code reload()} 在同一实例上重算（保留引用，让监听器/命令仍然读取到最新条目）。
 */
public final class ResourcePackManager {

    /** 客户端对单个服务器资源包的大小上限（250 MB），超过将被客户端拒载。 */
    private static final long CLIENT_MAX_BYTES = 250_000_000L;

    /**
     * 可配置上限的安全封顶（MB）。拆分是按"条目未压缩大小之和"装箱的，成品 zip 会因
     * zip 头/中央目录开销略大于该和（实测约 +0.1%~0.8%），故配置上限封顶 248 MB，
     * 保证任何分片都不会越过客户端 250 MB 硬上限。
     */
    private static final int SAFE_MAX_MB = 248;

    /** 单包上限默认值（MB）：留余量，低于客户端 250 MB 硬上限。 */
    public static final int DEFAULT_MAX_MB = 245;

    /** 合成目录默认名（相对插件数据目录）。 */
    public static final String DEFAULT_MERGE_FOLDER = "merged";

    /** jar 内内置图标资源名（写死在插件里，不释放到数据目录、也不可配置）。 */
    public static final String BUILTIN_ICON_RESOURCE = "pack-icon.png";

    private final File dataFolder;
    private FileConfiguration cfg;
    private String baseUrl;

    private volatile List<ResourcePackEntry> entries = new ArrayList<>();
    private volatile Map<String, ResourcePackEntry> byPath = new ConcurrentHashMap<>();

    private volatile int sourceCount;
    private volatile int oversizedCount;
    private volatile int mergedSourceCount;
    private volatile boolean mergeEnabled = true;
    private volatile int maxSizeMb = DEFAULT_MAX_MB;

    /** 插件内置图标（jar 内资源字节，写死在插件里）；null = 未注入，届时沿用源包的 pack.png。 */
    private volatile byte[] packIcon;

    /** 进度回调（由命令触发的重载注入，用于给执行命令的管理员推送聊天进度）。 */
    private volatile ProgressListener progress;

    /**
     * 生成进度回调。**在后台线程被调用**，实现方必须自己切回主线程再碰 Bukkit API。
     * 只在"管理员手动重载"时注入，开服自动生成时为 null（不打扰）。
     */
    public interface ProgressListener {
        void onProgress(String message);
    }

    /** 注入/清除进度回调（null = 不推送）。 */
    public void setProgressListener(ProgressListener listener) {
        this.progress = listener;
    }

    private void report(String message) {
        ProgressListener p = progress;
        if (p == null) {
            return;
        }
        try {
            p.onProgress(message);
        } catch (Throwable ignored) {
            // 进度推送失败绝不能影响生成流程。
        }
    }

    public ResourcePackManager(File dataFolder, FileConfiguration cfg, String baseUrl) {
        this.dataFolder = dataFolder;
        this.cfg = cfg;
        this.baseUrl = baseUrl;
    }

    public void setConfig(FileConfiguration cfg) {
        this.cfg = cfg;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /**
     * 注入插件内置图标（jar 内 {@code pack-icon.png} 的字节）。图标写死在插件里：
     * 不释放到数据目录、不读文件、不提供配置开关；未注入时才沿用源包的 {@code pack.png}。
     */
    public void setPackIcon(byte[] icon) {
        this.packIcon = icon;
    }

    /** 插件启动时的加载：合成产物指纹未变则复用，加快启动。 */
    public void load() {
        recompute(false);
    }

    /** 热加载（reload 命令）：无条件重新合成。 */
    public void reload() {
        recompute(true);
    }

    /**
     * 是否有必要重新合成：只做「扫描源包 + 比对指纹」，**不生成任何文件**。
     * 供 {@code /xiaohu_package reload} 在决定要不要重新合成前快速判断
     * （源包没动、配置身份没变 → 直接复用现有产物，省掉几百 MB 的重写）。
     * <p>
     * 指纹 = 源包（文件名 + 大小 + 修改时间） + 合成身份（名称/颜色/格式号/图标内容）。
     * 因此：增删改名源包、改动任一源包、改配置里的名称/颜色/格式号/图标 —— 都会判定为需要重新合成。
     */
    public boolean needsRegeneration() {
        String folder = cfg.getString("packs.folder", "packs");
        File dir = new File(dataFolder, folder);
        List<File> sources = listZipFiles(dir);
        if (!cfg.getBoolean("packs.merge", true)) {
            return false;   // 未开合成：没有合成产物，源包自身分片由拆分器按 mtime/size/上限自动失效
        }
        long limit = resolveMaxMb() * 1_000_000L;
        List<File> mergeable = new ArrayList<>();
        for (File f : sources) {
            if (f.length() <= limit) {
                mergeable.add(f);
            }
        }
        if (mergeable.size() < 2) {
            return false;   // 不足两个包不会合成
        }
        String mergeName = cfg.getString("packs.merge-name", ResourcePackMerger.DEFAULT_NAME);
        return !ResourcePackMerger.isReusable(mergeable, mergeDir(), mergeName, mergeOptions());
    }

    private void recompute(boolean forceMerge) {
        List<ResourcePackEntry> newEntries = new ArrayList<>();
        Map<String, ResourcePackEntry> newByPath = new ConcurrentHashMap<>();

        report("开始扫描源包目录…");
        String folder = cfg.getString("packs.folder", "packs");
        File dir = new File(dataFolder, folder);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.warn("无法创建资源包目录 " + dir.getAbsolutePath());
        }

        List<File> sources = listZipFiles(dir);
        this.sourceCount = sources.size();
        this.mergeEnabled = cfg.getBoolean("packs.merge", true);
        this.maxSizeMb = resolveMaxMb();
        long limit = maxSizeMb * 1_000_000L;

        if (sources.isEmpty()) {
            Log.warn("在目录 " + dir.getAbsolutePath() + " 中没有找到任何 .zip 资源包");
            // 没有源包 -> 上一次生成的合成产物不可能再被下发，直接清掉。
            deleteGenerated(mergeDir());
            this.oversizedCount = 0;
            this.mergedSourceCount = 0;
            publish(newEntries, newByPath);
            report("源包目录为空，已清空上一次生成的产物");
            return;
        }

        // 路由：单个就超过上限的源包不参与合成，各自单独下发（并各自拆分）。
        List<File> oversized = new ArrayList<>();
        List<File> mergeable = new ArrayList<>();
        long sourceMb = 0L;
        for (File f : sources) {
            sourceMb += f.length();
            if (f.length() > limit) {
                oversized.add(f);
            } else {
                mergeable.add(f);
            }
        }
        this.oversizedCount = oversized.size();
        this.mergedSourceCount = 0;
        report("共 " + sources.size() + " 个源包 / " + mb(sourceMb) + " MB："
                + mergeable.size() + " 个参与合成，" + oversized.size() + " 个超限单独下发（单包上限 "
                + maxSizeMb + " MB）");

        // 本轮实际下发的磁盘文件（合成产物 / 分片），用于清理目录里的旧产物。
        List<File> products = new ArrayList<>();
        boolean usedMerged = false;

        if (!mergeEnabled) {
            Log.info("合成模式已关闭（packs.merge=false），按源包逐个下发");
            report("合成模式已关闭，按源包逐个下发");
            for (File f : sources) {
                buildOne(f, splitCacheDir(f), newEntries, newByPath, products);
            }
            // 合成模式关闭时合成产物不再被下发，直接清掉，避免旧产物越积越多。
            deleteGenerated(mergeDir());
        } else {
            File outDir = mergeDir();
            String mergeName = cfg.getString("packs.merge-name", ResourcePackMerger.DEFAULT_NAME);
            ResourcePackMerger.MergeOptions options = mergeOptions();
            boolean willMerge = mergeable.size() >= 2;
            // 要重新生成合成包时，先把上一次的产物整体删掉（含旧分片），再写新的：
            // 目录里始终只有最新一份，也不会出现"旧产物 + 新产物"同时占盘（几百 MB 场景很关键）。
            if (willMerge && (forceMerge || !ResourcePackMerger.isReusable(mergeable, outDir, mergeName, options))) {
                String removed = deleteGenerated(outDir);
                if (removed != null) {
                    report("已删除上一次生成的产物：" + removed);
                }
            }

            // 1) 超限源包：不参与合成，单独下发（buildOne 内部会按上限把它自己拆成多份）。
            for (File f : oversized) {
                Log.info("源包 " + f.getName() + "（" + mb(f.length()) + " MB）超过单包上限 " + maxSizeMb
                        + " MB：不参与合成，单独下发");
                buildOne(f, splitCacheDir(f), newEntries, newByPath, products);
            }

            // 2) 未超限的源包：合成成一个再下发。
            if (willMerge) {
                try {
                    long mergeBytes = 0L;
                    for (File f : mergeable) {
                        mergeBytes += f.length();
                    }
                    report("正在合成 " + mergeable.size() + " 个源包（共 " + mb(mergeBytes) + " MB）…");
                    File merged = ResourcePackMerger.ensureMerged(mergeable, outDir, mergeName, forceMerge, options);
                    usedMerged = true;
                    this.mergedSourceCount = mergeable.size();
                    report("合成完成：产物 " + mb(merged.length()) + " MB");
                    // 合成产物本身也要留：超限被拆分时它是分片的缓存基，留着下次启动才能按指纹复用。
                    products.add(merged);
                    // 合成产物的分片统一放在 merged/parts/ 下，便于集中管理。
                    buildOne(merged, new File(outDir, "parts"), newEntries, newByPath, products);
                } catch (Exception ex) {
                    Log.error("合成 " + mergeable.size() + " 个资源包失败: " + ex.getMessage()
                            + "，这些包本次改为逐个下发");
                    report("合成失败：" + ex.getMessage() + "，改为逐个下发");
                    for (File f : mergeable) {
                        buildOne(f, splitCacheDir(f), newEntries, newByPath, products);
                    }
                }
            } else if (mergeable.size() == 1) {
                Log.info("只有 1 个源包未超限（" + mergeable.get(0).getName() + "），无需合成，直接下发");
                report("只有 1 个包未超限，无需合成，直接下发");
                buildOne(mergeable.get(0), splitCacheDir(mergeable.get(0)), newEntries, newByPath, products);
            } else {
                Log.warn("所有源包都超过单包上限 " + maxSizeMb + " MB，没有可合成的包（已各自单独下发）");
                report("所有源包都超过单包上限，没有可合成的包（已各自单独下发）");
            }

            // 兜底：清掉合成目录里本轮未使用的残留（改名、合成失败回退等情况）。
            cleanupStale(outDir, usedMerged ? products : List.of());
        }

        // 清掉 cache/ 下本轮不再使用的拆分缓存目录（源包被改名/删除后不留孤儿目录）。
        cleanupSplitCache(products);

        publish(newEntries, newByPath);
        StringBuilder summary = new StringBuilder("可下发资源包 " + newEntries.size() + " 个（源包 "
                + sources.size() + " 个");
        if (mergeEnabled && usedMerged) {
            summary.append("，其中 ").append(mergedSourceCount).append(" 个已合成一个");
        }
        if (mergeEnabled && oversizedCount > 0) {
            summary.append("，").append(oversizedCount).append(" 个超限单独下发");
        }
        summary.append("）");
        Log.success(summary.toString());
        report("生成完成：" + summary);
    }

    private void publish(List<ResourcePackEntry> newEntries, Map<String, ResourcePackEntry> newByPath) {
        // 整体替换引用，而不是原地 clear()+putAll()：PackServer 持有这个 map，
        // 原地清空会出现"已清空但尚未填充"的空窗口，让正在下载的请求 404。
        this.entries = newEntries;
        this.byPath = newByPath;
    }

    /**
     * 单个文件 -> 条目；超过单包上限则先拆成多份，每份各成一个条目。
     *
     * @param splitDir 拆分缓存目录（合成产物用 {@code merged/parts}，源包用 {@code cache/<名>}）
     * @param products 收集本轮实际下发的文件，供清理旧产物
     */
    private void buildOne(File f, File splitDir, List<ResourcePackEntry> out,
                          Map<String, ResourcePackEntry> byPath, List<File> products) {
        long limit = maxSizeMb * 1_000_000L;
        try {
            if (f.length() > limit) {
                Log.warn("资源包 " + f.getName() + " 约 " + mb(f.length()) + " MB，超过单包上限 "
                        + maxSizeMb + " MB，自动拆分成多份分别下发...");
                report("正在拆分 " + f.getName() + "（" + mb(f.length()) + " MB，超过上限 "
                        + maxSizeMb + " MB）…");
                List<File> parts = ResourcePackSplitter.ensureSplit(f, splitDir, limit);
                for (File part : parts) {
                    ResourcePackEntry e = buildEntry(part);
                    out.add(e);
                    byPath.put(e.getPath(), e);
                    products.add(part);
                }
                Log.success("资源包 " + f.getName() + " 已拆分为 " + parts.size() + " 份");
                report(f.getName() + " 已拆分为 " + parts.size() + " 份");
            } else {
                report("计算哈希并登记 " + f.getName() + "（" + mb(f.length()) + " MB）…");
                ResourcePackEntry e = buildEntry(f);
                out.add(e);
                byPath.put(e.getPath(), e);
                products.add(f);
            }
        } catch (Exception ex) {
            Log.error("处理资源包 " + f.getName() + " 失败: " + ex.getMessage());
            report("处理 " + f.getName() + " 失败：" + ex.getMessage());
        }
    }

    /**
     * 列出目录下全部 {@code *.zip}，按文件名排序。
     * 顺序即合成优先级：靠后的源包覆盖靠前的同名文件。
     */
    private static List<File> listZipFiles(File dir) {
        File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".zip"));
        if (files == null) {
            return new ArrayList<>();
        }
        List<File> list = new ArrayList<>(Arrays.asList(files));
        list.sort(Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(File::getName));
        return list;
    }

    /** 源包的拆分缓存目录（合成模式下用不到，仅逐包下发时使用）。 */
    private File splitCacheDir(File source) {
        return new File(dataFolder, "cache/" + baseName(source));
    }

    /** 合成目录（插件管理：合成产物与拆分分片都放这里）。 */
    private File mergeDir() {
        String folder = cfg.getString("packs.merge-folder", DEFAULT_MERGE_FOLDER);
        if (folder == null || folder.isBlank()) {
            folder = DEFAULT_MERGE_FOLDER;
        }
        return new File(dataFolder, folder);
    }

    /**
     * 从配置读取合成包的内置身份：客户端列表显示的名称、名称颜色、资源包格式号与兼容区间。
     * <p>
     * 图标由插件写死（见 {@link #setPackIcon(byte[])}，取自 jar 内资源），不在这里读文件、
     * 也不可通过配置更换。这些值都参与缓存指纹，改了配置就会重新生成，不会继续用旧产物。
     */
    private ResourcePackMerger.MergeOptions mergeOptions() {
        String name = cfg.getString("packs.name", ResourcePackMerger.DEFAULT_PACK_NAME);
        String color = cfg.getString("packs.name-color", ResourcePackMerger.DEFAULT_PACK_COLOR);
        int packFormat = cfg.getInt("packs.pack-format", ResourcePackMerger.DEFAULT_PACK_FORMAT);
        int formatMin = cfg.getInt("packs.min-format", ResourcePackMerger.DEFAULT_FORMAT_MIN);
        return new ResourcePackMerger.MergeOptions(name, color, packIcon, packFormat, formatMin);
    }

    /** 解析并钳制单包上限（MB）：为客户端 250 MB 硬上限留出 zip 开销余量。 */
    private int resolveMaxMb() {
        int mb = cfg.getInt("packs.max-size-mb", DEFAULT_MAX_MB);
        if (mb <= 0) {
            mb = DEFAULT_MAX_MB;
        }
        if (mb > SAFE_MAX_MB) {
            Log.warn("packs.max-size-mb=" + mb + " MB 过大（客户端硬上限 "
                    + (CLIENT_MAX_BYTES / 1_000_000L) + " MB，拆分按未压缩大小装箱会有少量开销），已按 "
                    + SAFE_MAX_MB + " MB 处理");
            mb = SAFE_MAX_MB;
        }
        return mb;
    }

    /**
     * 清理合成目录里本轮未使用的产物，保持该目录"只放当前正在下发的东西"。
     * 以 {@code .} 开头的缓存文件（{@code .merge.meta} / {@code .split.meta}）一律保留。
     */
    private static void cleanupStale(File dir, List<File> keep) {
        File[] fs = dir.listFiles();
        if (fs == null) {
            return;
        }
        for (File f : fs) {
            if (f.isFile() && f.getName().startsWith(".")) {
                continue;   // 缓存用 meta（.merge.meta / .split.meta）一律保留
            }
            if (keep.contains(f)) {
                continue;
            }
            if (f.isDirectory()) {
                if (containsAnyInside(f, keep)) {
                    // 目录里还有本轮在用的分片：递归进去只清多余文件，保住 .split.meta 缓存。
                    cleanupStale(f, keep);
                } else {
                    deleteRecursively(f);
                }
            } else if (!f.delete()) {
                Log.warn("无法删除旧的合成产物 " + f.getName() + "（可能仍被下载占用），下次热加载会重试");
            }
        }
    }

    private static boolean containsAnyInside(File dir, List<File> keep) {
        String prefix = dir.getAbsolutePath() + File.separator;
        for (File k : keep) {
            if (k.getAbsolutePath().startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 清理 {@code cache/} 下本轮不再使用的拆分缓存目录：超限源包被改名/删除后不会留下孤儿目录
     * （这些目录可能有几百 MB，不能只靠"下次拆分时覆盖"）。
     */
    private void cleanupSplitCache(List<File> keep) {
        File cache = new File(dataFolder, "cache");
        File[] fs = cache.listFiles();
        if (fs == null) {
            return;
        }
        for (File f : fs) {
            if (keep.contains(f) || (f.isDirectory() && containsAnyInside(f, keep))) {
                continue;
            }
            if (f.isDirectory()) {
                deleteRecursively(f);
            }
        }
    }

    /**
     * 整体清空合成目录：上一次生成的合成包、分片、残留临时文件与缓存 meta **全部删除**。
     * <p>
     * 重新生成前调用，保证目录里"只有最新那一份"，同时避免旧产物与新产物同时占盘
     * （几百 MB 的包，磁盘峰值差一倍）。该目录由插件独占管理，故不做保留。
     *
     * @return 删除摘要（如 {@code 3 项，共 379.0 MB}）；没有任何东西可删时返回 null
     */
    private static String deleteGenerated(File dir) {
        File[] fs = dir.listFiles();
        if (fs == null || fs.length == 0) {
            return null;
        }
        long bytes = 0L;
        int n = 0;
        for (File f : fs) {
            long size = sizeOf(f);
            if (deleteRecursively(f)) {
                bytes += size;
                n++;
            }
        }
        if (n > 0) {
            String summary = n + " 项，共 " + mb(bytes) + " MB";
            Log.info("已删除上一次生成的合成产物：" + summary);
            return summary;
        }
        return null;
    }

    /** 递归统计大小（用于删除日志）。 */
    private static long sizeOf(File f) {
        if (f == null || !f.exists()) {
            return 0L;
        }
        if (f.isFile()) {
            return f.length();
        }
        long sum = 0L;
        File[] ch = f.listFiles();
        if (ch != null) {
            for (File c : ch) {
                sum += sizeOf(c);
            }
        }
        return sum;
    }

    /** 递归删除；返回是否全部删除成功（失败会打警告，通常是文件仍被下载占用）。 */
    private static boolean deleteRecursively(File f) {
        if (f == null || !f.exists()) {
            return true;
        }
        boolean ok = true;
        File[] ch = f.listFiles();
        if (ch != null) {
            for (File c : ch) {
                ok &= deleteRecursively(c);
            }
        }
        if (!f.delete()) {
            Log.warn("无法删除 " + f.getAbsolutePath() + "（可能仍被下载占用），下次重新生成时会重试");
            ok = false;
        }
        return ok;
    }

    /** 去掉 .zip 后缀的文件名。 */
    private static String baseName(File f) {
        return f.getName().replaceAll("(?i)\\.zip$", "");
    }

    /** 计算当前资源包集合的稳定指纹：任一包的文件名或 SHA-1 变化则指纹变化。 */
    public String getPackFingerprint() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            for (ResourcePackEntry e : entries) {
                md.update(e.getFileName().getBytes(StandardCharsets.UTF_8));
                md.update((byte) ':');
                if (e.getHash() != null) {
                    md.update(e.getHash());
                }
            }
            return toHex(md.digest());
        } catch (Exception ex) {
            return "unknown";
        }
    }

    private static String toHex(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (byte b : d) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** 将字节数格式化为一位小数的 MB 字符串。 */
    private static String mb(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.1f", bytes / 1024.0 / 1024.0);
    }

    private ResourcePackEntry buildEntry(File file) throws Exception {
        String fileName = file.getName();
        byte[] hash = sha1(file);
        UUID uuid = UUID.nameUUIDFromBytes(fileName.getBytes(StandardCharsets.UTF_8));
        String url = baseUrl + java.net.URLEncoder.encode(fileName, StandardCharsets.UTF_8.toString());
        return new ResourcePackEntry(uuid, fileName, file, url, hash);
    }

    private byte[] sha1(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        return md.digest();
    }

    public List<ResourcePackEntry> getEntries() {
        return entries;
    }

    public Map<String, ResourcePackEntry> getByPath() {
        return byPath;
    }

    /** 本次扫描到的源包（{@code packs/*.zip}）数量。 */
    public int getSourceCount() {
        return sourceCount;
    }

    /** 其中"单个就超过单包上限、不参与合成、单独下发"的源包数量。 */
    public int getOversizedCount() {
        return oversizedCount;
    }

    /** 实际被合成进同一个包的源包数量（未发生合成时为 0）。 */
    public int getMergedSourceCount() {
        return mergedSourceCount;
    }

    /** 是否处于"全部合成一个"模式。 */
    public boolean isMergeEnabled() {
        return mergeEnabled;
    }

    /** 当前生效的单包上限（MB）。 */
    public int getMaxSizeMb() {
        return maxSizeMb;
    }

    public ResourcePackEntry findByPath(String path) {
        return byPath.get(path);
    }

    public ResourcePackEntry findByUuid(UUID uuid) {
        for (ResourcePackEntry e : entries) {
            if (e.getUuid().equals(uuid)) {
                return e;
            }
        }
        return null;
    }

    /**
     * 单个资源包的描述数据（uuid / 文件名 / 磁盘文件 / url / sha1）。
     */
    public static class ResourcePackEntry {
        private final UUID uuid;
        private final String fileName;
        private final File file;
        private final String url;
        private final byte[] hash;

        public ResourcePackEntry(UUID uuid, String fileName, File file, String url, byte[] hash) {
            this.uuid = uuid;
            this.fileName = fileName;
            this.file = file;
            this.url = url;
            this.hash = hash;
        }

        public UUID getUuid() {
            return uuid;
        }

        public String getFileName() {
            return fileName;
        }

        public File getFile() {
            return file;
        }

        public String getUrl() {
            return url;
        }

        public byte[] getHash() {
            return hash;
        }

        /** URL path 键（带前导 {@code /}，与 PackServer 解码后的请求路径一致）。 */
        public String getPath() {
            return "/" + fileName;
        }
    }
}
