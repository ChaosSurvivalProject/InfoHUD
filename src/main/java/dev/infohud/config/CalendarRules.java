package dev.infohud.config;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;

import dev.infohud.time.WorldDate;

/**
 * 世界历法：把“世界已流逝天数”换算成年 / 月 / 日，并按月份给出季节。
 *
 * <p>日期换算直接借用 {@link LocalDate}，因此闰年规则与现实完全一致
 * （能被 4 整除、但不能被 100 整除，或能被 400 整除的年份是闰年：
 * 1900 不是闰年，2000 是闰年）。世界第 1 天固定是起始年份的 1 月 1 日。</p>
 *
 * <p>季节只看月份，用<b>键名</b>表达（不涉及下标）：</p>
 * <ul>
 *   <li>{@code month-seasons}：月份 → 季节键名，默认
 *       12/1/2 月 = {@code winter}、3/4/5 = {@code spring}、6/7/8 = {@code summer}、9/10/11 = {@code autumn}；</li>
 *   <li>{@code season-names}：季节键名 → 显示名，默认
 *       {@code spring: &a春季}、{@code summer: &e夏季}、{@code autumn: &6秋季}、{@code winter: &b冬季}。</li>
 * </ul>
 *
 * <p>键名大小写不敏感（配置里写 {@code Spring} 也认），内部统一转小写，
 * 因此 {@code %world_season%} 输出的永远是配置里那个键名的小写形式，
 * 可以直接做等值判断。</p>
 */
public final class CalendarRules {

    /** 世界第 1 天默认落在 1 年（历法与日常说法通常从 1 年开始）。 */
    public static final int DEFAULT_START_YEAR = 1;

    /** 内置季节键名（顺序只用于“默认月份映射”的构建，不对外暴露为下标）。 */
    public static final String SPRING = "spring";
    public static final String SUMMER = "summer";
    public static final String AUTUMN = "autumn";
    public static final String WINTER = "winter";

    /** {@link LocalDate} 支持的最大年份；超出就直接夹住，避免换算抛异常。 */
    private static final int YEAR_MAX = 999_999_999;
    private static final int YEAR_MIN = -999_999_999;

    /**
     * 季节键名的默认显示名（与 config.yml 的 {@code calendar.season-names} 默认值一致）。
     *
     * <p>默认带颜色：春 &amp;a、夏 &amp;e、秋 &amp;6、冬 &amp;b。颜色只是默认视觉，
     * 配置里改了就以配置为准。</p>
     */
    private static final Map<String, String> DEFAULT_SEASON_NAMES = new LinkedHashMap<>();

    static {
        DEFAULT_SEASON_NAMES.put(SPRING, "&a春季");
        DEFAULT_SEASON_NAMES.put(SUMMER, "&e夏季");
        DEFAULT_SEASON_NAMES.put(AUTUMN, "&6秋季");
        DEFAULT_SEASON_NAMES.put(WINTER, "&b冬季");
    }

    /**
     * 月份 → 季节键名的默认值（下标 0 = 1 月）：
     * 12/1/2 月 = 冬季、3/4/5 = 春季、6/7/8 = 夏季、9/10/11 = 秋季。
     */
    private static final String[] DEFAULT_MONTH_SEASONS = {
            WINTER, WINTER, SPRING, SPRING, SPRING, SUMMER, SUMMER, SUMMER, AUTUMN, AUTUMN, AUTUMN, WINTER
    };

    private final int startYear;
    /** 下标 0 = 1 月（内部索引，与“季节下标”无关）。 */
    private final String[] monthSeasons;
    private final Map<String, String> seasonNames;

    private CalendarRules(int startYear, String[] monthSeasons, Map<String, String> seasonNames) {
        this.startYear = startYear;
        this.monthSeasons = monthSeasons;
        this.seasonNames = seasonNames;
    }

    /**
     * 从 {@code calendar} 配置节解析；节缺失（或某一项没配）时全部走内置默认值。
     *
     * @param section {@code calendar} 节点，可为 null
     */
    public static CalendarRules fromSection(ConfigurationSection section) {
        int startYear = DEFAULT_START_YEAR;
        if (section != null && section.isInt("start-year")) {
            startYear = clampYear(section.getInt("start-year", DEFAULT_START_YEAR));
        }

        String[] monthSeasons = DEFAULT_MONTH_SEASONS.clone();
        applyMonthSeasons(section, monthSeasons);

        return new CalendarRules(startYear, monthSeasons, readSeasonNames(section));
    }

    /** 世界第 1 天对应的年份。 */
    public int startYear() {
        return startYear;
    }

    /**
     * 把“世界已流逝天数”（从 1 开始）换算成具体日期与季节。
     *
     * @param totalDays 世界天数，小于 1 时按 1 处理
     * @return 年 / 月 / 日 + 季节键名与显示名
     */
    public WorldDate resolve(long totalDays) {
        long day = totalDays < 1L ? 1L : totalDays;
        LocalDate date;
        try {
            date = LocalDate.of(startYear, 1, 1).plusDays(day - 1L);
        } catch (DateTimeException | ArithmeticException ex) {
            // 天数大到超出公历可表示范围（现实里不可能发生）：退回起始年 1 月 1 日
            date = LocalDate.of(startYear, 1, 1);
        }
        String season = monthSeasons[date.getMonthValue() - 1];
        return new WorldDate(date.getYear(), date.getMonthValue(), date.getDayOfMonth(),
                season, seasonName(season));
    }

    /** 某个月（1~12）对应的季节键名。 */
    public String seasonOfMonth(int month) {
        int normalized = month < 1 ? 1 : Math.min(month, 12);
        return monthSeasons[normalized - 1];
    }

    /** 季节键名对应的显示名；没有为该键名配置显示名时退化成键名本身。 */
    public String seasonName(String season) {
        if (season == null) {
            return "";
        }
        String name = seasonNames.get(season.toLowerCase(Locale.ROOT));
        return name == null || name.isEmpty() ? season : name;
    }

    /** 全部季节键名（默认四项 + 配置里额外出现的键名），供调试/统计用。 */
    public List<String> seasonKeys() {
        List<String> keys = new ArrayList<>(seasonNames.keySet());
        for (String monthSeason : monthSeasons) {
            if (!keys.contains(monthSeason)) {
                keys.add(monthSeason);
            }
        }
        return keys;
    }

    // ------------------------- 解析 -------------------------

    /**
     * 读 {@code month-seasons}，两种写法都支持：
     * <ul>
     *   <li>映射：{@code 1: winter}、{@code 3: spring} …（键为月份 1~12）；</li>
     *   <li>列表：{@code [winter, winter, spring, ...]}（按 1 月到 12 月顺序）。</li>
     * </ul>
     * 没配的月份、以及写错的值（非字符串、空串）都保留默认值。
     */
    private static void applyMonthSeasons(ConfigurationSection section, String[] target) {
        if (section == null) {
            return;
        }
        if (section.isList("month-seasons")) {
            List<?> raw;
            try {
                raw = section.getList("month-seasons");
            } catch (RuntimeException ex) {
                return; // 结构异常：整项按默认值处理，不影响启动
            }
            if (raw == null) {
                return;
            }
            for (int i = 0; i < target.length && i < raw.size(); i++) {
                String season = normalizeSeason(raw.get(i));
                if (season != null) {
                    target[i] = season;
                }
            }
            return;
        }
        ConfigurationSection months = section.getConfigurationSection("month-seasons");
        if (months == null) {
            return;
        }
        for (String key : months.getKeys(false)) {
            int month = parseInt(key);
            if (month < 1 || month > 12) {
                continue;
            }
            String season = normalizeSeason(months.get(key));
            if (season != null) {
                target[month - 1] = season;
            }
        }
    }

    /** 默认四项显示名 + 配置覆盖（键是季节键名，大小写不敏感）。 */
    private static Map<String, String> readSeasonNames(ConfigurationSection section) {
        Map<String, String> names = new LinkedHashMap<>(DEFAULT_SEASON_NAMES);
        ConfigurationSection nameSection = section == null ? null : section.getConfigurationSection("season-names");
        if (nameSection == null) {
            return names;
        }
        for (String key : nameSection.getKeys(false)) {
            String season = normalizeSeason(key);
            String name = nameSection.getString(key);
            if (season == null || name == null || name.isEmpty()) {
                continue;
            }
            names.put(season, name);
        }
        return names;
    }

    /** 键名归一化：去空白 + 转小写；非字符串或空串返回 null（调用方保留默认值）。 */
    private static String normalizeSeason(Object raw) {
        if (!(raw instanceof String text)) {
            return null;
        }
        String value = text.trim().toLowerCase(Locale.ROOT);
        return value.isEmpty() ? null : value;
    }

    /** 解析整数键；不是整数时返回 -1。 */
    private static int parseInt(String raw) {
        if (raw == null) {
            return -1;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private static int clampYear(int year) {
        return Math.max(YEAR_MIN, Math.min(YEAR_MAX, year));
    }
}
