package com.xiaohu.resourcepack;

import java.util.UUID;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

/**
 * 玩家资源包监听：加入时对每个资源包各发一条；依
 * {@link PlayerResourcePackStatusEvent} 判定（DECLINED 且 force=true 则踢人）。
 * <p>
 * 主线程铁律：{@code addResourcePack}/{@code kickPlayer} 均只在事件回调（主线程）里调用，
 * 不做任何异步切换。
 */
public final class PlayerPackListener implements Listener {

    private final XiaohuPackagePlugin plugin;
    private final ResourcePackManager manager;

    public PlayerPackListener(XiaohuPackagePlugin plugin, ResourcePackManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        // 具体流程（基岩版跳过 / 生成期间登记待补发 / 强制下发 / 聊天询问）在插件主类里，
        // 好让"生成完成后自动补发"复用同一条逻辑。
        plugin.handlePlayerJoin(event.getPlayer());
    }

    @EventHandler
    public void onPackStatus(PlayerResourcePackStatusEvent event) {
        Player player = event.getPlayer();
        UUID id = event.getID();
        ResourcePackManager.ResourcePackEntry entry = manager.findByUuid(id);
        String packName = entry == null ? "未知" : entry.getFileName();
        boolean force = plugin.getConfig().getBoolean("packs.force", true);

        switch (event.getStatus()) {
            case DECLINED -> {
                if (force) {
                    Log.warn("玩家 " + player.getName() + " 拒绝资源包 [" + packName + "]，强制收包，踢出");
                    player.kickPlayer(ChatColor.translateAlternateColorCodes('&',
                            "&c你拒绝了服务器要求的资源包。"));
                } else {
                    Log.info("玩家 " + player.getName() + " 拒绝资源包 [" + packName + "]（非强制），忽略");
                }
            }
            case DOWNLOADED, SUCCESSFULLY_LOADED -> {
                Log.success("玩家 " + player.getName() + " 资源包 [" + packName + "] 接收成功");
            }
            case FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD -> {
                Log.error("玩家 " + player.getName() + " 资源包 [" + packName + "] 失败，原因 " + event.getStatus());
            }
            case ACCEPTED, DISCARDED -> {
                Log.debug("玩家 " + player.getName() + " 资源包 [" + packName + "] 状态 " + event.getStatus());
            }
        }
        // 记录下载状态，全部包到达终态后向玩家发送"成功/失败"汇总提示。
        plugin.recordPackStatus(player, id, event.getStatus());
    }
}
