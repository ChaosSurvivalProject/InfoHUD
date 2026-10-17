package dev.infohud.bar;

import java.util.logging.Level;

import org.bukkit.scheduler.BukkitRunnable;

import dev.infohud.InfoHUD;

/**
 * 血条清扫任务（主线程，固定每 20 tick = 1 秒一轮）：摘掉已到期的血条。
 *
 * <p>血条表为空时只是走一遍空迭代器，开销可忽略，因此无需按
 * {@code bar.enabled} 开关启停——关掉开关后 {@code HealthBarService#closeAll}
 * 已把表清空，任务空转即可。</p>
 */
public final class HealthBarSweepTask extends BukkitRunnable {

    private final InfoHUD plugin;

    public HealthBarSweepTask(InfoHUD plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        try {
            plugin.healthBars().sweepExpired();
        } catch (RuntimeException ex) {
            // 单次异常不能连带把整个调度器任务取消掉
            plugin.getLogger().log(Level.SEVERE, "血条清扫任务出现异常，本轮跳过（插件继续运行）", ex);
        }
    }
}
