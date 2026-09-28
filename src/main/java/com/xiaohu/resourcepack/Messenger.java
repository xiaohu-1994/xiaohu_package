package com.xiaohu.resourcepack;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * 玩家/控制台消息工具：统一加上**黄色渐变**的 {@code [xiaohu_package]} 前缀，并把 legacy {@code &}
 * 颜色码转成 {@code §}。
 * <p>
 * 渐变只能用 Adventure 组件表达（legacy {@code §} 码没有渐变）：前缀由逐字符 {@link TextColor} 插值
 * 生成（浅黄 {@code #FFF6B0} → 金黄 {@code #FFA800}），正文用
 * {@link LegacyComponentSerializer} 把原有 {@code &} 码文本转成组件后拼接。
 * <p>
 * 依赖都是 Paper 自带的（服务器 {@code libraries/} 下确有 adventure-api 与
 * adventure-text-serializer-legacy），不引入任何第三方打包依赖。
 */
public final class Messenger {

    private Messenger() {
        // util class，禁止实例化
    }

    /** legacy（{@code &} 码）前缀字符串；仅供无法使用组件的兜底路径使用。 */
    public static final String PREFIX = "&b[xiaohu_package]&f ";

    /** 渐变的起止色：浅黄 -> 金黄（都在黄色系内）。 */
    private static final int GRADIENT_FROM = 0xFFF6B0;
    private static final int GRADIENT_TO = 0xFFA800;

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    /** 渐变前缀（含一个尾随空格），只构造一次。 */
    private static final Component PREFIX_COMPONENT =
            gradient("[xiaohu_package]").append(Component.text(" "));

    /**
     * 把 legacy {@code &} 颜色码转换为 Minecraft 的 {@code §} 码。
     */
    public static String colorize(String legacy) {
        return ChatColor.translateAlternateColorCodes('&', legacy);
    }

    /** legacy 文本（含 {@code &} 码）转成组件。 */
    public static Component of(String legacyText) {
        return LEGACY.deserialize(colorize(legacyText));
    }

    /**
     * 生成"黄色渐变"文本：逐字符在 {@link #GRADIENT_FROM} 与 {@link #GRADIENT_TO} 之间插值上色。
     */
    public static Component gradient(String text) {
        Component out = Component.empty();
        int len = text.length();
        for (int i = 0; i < len; i++) {
            double t = len <= 1 ? 0.0 : (double) i / (len - 1);
            out = out.append(Component.text(String.valueOf(text.charAt(i)))
                    .color(TextColor.color(lerp(GRADIENT_FROM, GRADIENT_TO, t))));
        }
        return out;
    }

    /** 渐变前缀（含尾随空格）的组件形式。 */
    public static Component prefix() {
        return PREFIX_COMPONENT;
    }

    /** 给指定接收者发送一条"渐变前缀 + 彩色正文"的消息。 */
    public static void send(CommandSender receiver, String message) {
        receiver.sendMessage(PREFIX_COMPONENT.append(of(message)));
    }

    /** 通道独立的 RGB 线性插值。 */
    private static int lerp(int from, int to, double t) {
        int fr = (from >> 16) & 0xFF;
        int fg = (from >> 8) & 0xFF;
        int fb = from & 0xFF;
        int tr = (to >> 16) & 0xFF;
        int tg = (to >> 8) & 0xFF;
        int tb = to & 0xFF;
        int r = (int) Math.round(fr + (tr - fr) * t);
        int g = (int) Math.round(fg + (tg - fg) * t);
        int b = (int) Math.round(fb + (tb - fb) * t);
        return (r << 16) | (g << 8) | b;
    }
}
