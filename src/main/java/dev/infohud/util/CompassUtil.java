package dev.infohud.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * 罗盘方向换算与 BossBar 标题组件构造（纯函数，可单测）。
 *
 * <p>原版 yaw 与罗盘方位角（0=北, 90=东, 180=南, 270=西）的对应关系：
 * yaw 0 = 南、90 = 西、180 = 北、270 = 东，因此 {@code bearing = (yaw + 180) % 360}。
 * 进度条即“指针”：北 = 0%、东 = 25%、南 = 50%、西 = 75%。</p>
 *
 * <p>标题里只展示<b>以当前朝向为中心的 180° 刻度窗口</b>（37 格 × 5°）：
 * 正中一格永远钉在当前朝向上，两侧刻度按<b>绝对方位</b>摆放，
 * 因此转身时刻度逐格平移，看上去就是刻度在“滑动”。</p>
 *
 * <h2>刻度画法</h2>
 * <ul>
 *   <li>0°/90°/180°/270° → 方向字 {@code N/E/S/W}（金色）；</li>
 *   <li>其余每 30° → <b>角度数字</b>（灰色），如 {@code 30}、{@code 330}；</li>
 *   <li>其余每 5° → 短刻度 {@code ∙}（深灰）；</li>
 *   <li><b>正中一格</b> → 当前朝向，唯一的青色加粗高亮。</li>
 * </ul>
 *
 * <h2>为什么两半要用空格补齐（以及为什么只能补到 ±1px）</h2>
 * <p>BossBar 标题由客户端按<b>整体像素宽度</b>居中，所以“高亮是否在正中”
 * 取决于左右两半的像素宽度是否相等。实测原版 {@code ascii.png} 的字形步进
 * （= 最右墨迹列 + 2；空格由 {@code font/include/space.json} 定为 4）：</p>
 * <ul>
 *   <li>字母 {@code N/E/S/W}、数字、{@code ∙} (U+2219) → 均为 <b>6 像素</b>；</li>
 *   <li>空格 → <b>4 像素</b>，且没有 1~3 像素的空白字符，无法做精细微调。</li>
 * </ul>
 * <p>180° 窗口里每一半恰好包含 1 个方位字 + 2 个 30° 数字 + 15 个圆点，
 * 所以半宽只可能是 {@code 96 + 2 个数字的宽度}，即
 * <b>120（两个 2 位数）/ 126（一宽一窄）/ 132（两个 3 位数）像素</b>——
 * 全是 6 的倍数，而空格是 4 像素的倍数，两者无法精确对齐，
 * <b>数学上不可能做到严格等宽</b>。</p>
 * <p>因此这里把<b>较窄的一半用空格补到最接近的宽度</b>：</p>
 * <ul>
 *   <li>不补：两半最多差 12px，高亮偏移最多 <b>6px</b>，肉眼可见；</li>
 *   <li>补后：残差最多 2px，高亮偏移最多 <b>1px</b>，已看不出。</li>
 * </ul>
 * <p>补的空格加在<b>最外侧</b>（左半补在行首、右半补在行尾），
 * 不会在刻度之间制造空隙。整圈 72 个朝向里，约一半是天然等宽（无需补），
 * 其余补 1~3 个空格。</p>
 */
public final class CompassUtil {

    /** 标题里四个方向字的顺序：索引 = 象限号（N/E/S/W）。 */
    private static final String[] LETTERS = {"N", "E", "S", "W"};

    /** 刻度窗口半径：以当前朝向为中心，左右各 90°（合计 180°）。 */
    public static final int HALF_SPAN_DEGREES = 90;

    /** 一个刻度格代表的度数。 */
    public static final int DEGREES_PER_SLOT = 5;

    /**
     * 窗口格数：{@code 180 / 5 + 1 = 37}。
     * 取奇数才能让“正中一格”正好落在当前朝向上，左右两侧各 18 格完全对称。
     */
    public static final int SLOT_COUNT = 2 * HALF_SPAN_DEGREES / DEGREES_PER_SLOT + 1;

    /** 短刻度（非 30° 的每 5°）。 */
    static final String MINOR_TICK = "\u2219";

    /** 本类用到的可见字符（方位字、数字、圆点）在原版字体里的步进：都是 6 像素。 */
    static final int GLYPH_PX = 6;

    /** 空格的原版步进（{@code font/include/space.json}）。 */
    static final int SPACE_PX = 4;

    /** 正中一格的下标：{@code 37 / 2 = 18}。 */
    private static final int CENTER_SLOT = SLOT_COUNT / 2;

    private CompassUtil() {
    }

    /**
     * 玩家 yaw → 罗盘方位角（整数度，0=北, 90=东, 180=南, 270=西）。
     * yaw 允许任意实数（含越界值），内部做取整与归一化。
     */
    public static int bearingOf(float yaw) {
        int deg = Math.round(yaw + 180f) % 360;
        return deg < 0 ? deg + 360 : deg;
    }

    /** 方位角所在象限：0=N, 1=E, 2=S, 3=W（每 90° 一个象限，边界 ±45° 归相邻字）。 */
    public static int quadrantOf(int bearing) {
        int b = wrap(bearing);
        return (b + 45) / 90 % 4;
    }

    /** 方位角 → BossBar 进度 0~1（北 = 0.0，东 = 0.25，南 = 0.5，西 = 0.75）。 */
    public static float progressOf(int bearing) {
        return wrap(bearing) / 360f;
    }

    /**
     * 对齐到最近的刻度格（5° 的整数倍）。
     *
     * <p>标题只在这个粒度上变化，因此它同时是标题缓存的键：
     * 整圈只有 72 种取值，比按 1° 缓存（360 种）小 5 倍，
     * 转向时也只在跨格那一刻才需要发包。</p>
     */
    public static int alignedBearing(int bearing) {
        return wrap(Math.round(wrap(bearing) / (float) DEGREES_PER_SLOT) * DEGREES_PER_SLOT);
    }

    /**
     * 罗盘标题组件：以当前朝向为中心的 180° 刻度窗口（37 格，每格 5°）。
     *
     * <p>窗口先对齐到最近的 5°（{@link #alignedBearing}），所以每格都精确落在 5° 刻度上；
     * 转身时整个窗口逐格平移，数字与方向字一起滑动，看起来就像罗盘在转。</p>
     *
     * <p>示例（朝向正北，正中 N 高亮）：
     * {@code W∙∙∙∙∙300∙∙∙∙∙330∙∙∙∙∙N∙∙∙∙∙30∙∙∙∙∙60∙∙∙∙∙E}
     * —— 左右各 90°，两端正好是相邻的正西与正东。</p>
     *
     * <p>较窄的一半会在<b>最外侧</b>补空格（原因见类注释），即空格只会贴在
     * 首格之前或末格之后，不会在刻度之间制造空隙。返回的组件恒为 37 个子组件。</p>
     */
    public static Component buildTitle(int bearing) {
        int center = alignedBearing(bearing);
        String[] glyphs = new String[SLOT_COUNT];
        int[] ticks = new int[SLOT_COUNT];
        for (int i = 0; i < SLOT_COUNT; i++) {
            // 窗口横跨 [center-90, center+90]，逐格 +5°；跨 0/360 由 wrap 处理
            ticks[i] = wrap(center - HALF_SPAN_DEGREES + i * DEGREES_PER_SLOT);
            glyphs[i] = glyphOf(ticks[i]);
        }

        // 把较窄的一半补到最接近的宽度：空格贴在整行最外侧，
        // 因此仍只有 37 个子组件，格与格的对应关系不变
        int leftPx = widthPx(glyphs, 0, CENTER_SLOT);
        int rightPx = widthPx(glyphs, CENTER_SLOT + 1, SLOT_COUNT);
        if (leftPx < rightPx) {
            glyphs[0] = pad(rightPx - leftPx) + glyphs[0];
        } else if (rightPx < leftPx) {
            glyphs[SLOT_COUNT - 1] = glyphs[SLOT_COUNT - 1] + pad(leftPx - rightPx);
        }

        Component out = Component.empty();
        for (int i = 0; i < SLOT_COUNT; i++) {
            out = out.append(slotOf(ticks[i], glyphs[i], i == CENTER_SLOT));
        }
        return out;
    }

    /** 单格渲染：方向字/角度数字/圆点；正中一格统一走高亮色。 */
    private static Component slotOf(int tick, String glyph, boolean centered) {
        if (centered) {
            // 当前朝向：全行唯一的高亮，字形沿用该度数的自然刻度（含方向字与数字）
            return Component.text(glyph, NamedTextColor.AQUA, TextDecoration.BOLD);
        }
        if (tick % 90 == 0) {
            return Component.text(glyph, NamedTextColor.GOLD);                 // N/E/S/W
        }
        return Component.text(glyph,
                tick % 30 == 0 ? NamedTextColor.GRAY : NamedTextColor.DARK_GRAY);
    }

    /**
     * 某度数的自然字形：整 90° → 方向字，其余整 30° → 角度数字（自然位宽，如 30/330），
     * 剩下 → 短刻度。
     */
    private static String glyphOf(int bearing) {
        if (bearing % 90 == 0) {
            return LETTERS[bearing / 90];
        }
        return bearing % 30 == 0 ? Integer.toString(bearing) : MINOR_TICK;
    }

    /** 若干格的像素宽度：本类字形一律 6 像素，故按字符数折算。 */
    private static int widthPx(String[] glyphs, int from, int to) {
        int px = 0;
        for (int i = from; i < to; i++) {
            px += glyphs[i].length() * GLYPH_PX;
        }
        return px;
    }

    /** 用空格尽量补上 {@code px} 像素的差距（空格 4 像素，取最接近的个数）。 */
    private static String pad(int px) {
        int count = Math.max(0, Math.round(px / (float) SPACE_PX));
        return " ".repeat(count);
    }

    /** 把任意角度归一化到 0~359。 */
    private static int wrap(int degrees) {
        int d = degrees % 360;
        return d < 0 ? d + 360 : d;
    }
}
