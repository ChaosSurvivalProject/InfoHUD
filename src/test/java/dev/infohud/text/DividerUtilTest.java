package dev.infohud.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 标题下方横线：字宽估算，以及「填充 + 符号 + 填充」的等长与上限。
 */
class DividerUtilTest {

    private static final String DASH = "\u2014";
    private static final String STAR = "\u2726";

    @Test
    @DisplayName("字宽：半角 6px、全角 9px、空格 4px；破折号与星号按宽字符 9px")
    void pixelWidth() {
        assertEquals(6, DividerUtil.pixelWidth("A"));
        assertEquals(12, DividerUtil.pixelWidth("AB"));
        assertEquals(9, DividerUtil.pixelWidth("恶"));
        assertEquals(18, DividerUtil.pixelWidth("恶地"));
        assertEquals(4, DividerUtil.pixelWidth(" "));
        assertEquals(9, DividerUtil.pixelWidth(DASH));
        assertEquals(9, DividerUtil.pixelWidth(STAR));
        assertEquals(0, DividerUtil.pixelWidth(""));
        assertEquals(0, DividerUtil.pixelWidth(null));
    }

    @Test
    @DisplayName("形状是 填充+✦+填充，左右两侧段数严格相等")
    void symbolIsCentered() {
        String line = DividerUtil.build("恶地", DASH, STAR, 2.5D);
        int star = line.indexOf(STAR);
        assertEquals(1, line.chars().filter(c -> c == STAR.charAt(0)).count(), line);
        String left = line.substring(0, star);
        String right = line.substring(star + 1);
        assertEquals(left, right, "左右填充必须等长，否则符号不在正中：" + line);
        assertTrue(left.chars().allMatch(c -> c == DASH.charAt(0)), line);
        assertFalse(left.isEmpty());
    }

    @Test
    @DisplayName("横线长度随标题变长而变长")
    void lineScalesWithTitle() {
        String shortLine = DividerUtil.build("恶地", DASH, STAR, 2.5D);
        String longLine = DividerUtil.build("原始云杉针叶林", DASH, STAR, 2.5D);
        assertTrue(longLine.length() > shortLine.length(), shortLine + " / " + longLine);
    }

    @Test
    @DisplayName("填充段数按「2×scale×标题宽 − 符号宽」折算，向下取整")
    void fillCountFollowsScale() {
        // "恶地" = 18px → 目标 2 × 2.5 × 18 = 90px；符号 9px，填充 9px/段
        // (90 − 9) / (2 × 9) = 4.5 → 每侧 4 段
        assertEquals(DASH.repeat(4) + STAR + DASH.repeat(4),
                DividerUtil.build("恶地", DASH, STAR, 2.5D));
    }

    @Test
    @DisplayName("scale = 0 不画线；符号为空时退化成纯横线")
    void scaleAndSymbolVariants() {
        assertEquals("", DividerUtil.build("恶地", DASH, STAR, 0));
        assertEquals("", DividerUtil.build("恶地", DASH, STAR, -1));

        String pure = DividerUtil.build("恶地", DASH, "", 2.5D);
        assertTrue(pure.chars().allMatch(c -> c == DASH.charAt(0)), pure);
        assertEquals(10, pure.length());
    }

    @Test
    @DisplayName("填充为空时只显示符号；两者都空则不画线")
    void emptyFill() {
        assertEquals(STAR, DividerUtil.build("恶地", "", STAR, 2.5D));
        assertEquals("", DividerUtil.build("恶地", "", "", 2.5D));
        assertEquals("", DividerUtil.build("恶地", null, null, 2.5D));
    }

    @Test
    @DisplayName("空标题 / 只有空格返回空串，不抛异常")
    void emptyTitle() {
        assertEquals("", DividerUtil.build("", DASH, STAR, 2.5D));
        assertEquals("", DividerUtil.build(null, DASH, STAR, 2.5D));
        assertEquals("", DividerUtil.build("   ", DASH, STAR, 2.5D));
    }

    @Test
    @DisplayName("多字符填充整段重复")
    void multiCharFill() {
        String line = DividerUtil.build("恶地", "- ", STAR, 2.0D);
        assertTrue(line.contains(STAR), line);
        assertTrue(line.startsWith("- "), line);
    }

    @Test
    @DisplayName("超长标题 / 离谱的 scale 会被像素宽度上限夹住，横线不会超出屏幕")
    void widthIsCapped() {
        String longName = "原始云杉针叶林".repeat(20);
        String line = DividerUtil.build(longName, DASH, STAR, 12.0D);
        assertTrue(DividerUtil.pixelWidth(line) <= DividerUtil.MAX_LINE_WIDTH_PX,
                line.length() + " 个字，宽 " + DividerUtil.pixelWidth(line));

        // 左右仍然等长，符号没有被夹歪
        int star = line.indexOf(STAR);
        assertEquals(line.substring(0, star), line.substring(star + 1), line);
    }

    @Test
    @DisplayName("很短的标题在放得下时也至少各有一段填充，不会只剩一个孤零零的符号")
    void tinyTitleStillDrawsLine() {
        // 单字 9px，scale 1 → 目标 18px；符号 9px 占掉一半，按公式本该 0 段，兜底给 1 段
        assertEquals(DASH + STAR + DASH, DividerUtil.build("沙", DASH, STAR, 1.0D));
    }
}
