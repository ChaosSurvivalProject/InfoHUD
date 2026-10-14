package dev.infohud.hud;

import java.util.HashMap;
import java.util.Map;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.entity.Player;

import dev.infohud.InfoHUD;
import dev.infohud.config.DayNightRules;
import dev.infohud.config.PluginConfig;
import dev.infohud.placeholder.PapiBridge;
import dev.infohud.text.TextUtil;
import dev.infohud.time.TimeUtil;
import dev.infohud.util.BiomeUtil;
import net.kyori.adventure.text.Component;

/**
 * ActionBar 文本渲染。
 *
 * <p>一轮刷新内的成本控制在三点：</p>
 * <ol>
 *   <li>游戏时间/时段按<b>世界</b>计算（{@link #snapshotFor}），同世界玩家复用；</li>
 *   <li>现实时间、TPS 每轮<b>只算一次</b>（{@link HudContext}）；</li>
 *   <li>解析后的 {@link Component} 按最终文本缓存一轮（{@link #tickComponents}）——
 *       同一世界、同一群系的玩家文本完全相同，可直接复用同一个不可变组件，
 *       省掉绝大多数 MiniMessage/legacy 解析。Component 是不可变的，复用安全。</li>
 * </ol>
 */
public final class HudRenderer {

    private final InfoHUD plugin;

    /** 本轮刷新内的组件缓存：最终文本 → 组件。每轮开始清空，不跨轮存活。 */
    private final Map<String, Component> tickComponents = new HashMap<>(64);

    /** 本轮刷新内的世界快照缓存。 */
    private final Map<World, WorldSnapshot> tickSnapshots = new HashMap<>(8);

    public HudRenderer(InfoHUD plugin) {
        this.plugin = plugin;
    }

    /** 每轮刷新开始前调用。 */
    public void beginTick() {
        tickComponents.clear();
        tickSnapshots.clear();
    }

    /**
     * 组装本玩家本轮的 ActionBar 组件。
     *
     * @param player  玩家
     * @param biome   本轮确定好的群系（可为 null）
     * @param context 本轮公共上下文
     * @param config  配置快照
     * @return ActionBar 组件
     */
    public Component build(Player player, Biome biome, HudContext context, PluginConfig config) {
        WorldSnapshot snapshot = snapshotFor(player.getWorld(), config);
        Location location = player.getLocation();

        String text = TextUtil.fill(config.displayFormat(),
                "%gametime%", snapshot.gameTime(),
                "%realtime%", context.realtime(),
                "%daynight%", snapshot.dayNightLabel(),
                // 注意顺序：先替换更长的 %biome_id%，再替换 %biome%
                "%biome_id%", BiomeUtil.idOf(biome),
                "%biome%", BiomeUtil.displayName(biome, config),
                "%world%", player.getWorld().getName(),
                "%x%", Integer.toString(location.getBlockX()),
                "%y%", Integer.toString(location.getBlockY()),
                "%z%", Integer.toString(location.getBlockZ()),
                "%ping%", Integer.toString(player.getPing()),
                "%tps%", context.tps());

        // 交给 PlaceholderAPI 解析外部占位符（%player_name% 等）
        if (config.placeholderApiParse() && plugin.placeholderApiReady() && text.indexOf('%') >= 0) {
            text = PapiBridge.setPlaceholders(player, text);
        }

        if (!config.cacheComponents()) {
            return TextUtil.parse(text, config);
        }
        Component cached = tickComponents.get(text);
        if (cached != null) {
            return cached;
        }
        Component parsed = TextUtil.parse(text, config);
        tickComponents.put(text, parsed);
        return parsed;
    }

    /** 世界级快照：同一轮里每个世界只算一次。 */
    private WorldSnapshot snapshotFor(World world, PluginConfig config) {
        WorldSnapshot cached = tickSnapshots.get(world);
        if (cached != null) {
            return cached;
        }
        long rawTime = world.getTime();
        DayNightRules rules = config.dayNightRules();
        WorldSnapshot snapshot = new WorldSnapshot(rawTime,
                TimeUtil.gameTime(rawTime),
                rules.label(rules.phase(rawTime)));
        tickSnapshots.put(world, snapshot);
        return snapshot;
    }

    /** 供 {@code /infohud status} 复用：直接看某玩家本轮会看到什么文本。 */
    public String preview(Player player, Biome biome, PluginConfig config) {
        WorldSnapshot snapshot = snapshotFor(player.getWorld(), config);
        Location location = player.getLocation();
        return TextUtil.fill(config.displayFormat(),
                "%gametime%", snapshot.gameTime(),
                "%realtime%", TimeUtil.realTime(config.timezone(), config.realtimeFormatter()),
                "%daynight%", snapshot.dayNightLabel(),
                "%biome_id%", BiomeUtil.idOf(biome),
                "%biome%", BiomeUtil.displayName(biome, config),
                "%world%", player.getWorld().getName(),
                "%x%", Integer.toString(location.getBlockX()),
                "%y%", Integer.toString(location.getBlockY()),
                "%z%", Integer.toString(location.getBlockZ()),
                "%ping%", Integer.toString(player.getPing()),
                "%tps%", HudContext.readTps(config.displayFormat().contains("%tps%")));
    }
}
