package com.xiaohu.resourcepack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * {@code /xiaohu_package} 命令补全：返回子命令与玩家名。
 */
public final class PackTabCompleter implements TabCompleter {

    private static final List<String> SUBCOMMANDS =
            List.of("help", "resend", "resendall", "reload", "status");

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("xiaohu_package.admin")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            List<String> out = new ArrayList<>();
            for (String sub : SUBCOMMANDS) {
                if (sub.startsWith(prefix)) {
                    out.add(sub);
                }
            }
            return out;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("resend")) {
            String prefix = args[1].toLowerCase();
            List<String> out = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(prefix)) {
                    out.add(p.getName());
                }
            }
            return out;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("reload")) {
            return "force".startsWith(args[1].toLowerCase()) ? List.of("force") : Collections.emptyList();
        }
        return Collections.emptyList();
    }
}
