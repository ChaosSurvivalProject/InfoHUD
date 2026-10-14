package dev.infohud.placeholder;

import java.util.List;
import java.util.Locale;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import dev.infohud.InfoHUD;
import dev.infohud.config.PluginConfig;
import dev.infohud.time.TimeUtil;
import dev.infohud.util.BiomeUtil;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;

/**
 * PlaceholderAPI 扩展（可选集成，装了才会注册）。
 *
 * <p>占位符：</p>
 * <ul>
 *   <li>{@code %infohud_gametime%} —— 游戏时间 HH:MM</li>
 *   <li>{@code %infohud_realtime%} —— 现实时间（配置时区）</li>
 *   <li>{@code %infohud_daynight%} —— 时段标签</li>
 *   <li>{@code %infohud_biome%} —— 当前群系显示名（中文映射）</li>
 *   <li>{@code %infohud_biome_id%} —— 当前群系原始 ID</li>
 *   <li>{@code %infohud_world%} —— 当前世界名</li>
 * </ul>
 *
 * <p>群系一律取自 {@code BiomeService} 的缓存：PlaceholderAPI 可能在异步线程解析占位符，
 * 直接读区块会踩线程安全问题，缓存为空时返回占位符 {@code --}。</p>
 */
public final class InfoHUDExpansion extends PlaceholderExpansion {

    private final InfoHUD plugin;

    public InfoHUDExpansion(InfoHUD plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "infohud";
    }

    @Override
    public String getAuthor() {
        return "AutoMods";
    }

    @Override
    public String getVersion() {
        return plugin.pluginVersion();
    }

    @Override
    public String getRequiredPlugin() {
        return "InfoHUD";
    }

    /** 服务器重启后仍保留注册（PAPI 会自行恢复）。 */
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public List<String> getPlaceholders() {
        return List.of(
                "%infohud_gametime% - 游戏时间 HH:MM",
                "%infohud_realtime% - 现实时间",
                "%infohud_daynight% - 昼夜时段",
                "%infohud_biome% - 当前群系显示名",
                "%infohud_biome_id% - 当前群系 ID",
                "%infohud_world% - 当前世界名");
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (params == null) {
            return null;
        }
        String key = params.toLowerCase(Locale.ROOT);
        PluginConfig config = plugin.config();

        if (key.equals("realtime")) {
            // 与现实时间无关玩家状态，离线也能给值
            return TimeUtil.realTime(config.timezone(), config.realtimeFormatter());
        }

        Player online = player == null ? null : player.getPlayer();
        if (online == null || !online.isOnline()) {
            return "";
        }

        switch (key) {
            case "gametime":
                return TimeUtil.gameTime(online.getWorld().getTime());
            case "daynight":
                long rawTime = online.getWorld().getTime();
                return config.dayNightRules().label(config.dayNightRules().phase(rawTime));
            case "biome":
            case "biome_name":
                return BiomeUtil.displayName(plugin.biomes().cachedBiome(online.getUniqueId()), config);
            case "biome_id":
                return BiomeUtil.idOf(plugin.biomes().cachedBiome(online.getUniqueId()));
            case "world":
                return online.getWorld().getName();
            default:
                return null;
        }
    }
}
