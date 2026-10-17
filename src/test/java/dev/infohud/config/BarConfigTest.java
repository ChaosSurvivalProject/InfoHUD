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
    @DisplayName("缺省配置：血条开、存活 3 秒 / 死亡 1 秒、RED、PROGRESS(实心)、默认黑名单 4 项")
    void defaultsWhenSectionMissing() {
        PluginConfig config = TestConfigs.config("");
        assertTrue(config.healthBarEnabled());
        assertEquals(60, config.healthBarTimeTicks());
        assertEquals(20, config.healthBarDeadTimeTicks());
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
    @DisplayName("time / dead-time 各自被夹在 20~2000 之间，且互相独立")
    void timeClamped() {
        assertEquals(20, TestConfigs.config("bar:\n  time: 1\n").healthBarTimeTicks());
        assertEquals(2000, TestConfigs.config("bar:\n  time: 99999\n").healthBarTimeTicks());
        assertEquals(60, TestConfigs.config("bar:\n  time: 60\n").healthBarTimeTicks());
        // 死亡时长单独配置，不跟着 time 走
        PluginConfig custom = TestConfigs.config("bar:\n  time: 200\n  dead-time: 40\n");
        assertEquals(200, custom.healthBarTimeTicks());
        assertEquals(40, custom.healthBarDeadTimeTicks());
        // 只改 time 时 death 保持默认 1 秒
        assertEquals(20, TestConfigs.config("bar:\n  time: 200\n").healthBarDeadTimeTicks());
    }

    @Test
    @DisplayName("dead-time 也被夹在 20~2000；默认比存活时长短")
    void deadTimeClampedAndShorter() {
        assertEquals(20, TestConfigs.config("bar:\n  dead-time: 5\n").healthBarDeadTimeTicks());
        assertEquals(2000, TestConfigs.config("bar:\n  dead-time: 99999\n").healthBarDeadTimeTicks());
        PluginConfig config = TestConfigs.config("");
        assertTrue(config.healthBarDeadTimeTicks() <= config.healthBarTimeTicks(),
                "默认死亡展示时间不应长于存活展示时间");
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
    @DisplayName("玩家默认开关：未写时跟随总闸，显式设置时以它为准")
    void playerDefaultFollowsMasterSwitch() {
        // 没写 enabled-by-default：跟随 bar.enabled
        assertTrue(TestConfigs.config("bar:\n  enabled: true\n").healthBarEnabledByDefault());
        assertFalse(TestConfigs.config("bar:\n  enabled: false\n").healthBarEnabledByDefault());
        // 显式写：即使总闸开着，也可以默认不给玩家看
        PluginConfig explicit = TestConfigs.config("bar:\n  enabled: true\n  enabled-by-default: false\n");
        assertTrue(explicit.healthBarEnabled());
        assertFalse(explicit.healthBarEnabledByDefault());
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
