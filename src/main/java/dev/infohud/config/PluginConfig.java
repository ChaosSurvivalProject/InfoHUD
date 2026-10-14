package dev.infohud.config;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

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

    /** 性能红线：ActionBar 最快 0.5 秒刷一次。 */
    public static final int MIN_INTERVAL_TICKS = 10;

    private static final String DEFAULT_DISPLAY_FORMAT =
            "&7游戏时间 &f%gametime% &8| &7现实时间 &f%realtime% &8| &7%daynight% &8| &7群系 &f%biome%";
    private static final String DEFAULT_NOTIFY_FORMAT = "&a[InfoHUD] &e已到达 &b%biome%";
    private static final String DEFAULT_REALTIME_PATTERN = "HH:mm:ss";
    private static final String FALLBACK_ZONE = "UTC";

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
    private final double minMoveDistance;
    private final int queryCooldownTicks;
    private final boolean loadedChunkOnly;
    private final boolean notifyOnWorldChange;

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
        double distance = config.getDouble("biome-notification.min-move-distance", 5.0D);
        this.minMoveDistance = distance < 0 ? 0 : distance;
        int cooldown = config.getInt("biome-notification.query-cooldown-ticks", 5);
        this.queryCooldownTicks = clamp(cooldown, 0, 100);
        this.loadedChunkOnly = config.getBoolean("biome-notification.loaded-chunk-only", true);
        this.notifyOnWorldChange = config.getBoolean("biome-notification.notify-on-world-change", true);

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
