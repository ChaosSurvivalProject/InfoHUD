package dev.infohud.config;

import java.util.EnumMap;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;

import dev.infohud.time.DayNightPhase;
import dev.infohud.time.TimeUtil;

/**
 * 昼夜时段判定规则：区间边界 + 时段标签，全部来自 {@code day-night} 配置节。
 *
 * <p>判定优先级为 日出 → 白天 → 日落 → 黑夜。由于原版 23000~24000 天色已经开始发亮，
 * 而配置里 night-end 默认是 23000，两段存在重叠；按上面的优先级，重叠区归为“日出”，
 * 与需求描述（tick 0~1000 或 22000~24000 → 日出）一致。可用
 * {@code sunrise-late-start} 调整或关闭这段回绕。</p>
 */
public final class DayNightRules {

    private final int sunriseStart;
    private final int sunriseEnd;
    /** 夜间尾段也算日出的起点；负数表示禁用该回绕区间。 */
    private final int sunriseLateStart;
    private final int dayStart;
    private final int dayEnd;
    private final int sunsetStart;
    private final int sunsetEnd;
    private final int nightStart;
    private final int nightEnd;

    private final Map<DayNightPhase, String> labels;

    private DayNightRules(int sunriseStart, int sunriseEnd, int sunriseLateStart,
                          int dayStart, int dayEnd,
                          int sunsetStart, int sunsetEnd,
                          int nightStart, int nightEnd,
                          Map<DayNightPhase, String> labels) {
        this.sunriseStart = sunriseStart;
        this.sunriseEnd = sunriseEnd;
        this.sunriseLateStart = sunriseLateStart;
        this.dayStart = dayStart;
        this.dayEnd = dayEnd;
        this.sunsetStart = sunsetStart;
        this.sunsetEnd = sunsetEnd;
        this.nightStart = nightStart;
        this.nightEnd = nightEnd;
        this.labels = labels;
    }

    /**
     * 从配置节解析规则；缺失或非法（越界 / 起点大于终点）的项回退到原版默认值。
     */
    public static DayNightRules fromSection(ConfigurationSection section) {
        int sunriseStart = bounded(section, "sunrise-start", 0, 0, TimeUtil.DAY_TICKS);
        int sunriseEnd = bounded(section, "sunrise-end", 1000, 0, TimeUtil.DAY_TICKS);
        // 默认 22000；配置写 -1 就是关掉“夜间尾段也算日出”
        int sunriseLateStart = bounded(section, "sunrise-late-start", 22000, -1, TimeUtil.DAY_TICKS);
        int dayStart = bounded(section, "day-start", 1000, 0, TimeUtil.DAY_TICKS);
        int dayEnd = bounded(section, "day-end", 11000, 0, TimeUtil.DAY_TICKS);
        int sunsetStart = bounded(section, "sunset-start", 11000, 0, TimeUtil.DAY_TICKS);
        int sunsetEnd = bounded(section, "sunset-end", 13000, 0, TimeUtil.DAY_TICKS);
        int nightStart = bounded(section, "night-start", 13000, 0, TimeUtil.DAY_TICKS);
        int nightEnd = bounded(section, "night-end", 23000, 0, TimeUtil.DAY_TICKS);

        // 起点 > 终点属于明显笔误，交换一下而不是直接崩掉
        if (sunriseStart > sunriseEnd) {
            int tmp = sunriseStart;
            sunriseStart = sunriseEnd;
            sunriseEnd = tmp;
        }
        if (dayStart > dayEnd) {
            int tmp = dayStart;
            dayStart = dayEnd;
            dayEnd = tmp;
        }
        if (sunsetStart > sunsetEnd) {
            int tmp = sunsetStart;
            sunsetStart = sunsetEnd;
            sunsetEnd = tmp;
        }
        if (nightStart > nightEnd) {
            int tmp = nightStart;
            nightStart = nightEnd;
            nightEnd = tmp;
        }

        Map<DayNightPhase, String> labels = new EnumMap<>(DayNightPhase.class);
        ConfigurationSection labelSection = section == null ? null : section.getConfigurationSection("labels");
        for (DayNightPhase phase : DayNightPhase.values()) {
            String configured = labelSection == null ? null : labelSection.getString(phase.name().toLowerCase(java.util.Locale.ROOT));
            labels.put(phase, configured == null || configured.isEmpty() ? phase.fallbackLabel() : configured);
        }

        return new DayNightRules(sunriseStart, sunriseEnd, sunriseLateStart,
                dayStart, dayEnd, sunsetStart, sunsetEnd, nightStart, nightEnd, labels);
    }

    /**
     * 根据 tick 判定当前时段。
     *
     * @param rawTime {@code World#getTime()} 原始值
     * @return 时段枚举
     */
    public DayNightPhase phase(long rawTime) {
        int tick = TimeUtil.wrap(rawTime);
        if (contains(tick, sunriseStart, sunriseEnd)) {
            return DayNightPhase.SUNRISE;
        }
        if (sunriseLateStart >= 0 && tick >= sunriseLateStart && tick < TimeUtil.DAY_TICKS) {
            return DayNightPhase.SUNRISE;
        }
        if (contains(tick, dayStart, dayEnd)) {
            return DayNightPhase.DAY;
        }
        if (contains(tick, sunsetStart, sunsetEnd)) {
            return DayNightPhase.SUNSET;
        }
        if (contains(tick, nightStart, nightEnd)) {
            return DayNightPhase.NIGHT;
        }
        // 配置留白（例如 night-end 改小且没开回绕）时，尾部仍算黑夜，避免出现空档
        if (tick >= nightEnd) {
            return DayNightPhase.NIGHT;
        }
        return DayNightPhase.DAY;
    }

    /** 时段对应的显示标签（可能带颜色代码，交给 TextUtil 解析）。 */
    public String label(DayNightPhase phase) {
        return labels.getOrDefault(phase, phase.fallbackLabel());
    }

    /** 左闭右开区间判定。 */
    private static boolean contains(int tick, int start, int end) {
        return tick >= start && tick < end;
    }

    private static int bounded(ConfigurationSection section, String path, int fallback, int min, int max) {
        if (section == null || !section.isInt(path)) {
            return fallback;
        }
        int value = section.getInt(path, fallback);
        if (value < min || value > max) {
            return fallback;
        }
        return value;
    }
}
