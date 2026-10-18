package dev.infohud.bar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.infohud.TestConfigs;
import dev.infohud.config.PluginConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainComponentSerializer;

/**
 * 血条标题构建与血量格式化（纯逻辑，不需要真实服务器）。
 */
class HealthBarServiceTest {

    private static String plain(Component component) {
        return PlainComponentSerializer.plain().serialize(component);
    }

    private static String legacy(Component component) {
        return LegacyComponentSerializer.legacySection().serialize(component);
    }

    @Test
    @DisplayName("占位符按模板顺序替换")
    void titleTokensInOrder() {
        Component component = HealthBarService.titleComponent(
                "<entity> <name> <health>/<max> -<damage>♥",
                TestConfigs.config(""),
                Component.translatable("entity.zombie.name"),
                Component.text("Boss Z"),
                "10", "20", "3.5");
        assertEquals("entity.zombie.name Boss Z 10/20 -3.5♥", plain(component));
    }

    @Test
    @DisplayName("<name> 没有自定义名时回退 <entity>")
    void nameFallsBackToEntity() {
        Component component = HealthBarService.titleComponent(
                "<name>",
                TestConfigs.config(""),
                Component.translatable("entity.zombie.name"),
                null,
                "10", "20", "5");
        assertEquals("entity.zombie.name", plain(component));
    }

    @Test
    @DisplayName("占位符被切出文本段：不会进 MiniMessage 解析（不会被当成未知标签丢掉）")
    void tokensNotParsedAsMiniMessage() {
        // 默认 text-mode=auto，模板含 <gray> 等 MiniMessage 标签：
        // 存活标题原文（来自默认 config.yml）能完整还原占位符值
        String template = "<gray><health>/<max> <reset><name> -<damage><red>♥";
        Component component = HealthBarService.titleComponent(
                template,
                TestConfigs.config(""),
                Component.translatable("entity.creeper.name"),
                null,
                "15", "20", "6");
        String text = plain(component);
        assertTrue(text.contains("15/20"), text);
        assertTrue(text.contains("entity.creeper.name"), text);
        assertTrue(text.contains("-6"), text);
        // 颜色保留：legacy 串里应出现灰色（§7）与红色（§c）
        String serialized = legacy(component);
        assertTrue(serialized.contains("§7"), serialized);
        assertTrue(serialized.contains("§c"), serialized);
        // 占位符本体不允许残留
        assertTrue(!text.contains("<health>"), text);
        assertTrue(!text.contains("<damage>"), text);
    }

    @Test
    @DisplayName("空模板返回空组件，不抛异常")
    void emptyTemplate() {
        assertEquals(Component.empty(),
                HealthBarService.titleComponent("", TestConfigs.config(""), Component.empty(), null, "1", "1", "1"));
        assertEquals(Component.empty(),
                HealthBarService.titleComponent(null, TestConfigs.config(""), Component.empty(), null, "1", "1", "1"));
    }

    @Test
    @DisplayName("§ 颜色代码始终展开：关掉 ampersand-colors 也不能把裸 § 漏给客户端")
    void sectionCodesAlwaysExpanded() {
        // 旧实现把标题序列化成 § 串再由服务端重新解析，裸 § 也能生效；
        // 现在组件是直接下发的，必须在解析阶段就把 § 展开成 MiniMessage 标签。
        PluginConfig noAmpersand = TestConfigs.config("hud:\n  ampersand-colors: false\n");
        Component colored = HealthBarService.titleComponent(
                "§7<health>/<max>", noAmpersand, Component.text("X"), null, "1", "2", "3");
        String serialized = legacy(colored);
        assertTrue(serialized.contains("§7"), "§7 应转成灰色，实际：" + serialized);
        assertFalse(serialized.contains("§7<health>") || serialized.contains("§§"),
                "不该残留裸代码：" + serialized);
        // & 代码在关闭时应保持字面量（不被当颜色代码）
        Component literal = HealthBarService.titleComponent(
                "&7<health>", noAmpersand, Component.text("X"), null, "1", "2", "3");
        assertTrue(plain(literal).contains("&7"), plain(literal));
    }

    @Test
    @DisplayName("血量格式化：整数不带小数，小数保留 1 位，0/负数显示 0")
    void healthFormatting() {
        assertEquals("20", HealthBarService.formatHealth(20.0));
        assertEquals("19", HealthBarService.formatHealth(19.001));
        assertEquals("19.5", HealthBarService.formatHealth(19.5));
        assertEquals("0.4", HealthBarService.formatHealth(0.4));
        assertEquals("0", HealthBarService.formatHealth(0.0));
        assertEquals("0", HealthBarService.formatHealth(-3.2));
    }

    @Test
    @DisplayName("<entity> 占位符保留 translatable 组件：序列化后不能只剩裸翻译键")
    void entityPlaceholderStaysTranslatable() {
        // 回归：曾经为了塞进 Bukkit BossBar#setTitle(String) 而把整条组件用
        // LegacyComponentSerializer 压成 § 代码串，translatable 会被拍平成裸 key，
        // 客户端就原样显示 entity.minecraft.enderman。改用 Adventure BossBar
        // （标题是 Component）后，这里必须仍然是一个 TranslatableComponent。
        Component component = HealthBarService.titleComponent(
                "<gray><health>/<max> <reset><name> -<damage><red>♥",
                TestConfigs.config(""),
                Component.translatable("entity.minecraft.enderman"),
                null,
                "26", "40", "7");
        assertTrue(containsTranslatable(component),
                "标题里必须保留 translatable 组件，实际：" + component);
    }

    @Test
    @DisplayName("Bukkit 颜色/样式正确映射到 Adventure 枚举")
    void adventureEnumMapping() {
        assertEquals(net.kyori.adventure.bossbar.BossBar.Color.RED,
                HealthBarService.toAdventure(BarColor.RED));
        assertEquals(net.kyori.adventure.bossbar.BossBar.Color.WHITE,
                HealthBarService.toAdventure(BarColor.WHITE));
        assertEquals(net.kyori.adventure.bossbar.BossBar.Overlay.PROGRESS,
                HealthBarService.toAdventure(BarStyle.SOLID));
        assertEquals(net.kyori.adventure.bossbar.BossBar.Overlay.NOTCHED_10,
                HealthBarService.toAdventure(BarStyle.SEGMENTED_10));
        assertEquals(net.kyori.adventure.bossbar.BossBar.Overlay.NOTCHED_20,
                HealthBarService.toAdventure(BarStyle.SEGMENTED_20));
        // 每个 Bukkit 样式都要有对应项，避免以后新增值漏映射
        for (BarStyle style : BarStyle.values()) {
            assertNotNull(HealthBarService.toAdventure(style), style.name());
        }
        for (BarColor color : BarColor.values()) {
            assertNotNull(HealthBarService.toAdventure(color), color.name());
        }
    }

    @Test
    @DisplayName("血条归属键按「玩家 + 生物」区分：同一只怪不同玩家 = 两条独立血条")
    void barKeySeparatesPlayers() {
        UUID playerA = UUID.randomUUID();
        UUID playerB = UUID.randomUUID();
        UUID zombie = UUID.randomUUID();

        HealthBarService.BarKey aHitsZombie = new HealthBarService.BarKey(playerA, zombie);
        HealthBarService.BarKey bHitsZombie = new HealthBarService.BarKey(playerB, zombie);
        HealthBarService.BarKey aHitsZombieAgain = new HealthBarService.BarKey(playerA, zombie);

        // 回归：以前键里只有生物 UUID，A 和 B 共用一个键，于是 B 也能看到 A 的血条。
        // 现在键含攻击者，两个玩家必须拿到不同的键。
        assertNotEquals(aHitsZombie, bHitsZombie,
                "不同玩家打同一只生物必须是两条独立血条");
        // 同一个人再次攻击同一只生物 = 同一个键（刷新原血条，不会越积越多）
        assertEquals(aHitsZombie, aHitsZombieAgain,
                "同一玩家重复攻击同一生物应复用同一条血条");
        // equals/hashCode 一致：能正确当 HashMap 键用
        assertEquals(aHitsZombie.hashCode(), aHitsZombieAgain.hashCode());
    }

    @Test
    @DisplayName("血条归属键也按生物区分：同一玩家打两只不同的怪 = 两条血条")
    void barKeySeparatesEntities() {
        UUID player = UUID.randomUUID();
        HealthBarService.BarKey zombie = new HealthBarService.BarKey(player, UUID.randomUUID());
        HealthBarService.BarKey skeleton = new HealthBarService.BarKey(player, UUID.randomUUID());
        assertNotEquals(zombie, skeleton);
    }

    /** 组件树里是否含 translatable（含子组件）。 */
    private static boolean containsTranslatable(Component component) {
        if (component instanceof net.kyori.adventure.text.TranslatableComponent) {
            return true;
        }
        for (Component child : component.children()) {
            if (containsTranslatable(child)) {
                return true;
            }
        }
        return false;
    }
}
