package dev.infohud.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.infohud.TestConfigs;
import dev.infohud.time.WorldDate;

/**
 * 世界历法换算：天数 → 年/月/日（现实闰年规则）+ 月份 → 季节键名/显示名。
 */
class CalendarRulesTest {

    /** 平年每月天数（下标 0 = 1 月）。 */
    private static final int[] COMMON_YEAR_DAYS = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};

    /** 第 1 天（= 起始年 1 月 1 日）之前已经过去的天数。 */
    private static int daysBefore(int month) {
        int sum = 0;
        for (int i = 0; i < month - 1; i++) {
            sum += COMMON_YEAR_DAYS[i];
        }
        return sum;
    }

    @Test
    @DisplayName("默认起始年份为 1，第 1 天是 1 年 1 月 1 日")
    void defaultStartYear() {
        CalendarRules rules = TestConfigs.defaultConfig().calendarRules();
        assertEquals(1, rules.startYear());

        WorldDate first = rules.resolve(1);
        assertEquals(1, first.year());
        assertEquals(1, first.month());
        assertEquals(1, first.day());
    }

    @Test
    @DisplayName("平年：跨月边界按 31/28/31/30 推进")
    void commonYearMonthBoundaries() {
        CalendarRules rules = TestConfigs.defaultConfig().calendarRules();

        // 1 月有 31 天：第 31 天还是 1 月 31 日，第 32 天进入 2 月
        assertEquals(1, rules.resolve(31).month());
        assertEquals(31, rules.resolve(31).day());
        WorldDate feb = rules.resolve(32);
        assertEquals(2, feb.month());
        assertEquals(1, feb.day());

        // 平年 2 月 28 天 → 第 59 天是 2 月 28 日，第 60 天是 3 月 1 日
        assertEquals(28, rules.resolve(31 + 28).day());
        WorldDate mar = rules.resolve(31 + 28 + 1);
        assertEquals(3, mar.month());
        assertEquals(1, mar.day());

        // 平年共 365 天 → 第 365 天是 12 月 31 日，第 366 天进入下一年
        assertEquals(12, rules.resolve(365).month());
        assertEquals(31, rules.resolve(365).day());
        assertEquals(2, rules.resolve(366).year());
        assertEquals(1, rules.resolve(366).month());
        assertEquals(1, rules.resolve(366).day());
    }

    @Test
    @DisplayName("闰年按现实规则：1900 不是闰年，2000 是闰年")
    void leapYearRules() {
        CalendarRules from1900 = TestConfigs.config("""
                calendar:
                  start-year: 1900
                """).calendarRules();
        // 1900 平年：第 59 天仍是 2 月 28 日，第 60 天是 3 月 1 日
        assertEquals(2, from1900.resolve(59).month());
        assertEquals(28, from1900.resolve(59).day());
        assertEquals(3, from1900.resolve(60).month());
        assertEquals(1, from1900.resolve(60).day());
        // 全年 365 天
        assertEquals(1900, from1900.resolve(365).year());
        assertEquals(1901, from1900.resolve(366).year());

        CalendarRules from2000 = TestConfigs.config("""
                calendar:
                  start-year: 2000
                """).calendarRules();
        // 2000 闰年：第 60 天是 2 月 29 日，第 61 天是 3 月 1 日
        assertEquals(2, from2000.resolve(60).month());
        assertEquals(29, from2000.resolve(60).day());
        assertEquals(3, from2000.resolve(61).month());
        assertEquals(1, from2000.resolve(61).day());
        // 全年 366 天
        assertEquals(12, from2000.resolve(366).month());
        assertEquals(31, from2000.resolve(366).day());
        assertEquals(2000, from2000.resolve(366).year());
        assertEquals(2001, from2000.resolve(367).year());
    }

    @Test
    @DisplayName("闰年跨过 2 月后，同一天数比平年晚一天进 3 月")
    void leapYearShiftsLaterMonths() {
        CalendarRules leap = TestConfigs.config("""
                calendar:
                  start-year: 2000
                """).calendarRules();
        // 闰年 3 月 1 日是第 61 天；之后每个月都比平年整体后移一天，
        // 所以 4 月 30 日在闰年是第 121 天，平年只有第 120 天
        WorldDate apr30 = leap.resolve(121);
        assertEquals(4, apr30.month());
        assertEquals(30, apr30.day());

        CalendarRules common = TestConfigs.config("""
                calendar:
                  start-year: 1900
                """).calendarRules();
        assertEquals(4, common.resolve(120).month());
        assertEquals(30, common.resolve(120).day());
    }

    @Test
    @DisplayName("默认季节键名：12/1/2 冬、3/4/5 春、6/7/8 夏、9/10/11 秋")
    void defaultSeasons() {
        CalendarRules rules = TestConfigs.defaultConfig().calendarRules();
        String[] expected = {
                "winter", "winter", "spring", "spring", "spring",
                "summer", "summer", "summer", "autumn", "autumn", "autumn", "winter"
        };
        for (int month = 1; month <= 12; month++) {
            assertEquals(expected[month - 1], rules.seasonOfMonth(month), "month=" + month);
        }

        WorldDate first = rules.resolve(1);
        assertEquals("winter", first.season());
        assertEquals("&b冬季", first.seasonName());

        // 第 60 天 = 3 月 1 日（平年）→ spring
        WorldDate spring = rules.resolve(daysBefore(3) + 1);
        assertEquals(3, spring.month());
        assertEquals("spring", spring.season());
        assertEquals("&a春季", spring.seasonName());

        assertEquals("summer", rules.resolve(daysBefore(6) + 1).season());
        assertEquals("autumn", rules.resolve(daysBefore(9) + 1).season());

        // 第 336 天 = 12 月 1 日 → 回到 winter
        WorldDate december = rules.resolve(daysBefore(12) + 1);
        assertEquals(12, december.month());
        assertEquals("winter", december.season());
        assertEquals("&b冬季", december.seasonName());
    }

    @Test
    @DisplayName("季节键名与显示名都可以配置，未配置的保留默认")
    void customSeasons() {
        CalendarRules rules = TestConfigs.config("""
                calendar:
                  start-year: 1900
                  month-seasons:
                    1: rainy
                    2: rainy
                    12: cold
                  season-names:
                    rainy: "雨季"
                    cold: "寒冬"
                    spring: "春天"
                """).calendarRules();

        // 改过的月份按新键名，没改的月份保留默认
        assertEquals("rainy", rules.seasonOfMonth(1));
        assertEquals("rainy", rules.seasonOfMonth(2));
        assertEquals("cold", rules.seasonOfMonth(12));
        assertEquals("spring", rules.seasonOfMonth(3));
        assertEquals("summer", rules.seasonOfMonth(7));

        assertEquals("雨季", rules.seasonName("rainy"));
        assertEquals("寒冬", rules.seasonName("cold"));
        assertEquals("春天", rules.seasonName("spring"));
        // 未改的显示名保留内置默认（默认带颜色）
        assertEquals("&e夏季", rules.seasonName("summer"));
        assertEquals("&b冬季", rules.seasonName("winter"));
        // 完全没配显示名的键名退化成键名本身，不会输出 null
        assertEquals("monsoon", rules.seasonName("monsoon"));
    }

    @Test
    @DisplayName("季节键名大小写不敏感，输出统一为小写")
    void seasonKeysAreCaseInsensitive() {
        CalendarRules rules = TestConfigs.config("""
                calendar:
                  month-seasons:
                    1: Winter
                    2: SPRING
                  season-names:
                    Winter: "寒冬"
                """).calendarRules();

        assertEquals("winter", rules.seasonOfMonth(1));
        assertEquals("spring", rules.seasonOfMonth(2));
        assertEquals("寒冬", rules.seasonName("winter"));
        assertEquals("寒冬", rules.seasonName("WINTER"));
        assertEquals("winter", rules.resolve(1).season());
        assertEquals("寒冬", rules.resolve(1).seasonName());
    }

    @Test
    @DisplayName("month-seasons 也支持按 1~12 月顺序的列表写法")
    void monthSeasonsAsList() {
        CalendarRules rules = TestConfigs.config("""
                calendar:
                  month-seasons: [winter, winter, spring, spring, spring,
                                  summer, summer, summer, autumn, autumn, autumn, winter]
                """).calendarRules();
        assertEquals("winter", rules.seasonOfMonth(1));
        assertEquals("spring", rules.seasonOfMonth(5));
        assertEquals("summer", rules.seasonOfMonth(8));
        assertEquals("autumn", rules.seasonOfMonth(10));
    }

    @Test
    @DisplayName("季节键名列表包含默认四项与自定义键名")
    void seasonKeysListed() {
        CalendarRules rules = TestConfigs.config("""
                calendar:
                  month-seasons:
                    1: rainy
                  season-names:
                    rainbow: "彩虹季"
                """).calendarRules();
        List<String> keys = rules.seasonKeys();
        assertTrue(keys.containsAll(List.of("spring", "summer", "autumn", "winter")), keys.toString());
        assertTrue(keys.contains("rainy"), keys.toString());
        assertTrue(keys.contains("rainbow"), keys.toString());
    }

    @Test
    @DisplayName("日期文本按 yyyy-MM-dd 输出，月/日补零")
    void isoDateText() {
        CalendarRules rules = TestConfigs.defaultConfig().calendarRules();
        assertEquals("0001-01-01", rules.resolve(1).isoDate());

        // 第 60 天 = 3 月 1 日（平年）：月/日补成两位
        assertEquals("0001-03-01", rules.resolve(60).isoDate());
        // 第 99 天 = 4 月 9 日
        assertEquals("0001-04-09", rules.resolve(99).isoDate());
        // 第 365 天 = 12 月 31 日
        assertEquals("0001-12-31", rules.resolve(365).isoDate());
        // 第 366 天 = 第 2 年 1 月 1 日
        assertEquals("0002-01-01", rules.resolve(366).isoDate());

        CalendarRules from1900 = TestConfigs.config("""
                calendar:
                  start-year: 1900
                """).calendarRules();
        assertEquals("1900-01-01", from1900.resolve(1).isoDate());

        CalendarRules from2000 = TestConfigs.config("""
                calendar:
                  start-year: 2000
                """).calendarRules();
        // 闰年 2 月 29 日
        assertEquals("2000-02-29", from2000.resolve(60).isoDate());
        assertEquals("2000-03-01", from2000.resolve(61).isoDate());
    }

    @Test
    @DisplayName("天数异常（0 或负数）按第 1 天处理，不会跑出公历范围")
    void clampsInvalidDays() {
        CalendarRules rules = TestConfigs.defaultConfig().calendarRules();
        assertEquals(1, rules.resolve(0).day());
        assertEquals(1, rules.resolve(-100).month());
        assertEquals(1, rules.resolve(Long.MIN_VALUE).year());
        assertEquals(1, rules.resolve(Long.MAX_VALUE).year());
    }
}
