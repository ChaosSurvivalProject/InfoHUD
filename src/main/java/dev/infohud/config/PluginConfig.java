package dev.infohud.config;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * 配置快照。
 *
 * <p>{@code /infohud reload} 时会构造一个新实例整体替换旧引用，因此运行期的读取
 * 不需要任何锁；主类里的字段声明为 {@code volatile} 即可保证可见性。</p>
 *
 * <p>所有取值都带兜底默认值：配置写错（缺字段、时区名非法、时间格式非法、间隔过小）
 * 只会打日志并使用安全值，不会让插件启动失败。</p>
 */
public final class PluginConfig {

    /** HUD 文本的解析模式。 */
    public enum TextMode {
        /** 出现 MiniMessage 标签就按 MiniMessage，否则按传统 §/& 代码。 */
        AUTO,
        /** 只按传统 §/& 代码。 */
        LEGACY,
        /** 强制 MiniMessage（传统代码会被自动转换成 MiniMessage 标签）。 */
        MINIMESSAGE;

        static TextMode parse(String raw, Logger logger) {
            if (raw == null) {
                return AUTO;
            }
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                if (logger != null) {
                    logger.warning("hud.text-mode 取值 \"" + raw + "\" 无效（可用 auto/legacy/minimessage），已按 auto 处理。");
                }
                return AUTO;
            }
        }
    }

    /** 群系切换提示的展示方式。 */
    public enum NotificationMode {
        /** 聊天栏消息。 */
        CHAT,
        /** 屏幕中央大字（title/subtitle）。 */
        CENTER;

        static NotificationMode parse(String raw, Logger logger) {
            if (raw == null) {
                return CENTER;
            }
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                if (logger != null) {
                    logger.warning("biome-notification.mode 取值 \"" + raw + "\" 无效（可用 chat/center），已按 center 处理。");
                }
                return CENTER;
            }
        }
    }

    /** 性能红线：ActionBar 最快 0.5 秒刷一次。 */
    public static final int MIN_INTERVAL_TICKS = 10;

    private static final String DEFAULT_CENTER_TITLE = "&e进入 &b%biome%";
    private static final int DEFAULT_CENTER_DURATION_SECONDS = 3;

    private static final String DEFAULT_DISPLAY_FORMAT =
            "&7游戏时间 &f%gametime% &8| &7现实时间 &f%realtime% &8| &7%daynight% &8| &7群系 &f%biome%";
    private static final String DEFAULT_NOTIFY_FORMAT = "&a[InfoHUD] &e已到达 &b%biome%";
    private static final String DEFAULT_REALTIME_PATTERN = "HH:mm:ss";
    private static final String FALLBACK_ZONE = "UTC";

    // ---- bar（受击生物血条）----
    private static final String DEFAULT_BAR_LIVING_TITLE = "<gray><health>/<max> <reset><name> -<damage><red>♥";
    private static final String DEFAULT_BAR_DEAD_TITLE =
            "<gray><health>/<max> <dark_gray>☠ <name> ☠ <reset>-<damage><red>♥";
    private static final int DEFAULT_BAR_TIME_TICKS = 100;
    private static final int MIN_BAR_TIME_TICKS = 20;
    private static final int MAX_BAR_TIME_TICKS = 2000;
    private static final List<String> DEFAULT_BAR_BLACKLIST =
            List.of("ENDER_DRAGON", "WITHER", "PLAYER", "ARMOR_STAND");

    // ---- hud ----
    private final boolean enabledByDefault;
    private final int updateIntervalTicks;
    private final String displayFormat;
    private final ZoneId timezone;
    private final DateTimeFormatter realtimeFormatter;
    private final TextMode textMode;
    private final boolean ampersandColors;
    private final boolean placeholderApiParse;
    private final boolean cacheComponents;

    // ---- day-night ----
    private final DayNightRules dayNightRules;

    // ---- biome-notification ----
    private final boolean notifyEnabled;
    private final String notifyFormat;
    private final NotificationMode notifyMode;
    private final String centerTitle;
    private final int centerDurationSeconds;
    private final double minMoveDistance;
    private final int queryCooldownTicks;
    private final boolean loadedChunkOnly;
    private final boolean notifyOnWorldChange;

    // ---- compass ----
    private final boolean compassEnabledByDefault;
    private final int compassUpdateIntervalTicks;
    private final BarColor compassColor;
    private final BarStyle compassStyle;

    // ---- bar（受击生物血条）----
    private final boolean healthBarEnabled;
    private final boolean healthBarEnabledByDefault;
    private final String healthBarLivingTitle;
    private final String healthBarDeadTitle;
    private final BarStyle healthBarOverlay;
    private final BarColor healthBarColor;
    private final int healthBarTimeTicks;
    private final Set<String> healthBarBlacklist;

    // ---- storage ----
    private final boolean preferencesInConfig;
    private final long saveDebounceMillis;

    // ---- biome-names ----
    private final Map<String, String> biomeNames;

    private PluginConfig(FileConfiguration config, Logger logger) {
        // ---------- hud ----------
        this.enabledByDefault = config.getBoolean("hud.enabled-by-default", true);
        int interval = config.getInt("hud.update-interval-ticks", 20);
        if (interval < MIN_INTERVAL_TICKS) {
            if (logger != null) {
                logger.warning("hud.update-interval-ticks=" + interval + " 过小，已按最小值 " + MIN_INTERVAL_TICKS + " tick 执行。");
            }
            interval = MIN_INTERVAL_TICKS;
        }
        // 上限 200 tick（10 秒），防止填了个离谱的值导致 HUD 看起来“卡死”
        if (interval > 200) {
            interval = 200;
        }
        this.updateIntervalTicks = interval;

        String format = config.getString("hud.display-format", DEFAULT_DISPLAY_FORMAT);
        this.displayFormat = (format == null || format.isEmpty()) ? DEFAULT_DISPLAY_FORMAT : format;

        this.timezone = parseZone(config.getString("hud.timezone", FALLBACK_ZONE), logger);

        String pattern = config.getString("hud.realtime-format", DEFAULT_REALTIME_PATTERN);
        this.realtimeFormatter = parseFormatter(pattern, logger);

        this.textMode = TextMode.parse(config.getString("hud.text-mode", "auto"), logger);
        this.ampersandColors = config.getBoolean("hud.ampersand-colors", true);
        this.placeholderApiParse = config.getBoolean("hud.placeholderapi-parse", true);
        this.cacheComponents = config.getBoolean("hud.cache-components", true);

        // ---------- day-night ----------
        ConfigurationSection dayNight = config.getConfigurationSection("day-night");
        this.dayNightRules = DayNightRules.fromSection(dayNight);

        // ---------- biome-notification ----------
        this.notifyEnabled = config.getBoolean("biome-notification.enabled", true);
        String notifyFormatRaw = config.getString("biome-notification.message-format", DEFAULT_NOTIFY_FORMAT);
        this.notifyFormat = (notifyFormatRaw == null || notifyFormatRaw.isEmpty()) ? DEFAULT_NOTIFY_FORMAT : notifyFormatRaw;
        this.notifyMode = NotificationMode.parse(
                config.getString("biome-notification.mode", "center"), logger);
        String centerTitleRaw = config.getString("biome-notification.center-title", DEFAULT_CENTER_TITLE);
        this.centerTitle = (centerTitleRaw == null || centerTitleRaw.isEmpty()) ? DEFAULT_CENTER_TITLE : centerTitleRaw;
        this.centerDurationSeconds = clamp(config.getInt("biome-notification.center-duration-seconds",
                DEFAULT_CENTER_DURATION_SECONDS), 1, 10);
        double distance = config.getDouble("biome-notification.min-move-distance", 5.0D);
        this.minMoveDistance = distance < 0 ? 0 : distance;
        int cooldown = config.getInt("biome-notification.query-cooldown-ticks", 5);
        this.queryCooldownTicks = clamp(cooldown, 0, 100);
        this.loadedChunkOnly = config.getBoolean("biome-notification.loaded-chunk-only", true);
        this.notifyOnWorldChange = config.getBoolean("biome-notification.notify-on-world-change", true);

        // ---------- compass ----------
        this.compassEnabledByDefault = config.getBoolean("compass.enabled-by-default", true);
        int compassInterval = config.getInt("compass.update-interval-ticks", 2);
        this.compassUpdateIntervalTicks = clamp(compassInterval, 1, 200);
        this.compassColor = parseBarColor(config.getString("compass.boss-bar-color", "WHITE"),
                "compass.boss-bar-color", BarColor.WHITE, logger);
        this.compassStyle = parseBarStyle(config.getString("compass.boss-bar-style", "SEGMENTED_10"), logger);

        // ---------- bar（受击生物血条）----------
        this.healthBarEnabled = config.getBoolean("bar.enabled", true);
        // 玩家个人开关的默认值：bar.enabled 是服务端总闸，这里是"默认给不给玩家显示"
        this.healthBarEnabledByDefault = config.getBoolean("bar.enabled-by-default",
                this.healthBarEnabled);
        String livingTitle = config.getString("bar.title.living", DEFAULT_BAR_LIVING_TITLE);
        this.healthBarLivingTitle = (livingTitle == null || livingTitle.isEmpty()) ? DEFAULT_BAR_LIVING_TITLE : livingTitle;
        String deadTitle = config.getString("bar.title.dead", DEFAULT_BAR_DEAD_TITLE);
        this.healthBarDeadTitle = (deadTitle == null || deadTitle.isEmpty()) ? DEFAULT_BAR_DEAD_TITLE : deadTitle;
        this.healthBarOverlay = parseBarOverlay(config.getString("bar.overlay", "PROGRESS"), logger);
        this.healthBarColor = parseBarColor(config.getString("bar.color", "RED"), "bar.color", BarColor.RED, logger);
        this.healthBarTimeTicks = clamp(config.getInt("bar.time", DEFAULT_BAR_TIME_TICKS),
                MIN_BAR_TIME_TICKS, MAX_BAR_TIME_TICKS);
        List<String> rawBlacklist = config.getStringList("blacklist");
        this.healthBarBlacklist = readBarBlacklist(
                rawBlacklist.isEmpty() ? DEFAULT_BAR_BLACKLIST : rawBlacklist);

        // ---------- storage ----------
        this.preferencesInConfig = config.getBoolean("storage.preferences-in-config", false);
        int debounce = clamp(config.getInt("storage.save-debounce-seconds", 5), 0, 300);
        this.saveDebounceMillis = debounce * 1000L;

        // ---------- biome-names ----------
        this.biomeNames = readBiomeNames(config.getConfigurationSection("biome-names"));
    }

    /**
     * 解析并校验配置。
     *
     * @param config {@code Plugin#getConfig()}
     * @param logger 用于输出告警（可为 null）
     * @return 不可变的配置快照
     */
    public static PluginConfig load(FileConfiguration config, Logger logger) {
        return new PluginConfig(config, logger);
    }

    /**
     * 群系中文名。键为去掉 {@code minecraft:} 前缀后的 ID，大小写不敏感；
     * 未配置的群系返回 {@code null}，由调用方显示原始 ID。
     */
    public String biomeName(String biomeId) {
        if (biomeId == null || biomeNames.isEmpty()) {
            return null;
        }
        String name = biomeNames.get(biomeId.toLowerCase(Locale.ROOT));
        return name == null || name.isEmpty() ? null : name;
    }

    private static Map<String, String> readBiomeNames(ConfigurationSection section) {
        if (section == null) {
            return Collections.emptyMap();
        }
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : section.getValues(false).entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String text && !text.isEmpty()) {
                // 群系 ID 统一按小写存表，查表时也转小写，容忍配置里写成 Desert
                result.put(entry.getKey().toLowerCase(Locale.ROOT), text);
            }
        }
        return result.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(result);
    }

    private static ZoneId parseZone(String raw, Logger logger) {
        if (raw == null || raw.isEmpty()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(raw.trim());
        } catch (RuntimeException ex) {
            if (logger != null) {
                logger.warning("hud.timezone=\"" + raw + "\" 不是合法的时区 ID，已回退到服务器系统时区 "
                        + ZoneId.systemDefault().getId() + "。");
            }
            return ZoneId.systemDefault();
        }
    }

    private static DateTimeFormatter parseFormatter(String pattern, Logger logger) {
        if (pattern == null || pattern.isEmpty()) {
            pattern = DEFAULT_REALTIME_PATTERN;
        }
        try {
            // LENIENT + 可选字段：让 "HH:mm" 这类不带秒的写法也能正常工作
            return new DateTimeFormatterBuilder()
                    .parseLenient()
                    .appendPattern(pattern)
                    .toFormatter(Locale.ROOT);
        } catch (IllegalArgumentException ex) {
            if (logger != null) {
                logger.warning("hud.realtime-format=\"" + pattern + "\" 不是合法的 DateTimeFormatter 模式，已回退为 "
                        + DEFAULT_REALTIME_PATTERN + "。");
            }
            return DateTimeFormatter.ofPattern(DEFAULT_REALTIME_PATTERN, Locale.ROOT);
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** BossBar 颜色；写错回退 {@code fallback} 并告警。 */
    private static BarColor parseBarColor(String raw, String configKey, BarColor fallback, Logger logger) {
        if (raw == null) {
            return fallback;
        }
        try {
            return BarColor.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            if (logger != null) {
                logger.warning(configKey + "=\"" + raw + "\" 无效（可用 RED/GREEN/YELLOW/BLUE/PURPLE/PINK/WHITE），已按 "
                        + fallback + " 处理。");
            }
            return fallback;
        }
    }

    /**
     * 血条样式（{@code bar.overlay}）：同时接受参考配置的 Adventure 名
     * （PROGRESS / NOTCHED_6/10/12/20）与 Bukkit 原生名（SOLID / SEGMENTED_*），
     * 两者一一对应；写错回退 PROGRESS（实心）并告警。
     */
    private static BarStyle parseBarOverlay(String raw, Logger logger) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        switch (value) {
            case "":
            case "PROGRESS":
            case "SOLID":
                return BarStyle.SOLID;
            case "NOTCHED_6":
            case "SEGMENTED_6":
                return BarStyle.SEGMENTED_6;
            case "NOTCHED_10":
            case "SEGMENTED_10":
                return BarStyle.SEGMENTED_10;
            case "NOTCHED_12":
            case "SEGMENTED_12":
                return BarStyle.SEGMENTED_12;
            case "NOTCHED_20":
            case "SEGMENTED_20":
                return BarStyle.SEGMENTED_20;
            default:
                if (logger != null) {
                    logger.warning("bar.overlay=\"" + raw + "\" 无效"
                            + "（可用 PROGRESS/SOLID、NOTCHED_6/10/12/20 或 SEGMENTED_6/10/12/20），已按 PROGRESS 处理。");
                }
                return BarStyle.SOLID;
        }
    }

    /**
     * 血条黑名单归一化：大小写不敏感、容忍 {@code minecraft:} 前缀，统一转大写存表。
     * 匹配端（{@code HealthBarService}）把实体类型的 {@code name()} 转大写后查这张表。
     */
    private static Set<String> readBarBlacklist(List<String> raw) {
        Set<String> result = new LinkedHashSet<>();
        for (String entry : raw) {
            if (entry == null) {
                continue;
            }
            String value = entry.trim();
            if (value.isEmpty()) {
                continue;
            }
            int colon = value.indexOf(':');
            if (colon >= 0 && value.substring(0, colon).equalsIgnoreCase("minecraft")) {
                value = value.substring(colon + 1);
            }
            result.add(value.toUpperCase(Locale.ROOT));
        }
        return result.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(result);
    }

    /** BossBar 样式；写错回退 SEGMENTED_10 并告警。 */
    private static BarStyle parseBarStyle(String raw, Logger logger) {
        if (raw == null) {
            return BarStyle.SEGMENTED_10;
        }
        try {
            return BarStyle.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            if (logger != null) {
                logger.warning("compass.boss-bar-style=\"" + raw + "\" 无效（可用 SOLID/SEGMENTED_6/SEGMENTED_10/SEGMENTED_12/SEGMENTED_20），已按 SEGMENTED_10 处理。");
            }
            return BarStyle.SEGMENTED_10;
        }
    }

    // ------------------------- getters -------------------------

    public boolean enabledByDefault() {
        return enabledByDefault;
    }

    public int updateIntervalTicks() {
        return updateIntervalTicks;
    }

    public String displayFormat() {
        return displayFormat;
    }

    public ZoneId timezone() {
        return timezone;
    }

    public DateTimeFormatter realtimeFormatter() {
        return realtimeFormatter;
    }

    public TextMode textMode() {
        return textMode;
    }

    public boolean ampersandColors() {
        return ampersandColors;
    }

    public boolean placeholderApiParse() {
        return placeholderApiParse;
    }

    public boolean cacheComponents() {
        return cacheComponents;
    }

    public DayNightRules dayNightRules() {
        return dayNightRules;
    }

    public boolean notifyEnabled() {
        return notifyEnabled;
    }

    public String notifyFormat() {
        return notifyFormat;
    }

    public NotificationMode notifyMode() {
        return notifyMode;
    }

    public String centerTitle() {
        return centerTitle;
    }

    /** 中央大字提示的停留时长（秒，1~10）。 */
    public int centerDurationSeconds() {
        return centerDurationSeconds;
    }

    public double minMoveDistance() {
        return minMoveDistance;
    }

    public int queryCooldownTicks() {
        return queryCooldownTicks;
    }

    public boolean loadedChunkOnly() {
        return loadedChunkOnly;
    }

    public boolean notifyOnWorldChange() {
        return notifyOnWorldChange;
    }

    /** 顶部罗盘的默认开关（玩家个人开关可覆盖，见 storage）。 */
    public boolean compassEnabledByDefault() {
        return compassEnabledByDefault;
    }

    /** 顶部罗盘刷新间隔（tick）。 */
    public int compassUpdateIntervalTicks() {
        return compassUpdateIntervalTicks;
    }

    public BarColor compassColor() {
        return compassColor;
    }

    public BarStyle compassStyle() {
        return compassStyle;
    }

    /** 受击生物血条总开关。 */
    public boolean healthBarEnabled() {
        return healthBarEnabled;
    }

    /**
     * 血条的<b>玩家默认</b>开关（个人开关可覆盖，见 storage）。
     *
     * <p>{@code bar.enabled} 是服务端总闸：关掉后任何玩家都收不到血条；
     * 这一项只在总闸开启时决定"默认给不给玩家看"。</p>
     */
    public boolean healthBarEnabledByDefault() {
        return healthBarEnabledByDefault;
    }

    /** 存活状态血条标题模板（占位符见 {@code config.yml} 的 bar 注释）。 */
    public String healthBarLivingTitle() {
        return healthBarLivingTitle;
    }

    /** 死亡状态血条标题模板。 */
    public String healthBarDeadTitle() {
        return healthBarDeadTitle;
    }

    public BarStyle healthBarOverlay() {
        return healthBarOverlay;
    }

    public BarColor healthBarColor() {
        return healthBarColor;
    }

    /** 血条停留时长（tick，已夹在 20~2000）。 */
    public int healthBarTimeTicks() {
        return healthBarTimeTicks;
    }

    /** 血条黑名单（大写归一化后的实体类型名；空表 = 不过滤）。 */
    public Set<String> healthBarBlacklist() {
        return healthBarBlacklist;
    }

    public boolean preferencesInConfig() {
        return preferencesInConfig;
    }

    public long saveDebounceMillis() {
        return saveDebounceMillis;
    }

    public Map<String, String> biomeNames() {
        return biomeNames;
    }

    /** 群系映射表条数，用于 status 命令展示。 */
    public int biomeNameCount() {
        return biomeNames.size();
    }
}
