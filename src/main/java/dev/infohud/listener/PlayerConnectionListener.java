package dev.infohud.listener;

import java.util.List;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import dev.infohud.InfoHUD;

/**
 * 连接与位置突变相关事件：建立群系基线、离线清理、换世界/传送后刷新。
 */
public final class PlayerConnectionListener implements Listener {

    /** 上线后延迟多少 tick 建立群系基线（等玩家所在区块真正加载完）。 */
    private static final long PRIME_DELAY_TICKS = 20L;

    /** 换世界 / 传送后的刷新延迟（新坐标的区块需要先加载）。 */
    private static final long REFRESH_DELAY_TICKS = 20L;

    private final InfoHUD plugin;

    public PlayerConnectionListener(InfoHUD plugin) {
        this.plugin = plugin;
    }

    /**
     * 玩家上线。
     *
     * <p>延迟一批再读群系：{@code PlayerJoinEvent} 触发时客户端区块还没确认加载完，
     * 此时读群系可能返回 null 或触发区块加载。基线建立后 HUD 立刻有内容，
     * 而“已在某群系”不会触发提示（{@code BiomeService} 首次记录只建基线不发消息）。</p>
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                plugin.biomes().prime(List.of(player), plugin.config());
            }
        }, PRIME_DELAY_TICKS);
    }

    /** 玩家离线：清理群系缓存、移动锚点与顶部罗盘 BossBar（个人偏好由 {@code PreferenceStore} 持久化）。 */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        plugin.biomes().forget(id);
        plugin.compass().closeFor(id);
    }

    /**
     * 跨世界移动（下界门、末地门、/spawn 到别的世界）。
     *
     * <p>这里<b>不能</b>直接重建基线，否则“从主世界进下界”这种明显的群系变化会被吞掉；
     * 延迟几 tick 走正常比较流程，由 {@code biome-notification.notify-on-world-change}
     * 决定是否提示。延迟期间 HUD 仍显示旧群系，最多 {@value #REFRESH_DELAY_TICKS} tick。</p>
     */
    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () ->
                plugin.biomes().refresh(player, plugin.config()), REFRESH_DELAY_TICKS);
    }

    /**
     * 同世界传送（/tp、/warp、插件回城等）：也要刷新，否则站桩不动的玩家会一直显示旧群系。
     */
    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getTo() == null || event.getPlayer() == null) {
            return;
        }
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () ->
                plugin.biomes().refresh(player, plugin.config()), REFRESH_DELAY_TICKS);
    }
}
