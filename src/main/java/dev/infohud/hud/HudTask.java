package dev.infohud.hud;

import java.util.Collection;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.block.Biome;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import dev.infohud.InfoHUD;
import dev.infohud.Permissions;
import dev.infohud.config.PluginConfig;
import dev.infohud.time.TimeUtil;

/**
 * ActionBar 刷新任务（主线程，默认每 20 tick 一次）。
 *
 * <p>为什么不异步？{@code World#getBiome} 与 {@code Player#sendActionBar} 都要求主线程，
 * 而异步读区块反而更危险。这里把主线程开销压到最低：群系走 {@code BiomeService} 的缓存
 * （真正的区块读取只发生在“玩家跨方块 + 冷却到期”时），时间换算查预生成表，
 * 时段/现实时间/TPS 每轮只算一次，组件按文本复用。</p>
 */
public final class HudTask extends BukkitRunnable {

    private final InfoHUD plugin;

    public HudTask(InfoHUD plugin) {
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

            HudRenderer renderer = plugin.renderer();
            renderer.beginTick();

            String format = config.displayFormat();
            HudContext context = new HudContext(
                    TimeUtil.realTime(config.timezone(), config.realtimeFormatter()),
                    HudContext.readTps(format.contains("%tps%")));

            for (Player player : online) {
                if (!player.hasPermission(Permissions.USE)) {
                    continue;
                }
                if (!plugin.preferences().enabled(player.getUniqueId(), config.enabledByDefault())) {
                    continue;
                }
                Biome biome = plugin.biomes().currentBiome(player, config);
                player.sendActionBar(renderer.build(player, biome, context, config));
            }
        } catch (RuntimeException ex) {
            // 单次异常不能连带把整个调度器任务取消掉
            plugin.getLogger().log(Level.SEVERE, "HUD 刷新任务出现异常，本轮跳过（插件继续运行）", ex);
        }
    }
}
