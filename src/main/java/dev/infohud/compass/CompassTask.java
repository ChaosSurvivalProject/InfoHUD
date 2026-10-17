package dev.infohud.compass;

import java.util.Collection;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import dev.infohud.InfoHUD;
import dev.infohud.Permissions;
import dev.infohud.config.PluginConfig;

/**
 * 顶部罗盘刷新任务（主线程，默认间隔见 {@code compass.update-interval-ticks}）。
 *
 * <p>每轮只读玩家自带的 yaw，把方位角写进各自的 BossBar：
 * 没有任何区块访问，单玩家开销 = 一次整数比较 +（朝向变化时）一条 BossBar 包，
 * 比 {@code HudTask} 的 ActionBar 便宜得多，因此默认间隔可以更短以跟手。</p>
 */
public final class CompassTask extends BukkitRunnable {

    private final InfoHUD plugin;

    public CompassTask(InfoHUD plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        try {
            PluginConfig config = plugin.config();
            Collection<? extends Player> online = Bukkit.getOnlinePlayers();
            if (online.isEmpty()) {
                return;
            }
            for (Player player : online) {
                if (!player.hasPermission(Permissions.COMPASS)) {
                    continue;
                }
                if (!plugin.preferences().compass(player.getUniqueId(), config.compassEnabledByDefault())) {
                    continue;
                }
                plugin.compass().update(player, config);
            }
        } catch (RuntimeException ex) {
            // 单次异常不能连带把整个调度器任务取消掉
            plugin.getLogger().log(Level.SEVERE, "罗盘刷新任务出现异常，本轮跳过（插件继续运行）", ex);
        }
    }
}
