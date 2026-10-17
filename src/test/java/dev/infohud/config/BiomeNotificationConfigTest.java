package dev.infohud.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.infohud.TestConfigs;

/**
 * biome-notification 的解析：展示方式（title/chat/off）、默认中央大字文案只显示群系名、
 * 群系记忆秒数与夹取。
 */
class BiomeNotificationConfigTest {

    /** 快捷写法：只给一段 biome-notification 配置。 */
    private static PluginConfig notifyConfig(String yaml) {
        return TestConfigs.config("biome-notification:\n" + yaml);
    }

    @Test
    @DisplayName("缺省：中央大字标题就是群系名，不带“进入”字样；展示方式为 title")
    void defaultCenterTitleHasNoVerb() {
        PluginConfig config = TestConfigs.config("");
        assertEquals("&b%biome%", config.centerTitle());
        assertEquals(PluginConfig.NotificationMode.TITLE, config.notifyMode());
        assertTrue(config.notifyEnabled());
    }

    // ------------------------- 展示方式 -------------------------

    @Test
    @DisplayName("mode: title / chat / off 分别解析成三种展示方式")
    void modeThreeWay() {
        assertEquals(PluginConfig.NotificationMode.TITLE, notifyConfig("  mode: title\n").notifyMode());
        assertEquals(PluginConfig.NotificationMode.CHAT, notifyConfig("  mode: chat\n").notifyMode());
        assertEquals(PluginConfig.NotificationMode.OFF, notifyConfig("  mode: off\n").notifyMode());
        // 大小写与空格不敏感
        assertEquals(PluginConfig.NotificationMode.CHAT, notifyConfig("  mode: \"  CHAT \"\n").notifyMode());
    }

    @Test
    @DisplayName("mode 兼容旧写法：center = title，none/false = off")
    void modeAliases() {
        assertEquals(PluginConfig.NotificationMode.TITLE, notifyConfig("  mode: center\n").notifyMode());
        assertEquals(PluginConfig.NotificationMode.OFF, notifyConfig("  mode: none\n").notifyMode());
        assertEquals(PluginConfig.NotificationMode.OFF, notifyConfig("  mode: false\n").notifyMode());
    }

    @Test
    @DisplayName("mode 写错回退 title，不抛异常")
    void invalidModeFallsBackToTitle() {
        assertEquals(PluginConfig.NotificationMode.TITLE, notifyConfig("  mode: shout\n").notifyMode());
    }

    @Test
    @DisplayName("mode: off 时 notifyEnabled() 也必须是 false（两处判断一致）")
    void offDisablesNotify() {
        PluginConfig off = notifyConfig("  mode: off\n");
        assertEquals(PluginConfig.NotificationMode.OFF, off.notifyMode());
        assertFalse(off.notifyEnabled());

        PluginConfig chat = notifyConfig("  mode: chat\n");
        assertTrue(chat.notifyEnabled());

        PluginConfig title = notifyConfig("  mode: title\n");
        assertTrue(title.notifyEnabled());
    }

    @Test
    @DisplayName("旧配置 enabled: false 等价于 mode: off，且总开关优先于 mode")
    void legacyEnabledFalseWins() {
        PluginConfig legacyOff = notifyConfig("  enabled: false\n");
        assertEquals(PluginConfig.NotificationMode.OFF, legacyOff.notifyMode());
        assertFalse(legacyOff.notifyEnabled());

        // 总开关关着时，mode 写 title/chat 都不生效
        assertFalse(notifyConfig("  enabled: false\n  mode: title\n").notifyEnabled());
        assertEquals(PluginConfig.NotificationMode.OFF,
                notifyConfig("  enabled: false\n  mode: chat\n").notifyMode());
    }

    @Test
    @DisplayName("缺省群系记忆 300 秒；0 = 关闭")
    void defaultMemorySeconds() {
        assertEquals(300, TestConfigs.config("").memorySeconds());
        assertEquals(300_000L, TestConfigs.config("").memoryMillis());
        assertEquals(0, TestConfigs.config("biome-notification:\n  memory-seconds: 0\n").memorySeconds());
    }

    @Test
    @DisplayName("群系记忆负数夹到 0，超过一天夹到 86400")
    void memorySecondsClamped() {
        assertEquals(0, TestConfigs.config("biome-notification:\n  memory-seconds: -10\n").memorySeconds());
        assertEquals(86_400,
                TestConfigs.config("biome-notification:\n  memory-seconds: 999999\n").memorySeconds());
        assertEquals(60, TestConfigs.config("biome-notification:\n  memory-seconds: 60\n").memorySeconds());
    }

    @Test
    @DisplayName("center-title 可自定义，空值回退默认群系名")
    void customCenterTitle() {
        assertEquals("&7欢迎来到 &b%biome%",
                TestConfigs.config("biome-notification:\n  center-title: \"&7欢迎来到 &b%biome%\"\n").centerTitle());
        assertEquals("&b%biome%", TestConfigs.config("biome-notification:\n  center-title: \"\"\n").centerTitle());
    }

    @Test
    @DisplayName("老配置里原封不动的旧默认值 &e进入 &b%biome% 迁移成新默认（只显示群系名）")
    void legacyDefaultTitleMigrated() {
        assertEquals("&b%biome%",
                TestConfigs.config("biome-notification:\n  center-title: \"&e进入 &b%biome%\"\n").centerTitle());
        // 但用户自己写的“说法”要保留
        assertEquals("&a已抵达 &b%biome%",
                TestConfigs.config("biome-notification:\n  center-title: \"&a已抵达 &b%biome%\"\n").centerTitle());
    }

    @Test
    @DisplayName("默认配置项让中央大字停留 3 秒")
    void centerDuration() {
        assertTrue(TestConfigs.defaultConfig().centerDurationSeconds() >= 1);
        assertEquals(3, TestConfigs.defaultConfig().centerDurationSeconds());
    }
}
