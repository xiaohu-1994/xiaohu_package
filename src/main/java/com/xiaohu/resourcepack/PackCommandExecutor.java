package com.xiaohu.resourcepack;

import java.util.Collection;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * {@code /xiaohu_package} 命令执行器：resend &lt;player&gt; / resendall / reload / status。
 * <p>
 * 命令回调运行在主线程，直接调用 Bukkit API 安全。
 */
public final class PackCommandExecutor implements CommandExecutor {

    private final XiaohuPackagePlugin plugin;

    public PackCommandExecutor(XiaohuPackagePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("xiaohu_package.admin")) {
            Messenger.send(sender, "&c你没有权限使用该命令。");
            return true;
        }
        if (args.length == 0) {
            help(sender);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "help" -> help(sender);
            case "resend" -> resend(sender, args);
            case "resendall" -> resendAll(sender);
            case "reload" -> reload(sender, args);
            case "status" -> status(sender);
            default -> help(sender);
        }
        return true;
    }

    /**
     * 中文标注的帮助，且每条命令可点击（点击自动填入聊天框）。
     */
    private void help(CommandSender sender) {
        sender.sendMessage(helpComponent());
    }

    private Component helpComponent() {
        Component header = Component.text("=== xiaohu_package 帮助 ===", NamedTextColor.GOLD);
        return Component.text()
                .append(header).append(Component.newline())
                .append(cmdLine("/xiaohu_package help", "显示本帮助")).append(Component.newline())
                .append(cmdLine("/xiaohu_package resend <玩家>", "给指定玩家重发资源包")).append(Component.newline())
                .append(cmdLine("/xiaohu_package resendall", "给全体在线玩家重发资源包")).append(Component.newline())
                .append(cmdLine("/xiaohu_package reload", "重载配置（源包没变则不重新合成）")).append(Component.newline())
                .append(cmdLine("/xiaohu_package reload force", "强制重新合成资源包")).append(Component.newline())
                .append(cmdLine("/xiaohu_package status", "查看当前状态"))
                .build();
    }

    private Component cmdLine(String cmd, String desc) {
        return Component.text("  " + cmd, NamedTextColor.AQUA)
                .clickEvent(ClickEvent.suggestCommand(cmd))
                .hoverEvent(HoverEvent.showText(Component.text("点击填入: " + cmd, NamedTextColor.GRAY)))
                .append(Component.text("  — " + desc, NamedTextColor.GRAY));
    }

    private void resend(CommandSender sender, String[] args) {
        if (args.length < 2) {
            Messenger.send(sender, "&c用法: /xiaohu_package resend <player>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            Messenger.send(sender, "&c玩家 " + args[1] + " 不在线。");
            return;
        }
        int n = pushTo(target);
        Messenger.send(sender, "&a已向玩家 " + target.getName() + " 重发 " + n + " 个资源包。");
    }

    private void resendAll(CommandSender sender) {
        Collection<? extends Player> online = Bukkit.getOnlinePlayers();
        if (online.isEmpty()) {
            Messenger.send(sender, "&c当前无在线玩家。");
            return;
        }
        int count = 0;
        for (Player p : online) {
            count += pushTo(p);
        }
        Messenger.send(sender, "&a已向 " + online.size() + " 名在线玩家重发 " + count + " 个资源包。");
    }

    private int pushTo(Player player) {
        // 复用主类的统一下发逻辑（含基岩版跳过、removeResourcePacks、prompt/force）。
        return plugin.pushPacksTo(player);
    }

    private void reload(CommandSender sender, String[] args) {
        boolean force = args.length > 1
                && ("force".equalsIgnoreCase(args[1]) || "-f".equalsIgnoreCase(args[1]));
        Player notifier = sender instanceof Player player ? player : null;
        if (notifier != null) {
            Log.info("管理员 " + notifier.getName() + " 触发了资源包重载" + (force ? "（强制重新合成）" : ""));
        }
        if (plugin.reloadResources(notifier, force)) {
            Messenger.send(sender, "&a已开始在后台重载资源包（不占用服务器主线程）。"
                    + (force ? "已指定强制重新合成。" : "源包没有变化就会直接复用，不会重新合成。")
                    + "期间下载服务暂停，完成后自动恢复"
                    + (notifier != null ? "，进度会实时发到这里。" : "。"));
        } else {
            Messenger.send(sender, "&e资源包正在重载中，请稍候再试。");
        }
    }

    private void status(CommandSender sender) {
        FileConfiguration cfg = plugin.getConfig();
        int mbps = cfg.getInt("bandwidth.mbps", 20);
        String ip = cfg.getString("server.ip", "127.0.0.1");
        int port = cfg.getInt("server.port", 8080);
        boolean https = cfg.getBoolean("server.https", false);
        boolean force = cfg.getBoolean("packs.force", true);

        ResourcePackManager manager = plugin.getPackManager();
        PackServer server = plugin.getPackServer();

        Messenger.send(sender, "&b--- xiaohu_package 状态 ---");
        Messenger.send(sender, "&b带宽上限: &f" + mbps + " Mbps");
        Messenger.send(sender, "&b下载服务: &f" + (https ? "HTTPS" : "HTTP") + " " + ip + ":" + port
                + "  (监听中=" + (server != null && server.isListening()) + ")");
        // 公网端口占用检测。
        String portState;
        if (server == null) {
            portState = "服务未启动";
        } else if (server.isListening()) {
            portState = "已被本插件监听（正常）";
        } else if (server.isPortBindable()) {
            portState = "当前可用";
        } else {
            portState = "已被其他程序占用，启动会失败";
        }
        Messenger.send(sender, "&b公网端口: &f" + ip + ":" + port + "  " + portState);
        Messenger.send(sender, "&b强制收包: &f" + force);
        if (manager == null) {
            Messenger.send(sender, "&b资源包数量: &f0");
        } else {
            String mergeState = manager.isMergeEnabled()
                    ? "开（源包 " + manager.getSourceCount() + " 个：合成 " + manager.getMergedSourceCount()
                            + " 个为一个；超限单独 " + manager.getOversizedCount() + " 个；单包上限 "
                            + manager.getMaxSizeMb() + " MB）"
                    : "关（逐包下发）";
            Messenger.send(sender, "&b资源包合成: &f" + mergeState);
            Messenger.send(sender, "&b资源包数量: &f" + manager.getEntries().size() + "&b（含分片）");
            if (plugin.isRegenerating()) {
                Messenger.send(sender, "&e注意：资源包正在后台重新生成中，下载服务暂停，"
                        + "完成后会自动恢复（期间进服的玩家会被登记并在完成后自动补发）。");
            }
        }
        if (manager != null) {
            for (ResourcePackManager.ResourcePackEntry e : manager.getEntries()) {
                Messenger.send(sender, "&b  - " + e.getFileName() + "  ->  " + e.getUrl());
            }
        }
    }
}
