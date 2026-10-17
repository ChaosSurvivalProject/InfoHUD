package dev.infohud.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.infohud.TestConfigs;

/**
 * bar / blacklist 配置解析：默认值、Adventure 与 Bukkit 双套样式名、夹取、黑名单归一化。
 */
class BarConfigTest {

    @Test
    @DisplayName("缺省配置：血条开、100 tick、RED、PROGRESS(实心)、默认黑名单 4 项")
    void defaultsWhenSectionMissing() {
        PluginConfig config = TestConfigs.config("");
        assertTrue(config.healthBarEnabled());
        assertEquals(100, config.healthBarTimeTicks());
        assertEquals(BarColor.RED, config.healthBarColor());
        assertEquals(BarStyle.SOLID, config.healthBarOverlay());
        assertEquals(Set.of("ENDER_DRAGON", "WITHER", "PLAYER", "ARMOR_STAND"),
                config.healthBarBlacklist());
        assertTrue(config.healthBarLivingTitle().contains("<health>"), "默认存活标题应含占位符");
        assertTrue(config.healthBarDeadTitle().contains("☠"), "默认死亡标题应含 ☠");
    }

    @Test
    @DisplayName("overlay 同时接受 Adventure 名（PROGRESS/NOTCHED_x）与 Bukkit 名（SOLID/SEGMENTED_x）")
    void overlayNameAliases() {
        assertEquals(BarStyle.SOLID, TestConfigs.config("bar:\n  overlay: PROGRESS\n").healthBarOverlay());
        assertEquals(BarStyle.SEGMENTED_6, TestConfigs.config("bar:\n  overlay: NOTCHED_6\n").healthBarOverlay());
        assertEquals(BarStyle.SEGMENTED_10, TestConfigs.config("bar:\n  overlay: NOTCHED_10\n").healthBarOverlay());
        assertEquals(BarStyle.SEGMENTED_12, TestConfigs.config("bar:\n  overlay: NOTCHED_12\n").healthBarOverlay());
        assertEquals(BarStyle.SEGMENTED_20, TestConfigs.config("bar:\n  overlay: notched_20\n").healthBarOverlay());
        assertEquals(BarStyle.SOLID, TestConfigs.config("bar:\n  overlay: SOLID\n").healthBarOverlay());
        assertEquals(BarStyle.SEGMENTED_10,
                TestConfigs.config("bar:\n  overlay: SEGMENTED_10\n").healthBarOverlay());
    }

    @Test
    @DisplayName("非法 overlay / color 回退默认（SOLID / RED）")
    void invalidOverlayAndColor() {
        PluginConfig config = TestConfigs.config("bar:\n  overlay: FANCY\n  color: GOLD\n");
        assertEquals(BarStyle.SOLID, config.healthBarOverlay());
        assertEquals(BarColor.RED, config.healthBarColor());
    }

    @Test
    @DisplayName("color 合法值直接生效")
    void validColor() {
        assertEquals(BarColor.GREEN, TestConfigs.config("bar:\n  color: GREEN\n").healthBarColor());
    }

    @Test
    @DisplayName("time 被夹在 20~2000 之间")
    void timeClamped() {
        assertEquals(20, TestConfigs.config("bar:\n  time: 1\n").healthBarTimeTicks());
        assertEquals(2000, TestConfigs.config("bar:\n  time: 99999\n").healthBarTimeTicks());
        assertEquals(60, TestConfigs.config("bar:\n  time: 60\n").healthBarTimeTicks());
    }

    @Test
    @DisplayName("开关关闭与自定义标题生效")
    void enabledAndCustomTitles() {
        PluginConfig config = TestConfigs.config("""
                bar:
                  enabled: false
                  title:
                    living: "hit"
                    dead: "dead"
                """);
        assertFalse(config.healthBarEnabled());
        assertEquals("hit", config.healthBarLivingTitle());
        assertEquals("dead", config.healthBarDeadTitle());
    }

    @Test
    @DisplayName("黑名单归一化：大小写不敏感 + 容忍 minecraft: 前缀")
    void blacklistNormalized() {
        PluginConfig config = TestConfigs.config("""
                blacklist:
                  - zombie
                  - minecraft:skeleton
                  -  WITHER
                """);
        Set<String> list = config.healthBarBlacklist();
        assertEquals(3, list.size());
        assertTrue(list.contains("ZOMBIE"));
        assertTrue(list.contains("SKELETON"));
        assertTrue(list.contains("WITHER"));
    }

    @Test
    @DisplayName("空 / 缺失的黑名单回退默认 4 项")
    void emptyBlacklistFallsBackToDefault() {
        // getStringList 区分不了 “写了空表” 和 “没写”，两种情况都回退默认黑名单
        List<String> defaults = List.of("ENDER_DRAGON", "WITHER", "PLAYER", "ARMOR_STAND");
        assertTrue(TestConfigs.config("blacklist: []\n").healthBarBlacklist().containsAll(defaults));
        assertTrue(TestConfigs.config("").healthBarBlacklist().containsAll(defaults));
    }
}
