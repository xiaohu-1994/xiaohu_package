package com.xiaohu.resourcepack;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 玩家连接 IP -> Player 的映射，供 HTTP 下载服务把下载会话归属到具体玩家。
 * <p>
 * 客户端下载资源包时的 HTTP 请求源 IP 与玩家连服 IP 相同，因此可据此关联。
 * 注意 NAT/多玩家同 IP 时只能归属到其中一个（按最后记录者），属已知边界。
 */
public final class PlayerTracker implements Listener {

    private final Map<String, Player> byIp = new ConcurrentHashMap<>();

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        record(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        forget(event.getPlayer());
    }

    private void record(Player p) {
        InetSocketAddress a = p.getAddress();
        if (a != null && a.getAddress() != null) {
            byIp.put(norm(a.getAddress().getHostAddress()), p);
        }
    }

    private void forget(Player p) {
        byIp.values().removeIf(v -> v == p);
    }

    /** 根据连接远端地址解析出玩家；无则返回 null。 */
    public Player resolve(InetSocketAddress addr) {
        if (addr == null || addr.getAddress() == null) {
            return null;
        }
        return byIp.get(norm(addr.getAddress().getHostAddress()));
    }

    /** IPv4 走 IPv6 映射（如 ::ffff:1.2.3.4）时去掉前缀，保证两端一致。 */
    static String norm(String ip) {
        if (ip == null) {
            return null;
        }
        if (ip.startsWith("::ffff:")) {
            return ip.substring(7);
        }
        return ip;
    }
}
