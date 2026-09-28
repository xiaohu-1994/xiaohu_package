package com.xiaohu.resourcepack;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * 处理"是否下载资源包"聊天询问的点选结果（/xiaohu_packanswer yes|no）。
 */
public final class PackAnswerCommand implements CommandExecutor {

    private final XiaohuPackagePlugin plugin;

    public PackAnswerCommand(XiaohuPackagePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("仅限玩家使用");
            return true;
        }
        if (args.length < 1) {
            Messenger.send(player, "&e请点击 [是] / [否]");
            return true;
        }
        if (args[0].equalsIgnoreCase("yes")) {
            plugin.handleYes(player);
        } else if (args[0].equalsIgnoreCase("no")) {
            plugin.handleNo(player);
        } else {
            Messenger.send(player, "&e请点击 [是] / [否]");
        }
        return true;
    }
}