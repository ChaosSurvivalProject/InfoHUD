package dev.infohud.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import dev.infohud.InfoHUD;

/**
 * 玩家移动监听：只做“跨方块”判定，群系查询与提示交给 {@link dev.infohud.biome.BiomeService}。
 *
 * <p>两层节流：</p>
 * <ol>
 *   <li>{@link PlayerMoveEvent#hasChangedBlock()} —— 由 Paper 直接给出“本次移动是否跨方块”，
 *       转头、同一方块内的抖动都会在这里被 O(1) 挡掉，不会进入任何 Map 操作；</li>
 *   <li>每玩家查询冷却（{@code biome-notification.query-cooldown-ticks}）——
 *       即便玩家在区块边界高速移动，同一秒内也只会真正读一次群系。</li>
 * </ol>
 *
 * <p>{@code ignoreCancelled = true}：被其它插件取消的移动意味着玩家其实没动，不需要查询。</p>
 */
public final class PlayerMoveListener implements Listener {

    private final InfoHUD plugin;

    public PlayerMoveListener(InfoHUD plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedBlock()) {
            return;
        }
        Player player = event.getPlayer();
        plugin.biomes().onMove(player, plugin.config());
    }
}
