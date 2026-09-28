package com.xiaohu.resourcepack;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 将超过客户端单包大小上限的资源包拆分成多个合法子资源包。
 * <p>
 * 做法：按 {@link ZipFile} 读取条目，把每个 {@code .png} 与其 {@code .png.mcmeta}
 * 绑成一个"单元"，再按大小做 first-fit-decreasing 装箱（每份 ≤ 目标上限），
 * 每个子包都写入原 {@code pack.mcmeta} 与 {@code pack.png}，从而保持合法且内容互补。
 * 结果写入缓存目录；源文件未变时复用缓存，避免反复拆包。
 */
public final class ResourcePackSplitter {

    /** 每个拆分后子资源包的目标大小上限默认值（留余量，低于客户端 250MB 上限）。 */
    private static final long PART_BYTES = 245_000_000L;

    private ResourcePackSplitter() {
    }

    /** 用默认上限（{@link #PART_BYTES}）拆分。 */
    public static List<File> ensureSplit(File source, File cacheDir) throws IOException {
        return ensureSplit(source, cacheDir, PART_BYTES);
    }

    /**
     * 确保 source 被拆分成多个 ≤ {@code partBytes} 的合法子包（带缓存），返回子包 zip 列表。
     * 若 source 本身未超目标上限则返回只含 source 本身的列表；这里由调用方保证只对超限文件调用。
     * <p>
     * 缓存以"源文件路径 + 修改时间 + 大小 + 本次上限"为准：上限改了也会重拆。
     * 分片文件名逐个记在 {@code .split.meta} 里，因此缓存目录里混入的其它 zip
     * 不会被当成自己的分片（受管目录也不盲信目录内容）。
     */
    public static List<File> ensureSplit(File source, File cacheDir, long partBytes) throws IOException {
        cacheDir.mkdirs();
        List<File> cached = cachedParts(source, cacheDir, partBytes);
        if (cached != null) {
            return cached;
        }
        deleteRecursively(cacheDir);
        cacheDir.mkdirs();
        List<File> parts = split(source, cacheDir, partBytes);
        writeMeta(source, cacheDir, partBytes, parts);
        return parts;
    }

    /**
     * 缓存有效时返回上次产出的分片列表，否则返回 {@code null}（调用方据此重新拆分）。
     * meta 结构：源路径 / 修改时间 / 大小 / 上限 / 分片数 / 各分片文件名。
     */
    private static List<File> cachedParts(File source, File cacheDir, long partBytes) {
        File meta = new File(cacheDir, ".split.meta");
        if (!meta.exists()) {
            return null;
        }
        try {
            List<String> lines = Files.readAllLines(meta.toPath(), StandardCharsets.UTF_8);
            if (lines.size() < 6
                    || !lines.get(0).equals(source.getAbsolutePath())
                    || !lines.get(1).equals(String.valueOf(source.lastModified()))
                    || !lines.get(2).equals(String.valueOf(source.length()))
                    || !lines.get(3).equals(String.valueOf(partBytes))
                    || !lines.get(4).equals(String.valueOf(lines.size() - 5))) {
                return null;
            }
            List<File> out = new ArrayList<>(lines.size() - 5);
            for (int i = 5; i < lines.size(); i++) {
                File p = new File(cacheDir, lines.get(i));
                if (!p.isFile() || p.length() <= 0) {
                    return null;   // 分片缺失或为空 -> 缓存作废，重新拆分
                }
                out.add(p);
            }
            return out;
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeMeta(File source, File cacheDir, long partBytes, List<File> parts)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(source.getAbsolutePath()).append('\n')
                .append(source.lastModified()).append('\n')
                .append(source.length()).append('\n')
                .append(partBytes).append('\n')
                .append(parts.size()).append('\n');
        for (File p : parts) {
            sb.append(p.getName()).append('\n');
        }
        Files.write(new File(cacheDir, ".split.meta").toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static List<File> split(File source, File outDir, long partBytes) throws IOException {
        final String base = source.getName().replaceAll("(?i)\\.zip$", "");
        final List<Unit> units = new ArrayList<>();
        byte[] mcmeta;
        byte[] png;

        try (ZipFile zf = new ZipFile(source)) {
            mcmeta = readBytes(zf, "pack.mcmeta");
            png = readBytes(zf, "pack.png");

            // 按配对键聚合：.png 与其 .png.mcmeta 视为同一单元。
            Map<String, List<ZipEntry>> byKey = new LinkedHashMap<>();
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) {
                    continue;
                }
                String name = e.getName();
                if (name.equals("pack.mcmeta") || name.equals("pack.png")) {
                    continue;
                }
                String key = name.endsWith(".png.mcmeta")
                        ? name.substring(0, name.length() - ".mcmeta".length())
                        : name;
                byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
            }
            for (Map.Entry<String, List<ZipEntry>> m : byKey.entrySet()) {
                long sz = 0;
                for (ZipEntry e : m.getValue()) {
                    sz += Math.max(0, e.getSize());
                }
                units.add(new Unit(m.getKey(), m.getValue(), sz));
            }

            // first-fit-decreasing 装箱，每个箱 ≤ partBytes。
            units.sort((a, b) -> Long.compare(b.size, a.size));
            List<Bin> bins = new ArrayList<>();
            for (Unit u : units) {
                if (u.size > partBytes) {
                    throw new IOException("单个文件 " + u.key + "（" + u.size + " B）超过拆分上限，无法拆分");
                }
                Bin bin = null;
                for (Bin b : bins) {
                    if (b.sum + u.size <= partBytes) {
                        bin = b;
                        break;
                    }
                }
                if (bin == null) {
                    bin = new Bin();
                    bins.add(bin);
                }
                bin.units.add(u);
                bin.sum += u.size;
            }

            // 写每个子包。
            List<File> files = new ArrayList<>();
            for (int i = 0; i < bins.size(); i++) {
                File out = new File(outDir, base + "_part" + (i + 1) + ".zip");
                try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {
                    put(zos, "pack.mcmeta", mcmeta);
                    put(zos, "pack.png", png);
                    for (Unit u : bins.get(i).units) {
                        for (ZipEntry e : u.entries) {
                            zos.putNextEntry(new ZipEntry(e.getName()));
                            try (InputStream is = zf.getInputStream(e)) {
                                copy(is, zos);
                            }
                            zos.closeEntry();
                        }
                    }
                }
                files.add(out);
            }
            return files;
        }
    }

    private static byte[] readBytes(ZipFile zf, String name) throws IOException {
        ZipEntry e = zf.getEntry(name);
        if (e == null) {
            return null;
        }
        try (InputStream is = zf.getInputStream(e)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            copy(is, bos);
            return bos.toByteArray();
        }
    }

    private static void put(ZipOutputStream zos, String name, byte[] data) throws IOException {
        if (data == null) {
            return;
        }
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data);
        zos.closeEntry();
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[65536];
        int r;
        while ((r = in.read(buf)) > 0) {
            out.write(buf, 0, r);
        }
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] ch = f.listFiles();
        if (ch != null) {
            for (File c : ch) {
                deleteRecursively(c);
            }
        }
        f.delete();
    }

    private static final class Unit {
        final String key;
        final List<ZipEntry> entries;
        final long size;

        Unit(String key, List<ZipEntry> entries, long size) {
            this.key = key;
            this.entries = entries;
            this.size = size;
        }
    }

    private static final class Bin {
        final List<Unit> units = new ArrayList<>();
        long sum;
    }
}
