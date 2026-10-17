package dev.infohud.bar;

import java.util.logging.Level;

import org.bukkit.scheduler.BukkitRunnable;

import dev.infohud.InfoHUD;

/**
 * 血条清扫任务（主线程）：摘掉已到期的血条。
 *
 * <p>周期 {@value #SWEEP_INTERVAL_TICKS} tick（0.25 秒）：到期判定依赖这个周期，
 * 周期越长血条越“超时”地多留一会儿。死亡血条默认只显示 1 秒（20 tick），
 * 若仍按 1 秒一轮清扫，实际可能拖到近 2 秒才消失，所以这里调到 5 tick，
 * 把误差压到 ≤0.25 秒。</p>
 *
 * <p>血条表为空时只是走一遍空迭代器，开销可忽略，因此无需按
 * {@code bar.enabled} 开关启停——关掉开关后 {@code HealthBarService#closeAll}
 * 已把表清空，任务空转即可。</p>
 */
public final class HealthBarSweepTask extends BukkitRunnable {

    /** 清扫周期（tick）。同时决定血条消失时刻的精度。 */
    public static final int SWEEP_INTERVAL_TICKS = 5;

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
