package dev.infohud.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * 罗盘换算：yaw → 方位角（0=北, 90=东, 180=南, 270=西）、象限、BossBar 进度，
 * 以及“180° 居中刻度窗口”标题。
 *
 * <p>标题相关的像素断言基于实测的原版字形步进：本类用到的字母、数字、圆点
 * {@code ∙} 都是 6 像素，空格是 4 像素。参见 {@link CompassUtil} 类注释。</p>
 */
class CompassUtilTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private static final String MINOR = CompassUtil.MINOR_TICK;

    /** 原版字形步进（像素）：本类用到的可见字符都是 6px。 */
    private static final int GLYPH_PX = 6;

    /** 窗口总格数（37）。 */
    private static final int SLOT_COUNT = CompassUtil.SLOT_COUNT;

    /** 半窗格数：37 格去掉正中一格后，左右各 18 格。 */
    private static final int HALF_SLOTS = SLOT_COUNT / 2;

    private static final int CENTER_SLOT = SLOT_COUNT / 2;

    private static String plain(Component component) {
        return PLAIN.serialize(component);
    }

    @Test
    @DisplayName("yaw 与罗盘方位角的对应关系")
    void yawToBearing() {
        // yaw 0 = 南 → 180；90 = 西 → 270；180 = 北 → 0；270 = 东 → 90
        assertEquals(180, CompassUtil.bearingOf(0f));
        assertEquals(270, CompassUtil.bearingOf(90f));
        assertEquals(0, CompassUtil.bearingOf(180f));
        assertEquals(90, CompassUtil.bearingOf(270f));
    }

    @Test
    @DisplayName("非整度与越界 yaw 也能归一化")
    void bearingWraps() {
        assertEquals(180, CompassUtil.bearingOf(359.5f));  // 539.5 取整 540 → 180
        assertEquals(0, CompassUtil.bearingOf(180.4f));
        assertEquals(90, CompassUtil.bearingOf(630f));     // 810 % 360 = 90
        assertEquals(0, CompassUtil.bearingOf(-180f));     // -180 同 180 = 北 → 0
    }

    @Test
    @DisplayName("象限判定以正负 45 度为界")
    void quadrantBoundaries() {
        assertEquals(0, CompassUtil.quadrantOf(0));     // N
        assertEquals(0, CompassUtil.quadrantOf(44));
        assertEquals(0, CompassUtil.quadrantOf(315));   // 315 之后归 N 侧
        assertEquals(0, CompassUtil.quadrantOf(359));
        assertEquals(1, CompassUtil.quadrantOf(45));    // E
        assertEquals(1, CompassUtil.quadrantOf(90));
        assertEquals(1, CompassUtil.quadrantOf(134));
        assertEquals(2, CompassUtil.quadrantOf(135));   // S
        assertEquals(2, CompassUtil.quadrantOf(180));
        assertEquals(2, CompassUtil.quadrantOf(224));
        assertEquals(3, CompassUtil.quadrantOf(225));   // W
        assertEquals(3, CompassUtil.quadrantOf(270));
        assertEquals(3, CompassUtil.quadrantOf(314));
        // 越界值等价于自身
        assertEquals(CompassUtil.quadrantOf(10), CompassUtil.quadrantOf(-350));
    }

    @Test
    @DisplayName("进度条位置随方位角线性变化")
    void progressLinear() {
        assertEquals(0.0f, CompassUtil.progressOf(0), 1e-6);
        assertEquals(0.25f, CompassUtil.progressOf(90), 1e-6);
        assertEquals(0.5f, CompassUtil.progressOf(180), 1e-6);
        assertEquals(0.75f, CompassUtil.progressOf(270), 1e-6);
        // 越界值归一化后再取进度
        assertEquals(0.75f, CompassUtil.progressOf(-90), 1e-6);
        assertEquals(0.0f, CompassUtil.progressOf(360), 1e-6);
    }

    @Test
    @DisplayName("标题对齐到最近的 5 度刻度")
    void alignedToFiveDegreeSlot() {
        assertEquals(0, CompassUtil.alignedBearing(0));
        assertEquals(5, CompassUtil.alignedBearing(3));
        assertEquals(0, CompassUtil.alignedBearing(2));
        assertEquals(45, CompassUtil.alignedBearing(43));
        assertEquals(45, CompassUtil.alignedBearing(47));
        assertEquals(50, CompassUtil.alignedBearing(48));
        assertEquals(355, CompassUtil.alignedBearing(357));
        assertEquals(0, CompassUtil.alignedBearing(359));    // 359 归到 360 → 0
        assertEquals(90, CompassUtil.alignedBearing(88));
        assertEquals(150, CompassUtil.alignedBearing(870));  // 越界值先归一化：870 % 360 = 150
    }

    @Test
    @DisplayName("窗口固定 37 格，跨度为 180 度且格数为奇数")
    void windowIsHalfCircle() {
        assertEquals(37, CompassUtil.SLOT_COUNT);
        assertEquals(180, CompassUtil.HALF_SPAN_DEGREES * 2);
        // 奇数格才能让“正中一格”左右对称
        assertEquals(1, CompassUtil.SLOT_COUNT % 2, "格数必须是奇数才能居中");

        for (int bearing = 0; bearing < 360; bearing++) {
            assertEquals(CompassUtil.SLOT_COUNT, CompassUtil.buildTitle(bearing).children().size(),
                    "朝向 " + bearing + " 的标题应恒为 37 格");
        }
    }

    @Test
    @DisplayName("左右两半像素宽度差不超过 1px（保证高亮几乎居中）")
    void halvesEqualizedWithinOnePixel() {
        int worst = 0;
        for (int bearing = 0; bearing < 360; bearing++) {
            // 必须用原始格（含补位空格），否则测的是补位前的失衡
            List<String> slots = rawSlotsOf(CompassUtil.buildTitle(bearing));
            int left = 0;
            int right = 0;
            for (int i = 0; i < HALF_SLOTS; i++) {
                left += pixelWidth(slots.get(i));
                right += pixelWidth(slots.get(SLOT_COUNT - 1 - i));
            }
            worst = Math.max(worst, Math.abs(left - right));
        }
        // 半宽只可能是 120/126/132px（全为 6 的倍数），而空格是 4px，
        // 无法精确对齐，补空格后残差最多 2px → 高亮偏移最多 1px
        assertEquals(2, worst, "应恰好出现 2px 残差（否则补位逻辑没生效）");
    }

    @Test
    @DisplayName("补位空格只贴在最外侧，刻度之间不留空隙")
    void paddingOnlyAtOuterEdges() {
        for (int bearing = 0; bearing < 360; bearing++) {
            List<String> slots = rawSlotsOf(CompassUtil.buildTitle(bearing));
            for (int i = 0; i < SLOT_COUNT; i++) {
                String slot = slots.get(i);
                if (i == 0 || i == SLOT_COUNT - 1) {
                    // 首尾格允许含补位空格
                    continue;
                }
                assertTrue(!slot.contains(" "), "中间刻度格不该含空格：第 " + i + " 格 '" + slot + "'");
            }
        }
    }

    @Test
    @DisplayName("角度数字自然位宽：30/60 两位，其余三位")
    void numbersUseNaturalWidth() {
        for (int bearing = 0; bearing < 360; bearing++) {
            for (Component slot : CompassUtil.buildTitle(bearing).children()) {
                String text = plain(slot).trim();
                if (text.isEmpty()) {
                    continue;
                }
                assertTrue(text.length() <= 3, "单格字符数不该超过 3：" + text);
            }
        }
        assertEquals("30", expectedGlyph(30));
        assertEquals("60", expectedGlyph(60));
        assertEquals("330", expectedGlyph(330));
        assertEquals("120", expectedGlyph(120));
        // 正中一格也是自然位宽（无前导零）
        assertEquals("30", plain(CompassUtil.buildTitle(30).children().get(CENTER_SLOT)));
        assertEquals("60", plain(CompassUtil.buildTitle(60).children().get(CENTER_SLOT)));
        // 绝不出现补零形式
        for (int bearing = 0; bearing < 360; bearing++) {
            String text = plain(CompassUtil.buildTitle(bearing));
            assertTrue(!text.contains("030") && !text.contains("060"),
                    "不该出现补零数字：" + text);
        }
    }

    @Test
    @DisplayName("高亮恒在正中一格：唯一加粗青色")
    void highlightAlwaysCentered() {
        for (int bearing = 0; bearing < 360; bearing++) {
            List<Component> slots = CompassUtil.buildTitle(bearing).children();
            for (int i = 0; i < slots.size(); i++) {
                Component slot = slots.get(i);
                boolean highlighted = NamedTextColor.AQUA.equals(slot.color())
                        && slot.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE;
                assertEquals(i == CENTER_SLOT, highlighted,
                        "朝向 " + bearing + " 只有正中一格该高亮，实际第 " + i + " 格："
                                + plain(slot));
            }
        }
    }

    @Test
    @DisplayName("正中一格代表实际朝向")
    void centerSlotMatchesBearing() {
        // 朝向 0 / 90 / 180 / 270：正中分别是 N / E / S / W
        assertEquals("N", plain(CompassUtil.buildTitle(0).children().get(CENTER_SLOT)));
        assertEquals("E", plain(CompassUtil.buildTitle(90).children().get(CENTER_SLOT)));
        assertEquals("S", plain(CompassUtil.buildTitle(180).children().get(CENTER_SLOT)));
        assertEquals("W", plain(CompassUtil.buildTitle(270).children().get(CENTER_SLOT)));
        // 朝向 30：正中就是自然位宽的 30（无前导零）
        assertEquals("30", plain(CompassUtil.buildTitle(30).children().get(CENTER_SLOT)).trim());
        // 朝向 45（非 30/90 倍数）：正中是普通圆点
        assertEquals(MINOR, plain(CompassUtil.buildTitle(45).children().get(CENTER_SLOT)));
        // 同一格内不抖动
        assertEquals(plain(CompassUtil.buildTitle(45)), plain(CompassUtil.buildTitle(43)));
        assertEquals(plain(CompassUtil.buildTitle(45)), plain(CompassUtil.buildTitle(47)));
        // 跨格才变化
        assertNotEquals(plain(CompassUtil.buildTitle(43)), plain(CompassUtil.buildTitle(48)));
    }

    @Test
    @DisplayName("窗口覆盖以朝向为中心的 ±90°：两端是相邻的正西与正东")
    void windowSpansHalfCircle() {
        // 朝向 0 → 窗口 270..90，两端 W 与 E，中间带 300/330/30/60
        List<String> slots = slotsOf(CompassUtil.buildTitle(0));
        assertEquals(CompassUtil.SLOT_COUNT, slots.size());
        assertEquals("W", slots.get(0));       // 270°
        assertEquals("300", slots.get(6));     // 300°
        assertEquals("330", slots.get(12));    // 330°
        assertEquals("N", slots.get(18));      // 0° 正中
        assertEquals("30", slots.get(24).trim());      // 30°
        assertEquals("60", slots.get(30).trim());      // 60°
        assertEquals("E", slots.get(36));      // 90°
    }

    @Test
    @DisplayName("转身时刻度逐格平移（产生旋转感）")
    void ticksShiftWhileTurning() {
        List<String> at0 = slotsOf(CompassUtil.buildTitle(0));
        List<String> at5 = slotsOf(CompassUtil.buildTitle(5));
        assertNotEquals(at0, at5, "转 5° 标题应变化");
        // 正中一格 5° 时不再是 N
        assertEquals(MINOR, at5.get(CENTER_SLOT));
        // 转身等价于窗口整体平移一格
        assertEquals(at0.subList(1, CompassUtil.SLOT_COUNT), at5.subList(0, CompassUtil.SLOT_COUNT - 1),
                "转 5° 应等价于窗口左移一格");
        // 转满一圈回到原点
        assertEquals(at0, slotsOf(CompassUtil.buildTitle(360)));
    }

    @Test
    @DisplayName("窗口跨 360/0 边界正确回绕")
    void windowWrapsAroundNorth() {
        // 朝向 350 → 窗口 260..440，即 260..359 接 0..80
        List<String> slots = slotsOf(CompassUtil.buildTitle(350));
        assertEquals(MINOR, slots.get(0));     // 260° 非 30 倍数
        assertEquals("W", slots.get(2));       // 270°
        assertEquals("300", slots.get(8));     // 300°
        assertEquals("330", slots.get(14));    // 330°
        assertEquals("N", slots.get(20));      // 360° → 0°，越过 0 后仍在窗口内
        assertEquals("30", slots.get(26).trim());      // 30°
        assertEquals("60", slots.get(32).trim());      // 60°
        assertEquals(MINOR, slots.get(36));    // 80° 非 30 倍数
    }

    @Test
    @DisplayName("每格字形与绝对刻度一一对应")
    void glyphsMatchAbsoluteTicks() {
        for (int bearing = 0; bearing < 360; bearing++) {
            List<String> slots = slotsOf(CompassUtil.buildTitle(bearing));
            int center = CompassUtil.alignedBearing(bearing);
            for (int i = 0; i < slots.size(); i++) {
                int absolute = center - CompassUtil.HALF_SPAN_DEGREES + i * CompassUtil.DEGREES_PER_SLOT;
                // 测试内独立复算，避免直接复用被测逻辑
                assertEquals(expectedGlyph(absolute), slots.get(i).trim(),
                        "朝向 " + bearing + " 第 " + i + " 格字形不符");
            }
        }
    }

    @Test
    @DisplayName("负方位角与自身等价")
    void negativeBearingEqualsPositive() {
        assertEquals(plain(CompassUtil.buildTitle(270)), plain(CompassUtil.buildTitle(-90)));
        assertEquals(plain(CompassUtil.buildTitle(10)), plain(CompassUtil.buildTitle(-350)));
    }

    /** 像素宽度：可见字符按 {@link #GLYPH_PX} 计，空格按 4 像素计。 */
    private static int pixelWidth(String text) {
        int px = 0;
        for (char c : text.toCharArray()) {
            px += c == ' ' ? 4 : GLYPH_PX;
        }
        return px;
    }

    /** 测试内独立复算某绝对角度的字形，避免直接复用被测逻辑。 */
    private static String expectedGlyph(int absoluteBearing) {
        int b = ((absoluteBearing % 360) + 360) % 360;
        if (b % 90 == 0) {
            return new String[]{"N", "E", "S", "W"}[b / 90];
        }
        return b % 30 == 0 ? Integer.toString(b) : MINOR;
    }

    /**
     * 取出每格的纯文本并去掉补位空格（即“逻辑刻度”，用于断言刻度值）。
     * 补位空格只可能贴在首尾格上，见 {@link #rawSlotsOf} 与 {@link #paddingOnlyAtOuterEdges}。
     */
    private static List<String> slotsOf(Component title) {
        List<String> out = new ArrayList<>();
        for (String slot : rawSlotsOf(title)) {
            out.add(slot.trim());
        }
        return out;
    }

    /** 取出每格的原始文本（含补位空格），用于像素宽度与补位位置的断言。 */
    private static List<String> rawSlotsOf(Component title) {
        List<String> out = new ArrayList<>();
        for (Component slot : title.children()) {
            out.add(plain(slot));
        }
        return out;
    }
}
