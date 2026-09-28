package com.xiaohu.resourcepack;

import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 统一彩色控制台日志出口：info / warn / error / success / debug / player(...)。
 * <p>
 * 前缀用与聊天完全一致的**黄色渐变** {@code [xiaohu_package]}（Adventure 组件，Paper 自带），
 * 正文按 legacy {@code §} 码着色。
 */
public final class Log {

    private static JavaPlugin plugin;
    private static boolean debug = false;

    private Log() {
        // util class，禁止实例化
    }

    /** 在 onEnable 里调用，把插件引用注入到静态日志出口。 */
    public static void init(JavaPlugin p) {
        plugin = p;
    }

    /** 开启/关闭 debug 级输出（默认关闭，避免 ACCEPTED/DISCARDED 刷屏）。 */
    public static void setDebug(boolean on) {
        debug = on;
    }

    private static void sendToConsole(String message) {
        if (plugin == null) {
            return;
        }
        plugin.getServer().getConsoleSender()
                .sendMessage(Messenger.prefix().append(Messenger.of("&e" + message)));
    }

    public static void info(String message) {
        sendToConsole(message);
    }

    public static void warn(String message) {
        sendToConsole(message);
    }

    public static void error(String message) {
        sendToConsole(message);
    }

    public static void success(String message) {
        sendToConsole(message);
    }

    public static void debug(String message) {
        if (debug) {
            sendToConsole(message);
        }
    }

    /** 给玩家/命令发送者发一条带前缀的彩色消息。 */
    public static void player(CommandSender receiver, String message) {
        Messenger.send(receiver, message);
    }
}
