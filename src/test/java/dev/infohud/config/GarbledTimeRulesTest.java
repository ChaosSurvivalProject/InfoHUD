package dev.infohud.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.infohud.TestConfigs;

/**
 * 时间乱码规则：默认只在下界/末地生效，只作用于 gametime 与 daynight。
 */
class GarbledTimeRulesTest {

    @Test
    @DisplayName("默认：下界与末地乱码，主世界不受影响")
    void defaultTargetsNetherAndEnd() {
        GarbledTimeRules rules = TestConfigs.defaultConfig().garbledTimeRules();

        assertTrue(rules.enabled());
        assertTrue(rules.targets(World.Environment.NETHER));
        assertTrue(rules.targets(World.Environment.THE_END));
        assertFalse(rules.targets(World.Environment.NORMAL));

        assertEquals("&k??:??", rules.apply(World.Environment.NETHER, "gametime", "12:00"));
        assertEquals("&k????", rules.apply(World.Environment.THE_END, "daynight", "&a白天"));
        // 主世界保持原值
        assertEquals("12:00", rules.apply(World.Environment.NORMAL, "gametime", "12:00"));
    }

    @Test
    @DisplayName("没配置的占位符不受影响（如 realtime）")
    void otherPlaceholdersUntouched() {
        GarbledTimeRules rules = TestConfigs.defaultConfig().garbledTimeRules();
        assertEquals("21:45:12", rules.apply(World.Environment.NETHER, "realtime", "21:45:12"));
        assertFalse(rules.garbles(World.Environment.NETHER, "realtime"));
        assertTrue(rules.garbles(World.Environment.NETHER, "gametime"));
    }

    @Test
    @DisplayName("enabled: false 时所有维度都按正常时间显示")
    void disabledEverywhere() {
        GarbledTimeRules rules = TestConfigs.config("""
                day-night:
                  garbled:
                    enabled: false
                """).garbledTimeRules();

        assertFalse(rules.enabled());
        assertEquals("12:00", rules.apply(World.Environment.NETHER, "gametime", "12:00"));
        // 名单还在，但 apply/garbles 已经不看它
        assertTrue(rules.targets(World.Environment.NETHER));
        assertFalse(rules.garbles(World.Environment.NETHER, "gametime"));
    }

    @Test
    @DisplayName("维度与占位符都可以配置，别名 overworld/end 也认")
    void customDimensionsAndFormats() {
        GarbledTimeRules rules = TestConfigs.config("""
                day-night:
                  garbled:
                    dimensions:
                      - overworld
                      - end
                    formats:
                      realtime: "&k--:--:--"
                      "%daynight%": "未知"
                """).garbledTimeRules();

        assertTrue(rules.targets(World.Environment.NORMAL));
        assertTrue(rules.targets(World.Environment.THE_END));
        assertFalse(rules.targets(World.Environment.NETHER));

        assertEquals("&k--:--:--", rules.apply(World.Environment.NORMAL, "realtime", "21:45:12"));
        // 键写成 %daynight% 也能匹配（归一化去掉两侧 %）
        assertEquals("未知", rules.apply(World.Environment.NORMAL, "daynight", "&a白天"));
        // 没列在 formats 里的占位符保持原样
        assertEquals("12:00", rules.apply(World.Environment.NORMAL, "gametime", "12:00"));
    }

    @Test
    @DisplayName("dimensions 写空列表表示任何维度都不乱码")
    void emptyDimensionsList() {
        GarbledTimeRules rules = TestConfigs.config("""
                day-night:
                  garbled:
                    dimensions: []
                """).garbledTimeRules();
        // 显式空列表 = 明确的“哪个维度都不乱码”，而不是退回默认
        assertFalse(rules.targets(World.Environment.NETHER));
        assertFalse(rules.targets(World.Environment.THE_END));
        assertFalse(rules.targets(World.Environment.NORMAL));
        assertEquals("12:00", rules.apply(World.Environment.NETHER, "gametime", "12:00"));
    }

    @Test
    @DisplayName("维度写错时退回默认，不会变成一个维度都不生效")
    void invalidDimensionsFallBackToDefault() {
        GarbledTimeRules rules = TestConfigs.config("""
                day-night:
                  garbled:
                    dimensions:
                      - NOT_A_DIMENSION
                      - moon
                """).garbledTimeRules();

        assertTrue(rules.targets(World.Environment.NETHER));
        assertTrue(rules.targets(World.Environment.THE_END));
        assertFalse(rules.targets(World.Environment.NORMAL));
    }

    @Test
    @DisplayName("formats 缺配时用内置默认文本")
    void defaultFormats() {
        GarbledTimeRules rules = TestConfigs.config("""
                day-night:
                  garbled:
                    dimensions:
                      - NETHER
                """).garbledTimeRules();

        assertEquals("&k??:??", rules.apply(World.Environment.NETHER, "gametime", "12:00"));
        assertEquals("&k????", rules.apply(World.Environment.NETHER, "daynight", "&a白天"));
        assertEquals(2, rules.formats().size());
    }
}
