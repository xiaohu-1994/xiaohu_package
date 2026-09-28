package com.xiaohu.resourcepack;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 把 {@code packs/} 下的多个资源包 zip 合成"一个"资源包。
 * <p>
 * 合成语义与客户端叠加多个资源包一致：{@code sources} 必须已按优先级升序排列，
 * 同名文件由靠后的源包覆盖靠前者（覆盖处数会记入日志），因此不会往同一个 zip 里
 * 写入重复条目（重复条目在客户端解包时行为未定义）。
 * <p>
 * 合成后套用"插件内置身份"（见 {@link MergeOptions}）：
 * <ul>
 *   <li>{@code pack.mcmeta} **由插件完整生成**：名称+颜色（description）、资源包格式号与兼容区间
 *       （{@code pack_format} / {@code supported_formats} / {@code min_format} / {@code max_format}）
 *       全部用插件内置的标准值，**不沿用源包的版本声明**；源包的 {@code filter} 块会保留（它是"屏蔽其它包文件"
 *       的正则列表，丢掉会改变行为）；</li>
 *   <li>{@code pack.png} 替换为配置的图标（源包没有 {@code pack.png} 时也会补上）。</li>
 * </ul>
 * 产物写在"合成目录"（默认 {@code merged/}）里，由 {@code .merge.meta} 记录"源包 + 自定义身份"的指纹：
 * 指纹未变可复用（启动加速），热加载时由调用方传 {@code force=true} 强制重新合成。
 * 写入采用 {@code .zip.tmp} 中转再原子替换，避免客户端读到半成品。
 */
public final class ResourcePackMerger {

    /** 默认合成产物文件名（不含 {@code .zip}）；决定下载 URL。 */
    public static final String DEFAULT_NAME = "xiaohu_all_in_one";

    /** 默认资源包名称（客户端资源包列表里显示的 description）。 */
    public static final String DEFAULT_PACK_NAME = "小胡自定义世界材质包";

    /** 默认名称颜色（JSON 颜色名或 #RRGGBB）。 */
    public static final String DEFAULT_PACK_COLOR = "yellow";

    /**
     * 默认资源包格式号 = 26.2 的权威值。
     * 来源：本机真实 Paper/vanilla 26.2 服务端 jar 内 {@code version.json} 的
     * {@code pack_version.resource_major}（实读 = 88，resource_minor = 0）。
     */
    public static final int DEFAULT_PACK_FORMAT = 88;

    /**
     * 默认兼容区间下界（写入 {@code min_format}）。
     * 用户指定 80：覆盖 26.1~26.2，同时向后兼容更早的客户端，不会提示"不兼容的旧资源包"。
     */
    public static final int DEFAULT_FORMAT_MIN = 80;

    /** 缓存指纹格式版本：调整合成规则时递增，旧缓存自动失效。 */
    private static final int META_VERSION = 5;

    private static final Pattern PACK_FORMAT = Pattern.compile("\"pack_format\"\\s*:\\s*(\\d+)");

    private ResourcePackMerger() {
        // util class，禁止实例化
    }

    /**
     * 合成包的"内置身份"（由配置决定，随指纹参与缓存失效判定）。
     *
     * @param displayName 客户端列表显示的名称（写入 {@code pack.mcmeta} 的 description）
     * @param color       名称颜色，JSON 颜色名（yellow/gold/...）或 {@code #RRGGBB}
     * @param iconPng     图标 PNG 字节（{@code pack.png}）；null = 沿用源包图标
     * @param packFormat  资源包格式号（写入 {@code pack_format} 与 {@code max_format}），26.2 = {@link #DEFAULT_PACK_FORMAT}
     * @param formatMin   兼容区间下界（写入 {@code min_format}），默认 {@link #DEFAULT_FORMAT_MIN}
     */
    public record MergeOptions(String displayName, String color, byte[] iconPng, int packFormat,
                               int formatMin) {

        /** 全默认身份：默认名称 + 黄色 + 不覆盖图标 + 26.2 格式号 + 26.x 兼容下界。 */
        public static MergeOptions defaults() {
            return new MergeOptions(DEFAULT_PACK_NAME, DEFAULT_PACK_COLOR, null, DEFAULT_PACK_FORMAT,
                    DEFAULT_FORMAT_MIN);
        }
    }

    /**
     * 合成产物是否可以直接复用（源包与身份信息都没变 + 产物仍在）。
     * 供调用方在"重新生成"前判断：要重新生成时，应当先把上一次的产物删掉再写新的。
     */
    public static boolean isReusable(List<File> sources, File outDir, String fileName, MergeOptions options) {
        MergeOptions opts = normalize(options);
        File out = new File(outDir, baseName(fileName) + ".zip");
        try {
            return cacheValid(out, new File(outDir, ".merge.meta"), fingerprint(sources, opts));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 确保 {@code sources} 合成一个 zip 并返回产物文件。
     *
     * @param sources  源包，需已按优先级升序（靠后者覆盖靠前者）
     * @param outDir   合成目录（插件管理，默认 {@code merged/}）
     * @param fileName 产物文件名（不含 .zip），空则用 {@link #DEFAULT_NAME}
     * @param force    true = 无条件重新合成（热加载）；false = 指纹未变则复用（启动）
     * @param options  自定义身份（名称/颜色/图标/兜底格式号）
     */
    public static File ensureMerged(List<File> sources, File outDir, String fileName, boolean force,
                                    MergeOptions options) throws IOException {
        MergeOptions opts = normalize(options);
        outDir.mkdirs();
        String base = baseName(fileName);
        File out = new File(outDir, base + ".zip");
        File meta = new File(outDir, ".merge.meta");
        String fp = fingerprint(sources, opts);

        if (!force && cacheValid(out, meta, fp)) {
            Log.info("复用已合成的资源包 " + out.getName() + "（" + mb(out.length()) + " MB，名称「"
                    + opts.displayName() + "」）；源包有改动或执行 /xiaohu_package reload force 才会重新合成");
            return out;
        }

        File tmp = new File(outDir, base + ".zip.tmp");
        if (tmp.exists() && !tmp.delete()) {
            Log.warn("无法删除上次残留的临时文件 " + tmp.getName() + "，将直接覆盖");
        }

        Log.info((force ? "重新合成" : "合成") + " " + sources.size() + " 个资源包 -> "
                + out.getAbsolutePath() + "（共 " + mb(totalSize(sources)) + " MB），请稍候...");
        long t0 = System.currentTimeMillis();

        MergeResult result = merge(sources, tmp, opts);
        Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
        writeMeta(meta, fp);

        Log.success("资源包合成完成：" + sources.size() + " 个源包 -> " + out.getName()
                + "（" + mb(out.length()) + " MB，名称「" + opts.displayName() + "」"
                + (result.iconEmbedded() ? "，已套用自定义图标" : "")
                + "，同名文件覆盖 " + result.overridden() + " 处，用时 " + sec(t0) + " 秒）");
        return out;
    }

    /**
     * 按优先级升序读取源包，同名条目后者覆盖前者，套用自定义名称与图标后写成一个 zip。
     */
    private static MergeResult merge(List<File> sources, File out, MergeOptions opts) throws IOException {
        Map<File, ZipFile> opened = new LinkedHashMap<>();
        try {
            Map<String, Src> picked = new LinkedHashMap<>();
            Set<String> formats = new LinkedHashSet<>();
            String filter = null;
            int filterCount = 0;
            int overridden = 0;

            for (File src : sources) {
                ZipFile zf = new ZipFile(src);
                opened.put(src, zf);

                // 源包自己的 pack.mcmeta 只用来取两样东西：格式号（仅日志）与 filter（需要保留）。
                // 其余版本声明一律忽略——合成包用插件内置的版本号。
                if (zf.getEntry("pack.mcmeta") != null) {
                    String text = new String(readFully(zf, "pack.mcmeta"), StandardCharsets.UTF_8);
                    Matcher fm = PACK_FORMAT.matcher(text);
                    if (fm.find()) {
                        formats.add(fm.group(1));
                    }
                    String f = rawValue(text, "filter");
                    if (f != null) {
                        filter = f;        // 升序遍历 -> 最终留下的是优先级最高的那份
                        filterCount++;
                    }
                }

                Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    if (e.isDirectory()) {
                        continue;
                    }
                    if (picked.put(e.getName(), new Src(src, e.getName())) != null) {
                        overridden++;
                    }
                }
            }

            if (!formats.isEmpty()) {
                Log.info("源包的 pack_format=" + formats + " 已忽略：合成包统一使用插件内置版本声明 "
                        + opts.packFormat() + "（兼容 " + opts.formatMin() + "~" + opts.packFormat() + "）");
            }
            // 版本声明由插件生成（名称/颜色/格式号/兼容区间），不沿用源包。
            byte[] newMcmeta = buildPackMcmeta(filter, filterCount, opts);

            byte[] icon = opts.iconPng();

            try (ZipOutputStream zos =
                         new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(out)))) {
                // 源包里多是已压缩的 png/ogg，用最快档压缩，避免合成几百 MB 时长时间占 CPU。
                zos.setLevel(Deflater.BEST_SPEED);
                byte[] buf = new byte[65536];
                for (Map.Entry<String, Src> me : picked.entrySet()) {
                    String entryName = me.getKey();
                    if ("pack.mcmeta".equals(entryName) && newMcmeta != null) {
                        writeEntry(zos, entryName, newMcmeta);
                        continue;
                    }
                    if ("pack.png".equals(entryName) && icon != null) {
                        writeEntry(zos, entryName, icon);
                        continue;
                    }
                    Src s = me.getValue();
                    ZipFile zf = opened.get(s.file());
                    zos.putNextEntry(new ZipEntry(entryName));
                    try (InputStream in = zf.getInputStream(zf.getEntry(s.name()))) {
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            zos.write(buf, 0, n);
                        }
                    }
                    zos.closeEntry();
                }
                // 源包里没有的，补上：保证合成包一定带自定义名称与图标。
                if (newMcmeta != null && !picked.containsKey("pack.mcmeta")) {
                    writeEntry(zos, "pack.mcmeta", newMcmeta);
                }
                if (icon != null && !picked.containsKey("pack.png")) {
                    writeEntry(zos, "pack.png", icon);
                }
            }
            return new MergeResult(overridden, icon != null);
        } finally {
            for (ZipFile zf : opened.values()) {
                try {
                    zf.close();
                } catch (IOException ignored) {
                    // 关闭失败不影响合成结果。
                }
            }
        }
    }

    /**
     * 生成合成包的 {@code pack.mcmeta}：**完全由插件内置值构成**（名称/颜色/格式号/兼容区间），
     * 不沿用源包的版本声明；唯一保留的是源包的 {@code filter} 块（"屏蔽其它包文件"的正则列表，
     * 丢掉会改变行为）。
     * <p>
     * 版本号写法由用户指定，只出三个字段（不再输出 {@code supported_formats}）：
     * <pre>
     * "pack_format": 88,
     * "min_format": 80,
     * "max_format": 88
     * </pre>
     *
     * @param filter      要保留的 filter 原始 JSON 文本（没有则 null）
     * @param filterCount 声明了 filter 的源包数量（>1 时告警：只保留优先级最高那份）
     */
    private static byte[] buildPackMcmeta(String filter, int filterCount, MergeOptions opts) {
        String desc = "{\"text\": \"" + jsonEscape(opts.displayName()) + "\", \"color\": \""
                + jsonEscape(opts.color()) + "\"}";
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"pack\": {\n")
                .append("    \"description\": ").append(desc).append(",\n")
                .append("    \"pack_format\": ").append(opts.packFormat()).append(",\n")
                .append("    \"min_format\": ").append(opts.formatMin()).append(",\n")
                .append("    \"max_format\": ").append(opts.packFormat()).append("\n")
                .append("  }");
        if (filter != null) {
            sb.append(",\n  \"filter\": ").append(filter);
            if (filterCount > 1) {
                Log.warn("有 " + filterCount + " 个源包声明了 filter 块，产物只保留优先级最高的那份；"
                        + "若需要合并多份 filter，请手动整理源包");
            } else {
                Log.info("已保留源包的 pack.mcmeta filter 块");
            }
        }
        sb.append("\n}\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** 取 JSON 里某个键的原始值文本（保持原结构），取不到返回 null。 */
    private static String rawValue(String json, String key) {
        int[] span = findValueSpan(json, key);
        return span == null ? null : json.substring(span[0], span[1]);
    }

    /**
     * 定位 JSON 里某个键的值区间（半开区间 [start, end)），支持字符串、对象/数组与字面量。
     * 只做"够用就好"的扫描：不解析整个 JSON，但会正确跳过字符串与转义。
     */
    private static int[] findValueSpan(String json, String key) {
        int k = json.indexOf('"' + key + '"');
        if (k < 0) {
            return null;
        }
        int colon = json.indexOf(':', k + key.length() + 2);
        if (colon < 0) {
            return null;
        }
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        if (i >= json.length()) {
            return null;
        }
        char c = json.charAt(i);
        if (c == '"') {
            int j = i + 1;
            while (j < json.length()) {
                char d = json.charAt(j);
                if (d == '\\') {
                    j += 2;
                    continue;
                }
                if (d == '"') {
                    return new int[]{i, j + 1};
                }
                j++;
            }
            return null;
        }
        if (c == '{' || c == '[') {
            int depth = 0;
            int j = i;
            while (j < json.length()) {
                char d = json.charAt(j);
                if (d == '"') {
                    j++;
                    while (j < json.length() && json.charAt(j) != '"') {
                        if (json.charAt(j) == '\\') {
                            j++;
                        }
                        j++;
                    }
                } else if (d == '{' || d == '[') {
                    depth++;
                } else if (d == '}' || d == ']') {
                    depth--;
                    if (depth == 0) {
                        return new int[]{i, j + 1};
                    }
                }
                j++;
            }
            return null;
        }
        int j = i;
        while (j < json.length() && ",}] \t\r\n".indexOf(json.charAt(j)) < 0) {
            j++;
        }
        return new int[]{i, j};
    }

    /** JSON 字符串转义（名称/颜色由配置提供，必须转义，避免写出非法 mcmeta）。 */
    private static String jsonEscape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static byte[] readFully(ZipFile zf, String name) throws IOException {
        try (InputStream in = zf.getInputStream(zf.getEntry(name))) {
            return in.readAllBytes();
        }
    }

    private static void writeEntry(ZipOutputStream zos, String name, byte[] data) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data);
        zos.closeEntry();
    }

    private static MergeOptions normalize(MergeOptions options) {
        if (options == null) {
            return MergeOptions.defaults();
        }
        String name = options.displayName() == null || options.displayName().isBlank()
                ? DEFAULT_PACK_NAME : options.displayName().trim();
        String color = options.color() == null || options.color().isBlank()
                ? DEFAULT_PACK_COLOR : options.color().trim();
        int packFormat = options.packFormat() > 0 ? options.packFormat() : DEFAULT_PACK_FORMAT;
        int formatMin = options.formatMin() > 0 ? Math.min(options.formatMin(), packFormat) : packFormat;
        return new MergeOptions(name, color, options.iconPng(), packFormat, formatMin);
    }

    private static String baseName(String fileName) {
        return fileName == null || fileName.isBlank() ? DEFAULT_NAME : fileName.trim();
    }

    /**
     * 指纹 = 源包（名+大小+修改时间） + 自定义身份（名称/颜色/图标内容/兜底格式号）。
     * 刻意不哈希源包内容——源包动辄几百 MB；而图标很小，直接哈希字节，改了图标立刻失效。
     */
    private static String fingerprint(List<File> sources, MergeOptions opts) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-1");
        } catch (Exception e) {
            throw new IOException("无法创建 SHA-1 摘要: " + e.getMessage(), e);
        }
        for (File f : sources) {
            md.update(f.getName().getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(String.valueOf(f.length()).getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(String.valueOf(f.lastModified()).getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
        }
        md.update(opts.displayName().getBytes(StandardCharsets.UTF_8));
        md.update((byte) 0);
        md.update(opts.color().getBytes(StandardCharsets.UTF_8));
        md.update((byte) 0);
        md.update(String.valueOf(opts.packFormat()).getBytes(StandardCharsets.UTF_8));
        md.update((byte) 0);
        md.update(String.valueOf(opts.formatMin()).getBytes(StandardCharsets.UTF_8));
        md.update((byte) 0);
        if (opts.iconPng() != null) {
            md.update(opts.iconPng());
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static boolean cacheValid(File out, File meta, String fp) {
        if (!out.exists() || out.length() <= 0 || !meta.exists()) {
            return false;
        }
        try {
            List<String> lines = Files.readAllLines(meta.toPath(), StandardCharsets.UTF_8);
            return lines.size() == 2
                    && lines.get(0).equals(String.valueOf(META_VERSION))
                    && lines.get(1).equals(fp);
        } catch (IOException e) {
            return false;
        }
    }

    private static void writeMeta(File meta, String fp) throws IOException {
        Files.write(meta.toPath(),
                (META_VERSION + "\n" + fp + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static long totalSize(List<File> sources) {
        long sum = 0L;
        for (File f : sources) {
            sum += f.length();
        }
        return sum;
    }

    private static String mb(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.1f", bytes / 1024.0 / 1024.0);
    }

    private static String sec(long startMillis) {
        return String.format(java.util.Locale.ROOT, "%.1f", (System.currentTimeMillis() - startMillis) / 1000.0);
    }

    /** 一条待写入的条目：来自哪个源包、在源包里的条目名。 */
    private record Src(File file, String name) {
    }

    /** 合成结果：被覆盖的同名条目数、是否套用了自定义图标。 */
    private record MergeResult(int overridden, boolean iconEmbedded) {
    }
}
