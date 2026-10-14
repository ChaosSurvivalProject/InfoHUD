package dev.infohud.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.infohud.TestConfigs;
import dev.infohud.time.DayNightPhase;

/**
 * 昼夜时段判定：边界值、重叠区（22000~24000 归为日出）、以及自定义边界。
 */
class DayNightRulesTest {

    @Test
    @DisplayName("默认配置下的四个时段与需求一致")
    void defaultPhases() {
        DayNightRules rules = TestConfigs.defaultConfig().dayNightRules();

        assertEquals(DayNightPhase.SUNRISE, rules.phase(0));
        assertEquals(DayNightPhase.SUNRISE, rules.phase(999));
        assertEquals(DayNightPhase.DAY, rules.phase(1000));
        assertEquals(DayNightPhase.DAY, rules.phase(6000));
        assertEquals(DayNightPhase.DAY, rules.phase(10999));
        assertEquals(DayNightPhase.SUNSET, rules.phase(11000));
        assertEquals(DayNightPhase.SUNSET, rules.phase(12999));
        assertEquals(DayNightPhase.NIGHT, rules.phase(13000));
        assertEquals(DayNightPhase.NIGHT, rules.phase(21999));
        // 与需求“22000~24000 → 日出”一致：这一段优先算日出
        assertEquals(DayNightPhase.SUNRISE, rules.phase(22000));
        assertEquals(DayNightPhase.SUNRISE, rules.phase(23999));
    }

    @Test
    @DisplayName("tick 越界也能正确落回时段")
    void wrappedTicks() {
        DayNightRules rules = TestConfigs.defaultConfig().dayNightRules();
        assertEquals(DayNightPhase.SUNRISE, rules.phase(24000));
        assertEquals(DayNightPhase.DAY, rules.phase(25000));
        assertEquals(DayNightPhase.SUNRISE, rules.phase(-1));
    }

    @Test
    @DisplayName("关闭回绕后，22000 之后算黑夜")
    void disableLateSunrise() {
        DayNightRules rules = TestConfigs.config("""
                day-night:
                  sunrise-start: 0
                  sunrise-end: 1000
                  sunrise-late-start: -1
                  day-start: 1000
                  day-end: 11000
                  sunset-start: 11000
                  sunset-end: 13000
                  night-start: 13000
                  night-end: 23000
                """).dayNightRules();
        assertEquals(DayNightPhase.NIGHT, rules.phase(22500));
        assertEquals(DayNightPhase.NIGHT, rules.phase(23500));
    }

    @Test
    @DisplayName("时段标签取自配置，缺省用中文兜底")
    void labels() {
        DayNightRules rules = TestConfigs.defaultConfig().dayNightRules();
        assertEquals("&e日出", rules.label(DayNightPhase.SUNRISE));
        assertEquals("&a白天", rules.label(DayNightPhase.DAY));

        DayNightRules fallbackRules = TestConfigs.config("").dayNightRules();
        assertEquals("白天", fallbackRules.label(DayNightPhase.DAY));
        assertEquals("黑夜", fallbackRules.label(DayNightPhase.NIGHT));
    }

    @Test
    @DisplayName("配置里边界写反时会自动交换，不留空档")
    void invertedRange() {
        DayNightRules rules = TestConfigs.config("""
                day-night:
                  day-start: 11000
                  day-end: 1000
                """).dayNightRules();
        assertEquals(DayNightPhase.DAY, rules.phase(5000));
    }
}
