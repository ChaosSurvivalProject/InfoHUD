package dev.infohud.placeholder;

import java.util.List;
import java.util.Locale;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import dev.infohud.InfoHUD;
import dev.infohud.config.PluginConfig;
import dev.infohud.time.TimeUtil;
import dev.infohud.time.WorldDate;
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
 *   <li>{@code %infohud_world_days%} —— 当前世界天数（从 1 开始）</li>
 *   <li>{@code %infohud_world_year%} / {@code _month%} / {@code _day%} —— 世界历法的年 / 月 / 日；
 *       {@code %infohud_world_date%} —— 游戏日期（{@code yyyy-MM-dd}）</li>
 *   <li>{@code %infohud_world_season%} —— 季节键名（如 {@code spring}）；
 *       {@code %infohud_world_season_name%} —— 季节显示名（如 {@code 春季}）</li>
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

    /**
     * Tab 补全用的占位符清单。
     *
     * <p>只列<b>裸变量名</b>（不带 {@code " - 说明"}）：PAPI 的 {@code /papi parse} 补全会把
     * 整条字符串塞进输入位置，带说明就没法直接用了。各变量的含义见 README 的占位符表。</p>
     */
    @Override
    public List<String> getPlaceholders() {
        return List.of(
                "%infohud_gametime%",
                "%infohud_realtime%",
                "%infohud_daynight%",
                "%infohud_biome%",
                "%infohud_biome_id%",
                "%infohud_world%",
                "%infohud_world_days%",
                "%infohud_world_year%",
                "%infohud_world_month%",
                "%infohud_world_day%",
                "%infohud_world_date%",
                "%infohud_world_season%",
                "%infohud_world_season_name%");
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
            case "world_days":
            case "worlddays":
                return TimeUtil.worldDays(online.getWorld().getFullTime());
            case "world_year":
            case "worldyear":
                return Integer.toString(worldDate(online, config).year());
            case "world_month":
            case "worldmonth":
                return Integer.toString(worldDate(online, config).month());
            case "world_day":
            case "worldday":
                return Integer.toString(worldDate(online, config).day());
            case "world_date":
            case "worlddate":
                return worldDate(online, config).isoDate();
            case "world_season":
            case "worldseason":
                return worldDate(online, config).season();
            case "world_season_name":
            case "worldseasonname":
                return worldDate(online, config).seasonName();
            default:
                return null;
        }
    }

    /** 当前世界天数换算出的日期与季节。 */
    private static WorldDate worldDate(Player player, PluginConfig config) {
        return config.calendarRules().resolve(TimeUtil.worldDay(player.getWorld().getFullTime()));
    }
}
