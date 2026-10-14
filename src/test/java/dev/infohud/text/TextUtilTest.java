package dev.infohud.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.infohud.TestConfigs;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainComponentSerializer;

/**
 * 文本层：传统 §/& 代码、MiniMessage 标签，以及两者混写。
 */
class TextUtilTest {

    private static String plain(Component component) {
        return PlainComponentSerializer.plain().serialize(component);
    }

    private static String legacy(Component component) {
        return LegacyComponentSerializer.legacySection().serialize(component);
    }

    @Test
    @DisplayName("& 颜色代码解析")
    void ampersandColors() {
        Component component = TextUtil.parse("&7游戏时间 &f14:30", TestConfigs.defaultConfig());
        assertEquals("游戏时间 14:30", plain(component));
        String serialized = legacy(component);
        assertTrue(serialized.contains("§7"), serialized);
        assertTrue(serialized.contains("§f"), serialized);
    }

    @Test
    @DisplayName("§ 颜色代码同样可用")
    void sectionColors() {
        Component component = TextUtil.parse("§a已到达 §b沙漠", TestConfigs.defaultConfig());
        assertEquals("已到达 沙漠", plain(component));
        assertTrue(legacy(component).contains("§a"), legacy(component));
    }

    @Test
    @DisplayName("MiniMessage 标签解析")
    void miniMessageTags() {
        Component component = TextUtil.parse("<red>危险</red>", TestConfigs.defaultConfig());
        assertEquals("危险", plain(component));
        assertTrue(legacy(component).contains("§c"), legacy(component));
    }

    @Test
    @DisplayName("传统代码与 MiniMessage 混写")
    void mixedFormats() {
        Component component = TextUtil.parse("&aGo <bold>fast</bold> &lhome", TestConfigs.defaultConfig());
        assertEquals("Go fast home", plain(component));
        String serialized = legacy(component);
        // &a 让 "Go " 变绿；<bold> 与 &l 都产生加粗
        assertTrue(serialized.contains("§a"), serialized);
        assertTrue(serialized.contains("§l"), serialized);
    }

    @Test
    @DisplayName("颜色代码清掉先前的格式（与原版语义一致）")
    void colorResetsFormatting() {
        // &l 先加粗，紧接着 &a 上颜色 —— 原版里颜色代码会重置格式，所以“绿粗”并不加粗
        Component component = TextUtil.parse("&l&a绿粗&r普通", TestConfigs.defaultConfig());
        String serialized = legacy(component);
        assertEquals("绿粗普通", plain(component));
        assertTrue(serialized.contains("§a"), serialized);
        assertFalse(serialized.contains("§l"), "颜色代码之后不应再有加粗：" + serialized);

        // 反方向：先颜色后格式，格式保留
        String keep = legacy(TextUtil.parse("&a绿&l粗", TestConfigs.defaultConfig()));
        assertTrue(keep.contains("§l"), keep);
        assertTrue(keep.contains("§a"), keep);
    }

    @Test
    @DisplayName("text-mode: legacy 时 MiniMessage 标签按普通字符输出")
    void forceLegacyMode() {
        var config = TestConfigs.config("hud:\n  text-mode: legacy\n");
        Component component = TextUtil.parse("<red>x</red>", config);
        assertEquals("<red>x</red>", plain(component));
    }

    @Test
    @DisplayName("text-mode: minimessage 时传统代码被展开成标签后解析")
    void forceMiniMessageMode() {
        var config = TestConfigs.config("hud:\n  text-mode: minimessage\n");
        Component component = TextUtil.parse("&a绿色", config);
        assertEquals("绿色", plain(component));
        assertTrue(legacy(component).contains("§a"), legacy(component));
    }

    @Test
    @DisplayName("ampersand-colors: false 时 & 只当普通字符")
    void ampersandDisabled() {
        var config = TestConfigs.config("hud:\n  ampersand-colors: false\n");
        Component component = TextUtil.parse("&ax", config);
        assertEquals("&ax", plain(component));
    }

    @Test
    @DisplayName("§r 重置全部样式")
    void resetCode() {
        var config = TestConfigs.config("""
                hud:
                  text-mode: legacy
                """);
        Component component = TextUtil.parse("&c红&r黑", config);
        String serialized = legacy(component);
        assertEquals("红黑", plain(component));
        // “红”是红色，“黑”不再是红色（§r 生效）
        assertTrue(serialized.contains("§c红"), serialized);
        assertTrue(serialized.endsWith("黑"), serialized);
        assertTrue(!serialized.contains("§c黑"), serialized);
    }

    @Test
    @DisplayName("fill 按顺序替换 token，长 token 先替换")
    void fillOrder() {
        String filled = TextUtil.fill("%biome% / %biome_id%",
                "%biome_id%", "desert",
                "%biome%", "沙漠");
        assertEquals("沙漠 / desert", filled);
    }

    @Test
    @DisplayName("空字符串与 null 都返回空组件，不抛异常")
    void emptyInput() {
        var config = TestConfigs.defaultConfig();
        assertEquals("", plain(TextUtil.parse(null, config)));
        assertEquals("", plain(TextUtil.parse("", config)));
    }
}
