package com.xiaohu.resourcepack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.bukkit.entity.Player;

/**
 * 公平带宽分配器（fair-share pacing）。
 * <p>
 * 全局总带宽 {@code mbps} Mbps。每个活跃下载会话分得一份
 * {@code 总速率 / 活跃会话数}；任一会话下载完会自动释放份额，其余会话立即提速。
 * 由此既给出"合理分配网速"，又把全体聚合吞吐限制在总上限内。
 */
public final class BandwidthAllocator {

    private final int mbps;
    private final double bytesPerSec;
    private final List<Session> sessions = Collections.synchronizedList(new ArrayList<>());

    public BandwidthAllocator(int mbps) {
        this.mbps = mbps;
        this.bytesPerSec = mbps * 1_000_000.0 / 8.0;
    }

    public int getMbps() {
        return mbps;
    }

    public double getBytesPerSec() {
        return bytesPerSec;
    }

    /** 当前活跃（正在处理）的下载会话数。 */
    public int activeSessionCount() {
        synchronized (sessions) {
            return sessions.size();
        }
    }

    /** 当前正在下载的不同玩家数（用于玩家侧提示"前面有几个玩家"）。 */
    public int activePlayerCount() {
        synchronized (sessions) {
            Set<Player> players = new HashSet<>();
            for (Session s : sessions) {
                if (s.player != null) {
                    players.add(s.player);
                }
            }
            return players.size();
        }
    }

    /** 给定活跃数下，每个会话分到的速度（Mbps）。 */
    public double shareMbps(int active) {
        return mbps / Math.max(1, active);
    }

    /** 给定活跃数下，每个会话分到的速度（字节/秒）。 */
    public double shareBytesPerSec(int active) {
        return bytesPerSec / Math.max(1, active);
    }

    /** 新建一个下载会话并登记；player 可为 null（未归属玩家时仍限速但不上报）。 */
    public Session newSession(Player player, ResourcePackManager.ResourcePackEntry entry) {
        Session s = new Session(player, entry);
        synchronized (sessions) {
            sessions.add(s);
        }
        return s;
    }

    public void removeSession(Session s) {
        synchronized (sessions) {
            sessions.remove(s);
        }
    }

    /**
     * 限速写入：让本会话以"全局速率/活跃会话数"的份额发送。
     * 按虚拟时间调度（每个会话记录自己的下一发送时刻），睡眠到点后再写，避免超发。
     * 睡眠期间被中断则向上抛出，调用方据此安全中止本次下载。
     */
    public void pace(Session s, int n) throws InterruptedException {
        int active;
        synchronized (sessions) {
            active = sessions.size();
        }
        double share = bytesPerSec / Math.max(1, active);

        long now = System.nanoTime();
        long due = Math.max(now, s.nextSendTime);
        if (due > now) {
            long wait = due - now;
            long ms = wait / 1_000_000L;
            int ns = (int) (wait % 1_000_000L);
            Thread.sleep(ms, ns);
        }
        long sendDur = (long) ((n / share) * 1_000_000_000L);
        s.nextSendTime = due + sendDur;
        s.addBytes(n);
    }

    /**
     * 单个下载会话：记录该会话的玩家、资源包、已发送字节与速度统计。
     */
    public static final class Session {
        private final Player player;
        private final ResourcePackManager.ResourcePackEntry entry;
        private long nextSendTime;
        private long bytesSent;
        private final long startNanos = System.nanoTime();
        private long lastLogNanos = startNanos;
        private long lastLogBytes;

        Session(Player player, ResourcePackManager.ResourcePackEntry entry) {
            this.player = player;
            this.entry = entry;
        }

        public Player getPlayer() {
            return player;
        }

        public ResourcePackManager.ResourcePackEntry getEntry() {
            return entry;
        }

        public long getBytesSent() {
            return bytesSent;
        }

        public long getStartNanos() {
            return startNanos;
        }

        synchronized void addBytes(int n) {
            bytesSent += n;
        }

        /** 自会话开始后的平均速度（Mbps）。 */
        public double avgMbps() {
            long now = System.nanoTime();
            double dt = (now - startNanos) / 1_000_000_000.0;
            if (dt <= 0) {
                return 0;
            }
            return (bytesSent * 8.0) / dt / 1_000_000.0;
        }

        /** 距上次日志以来的瞬时速度（Mbps）。 */
        public double currentMbps() {
            long now = System.nanoTime();
            double dt = (now - lastLogNanos) / 1_000_000_000.0;
            if (dt <= 0) {
                return 0;
            }
            return (bytesSent - lastLogBytes) * 8.0 / dt / 1_000_000.0;
        }

        public long elapsedSinceLogNanos() {
            return System.nanoTime() - lastLogNanos;
        }

        public void markLogged() {
            lastLogNanos = System.nanoTime();
            lastLogBytes = bytesSent;
        }
    }
}
