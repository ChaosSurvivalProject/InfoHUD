package dev.infohud.text;

/**
 * 群系提示标题下方的横线：一段填充字符 + 正中的符号，按标题宽度算出该画多长。
 *
 * <p>Minecraft 的 title / subtitle 只能用字符画，没有真正的“一条线”。
 * 默认用中文破折号 {@code —}（U+2014）当填充：它在原版 unicode 字体里是<b>撑满字格的横线</b>，
 * 连续重复时会首尾相接，看上去就是一条连续的实线 —— 这也是中文圈画分割线的常用写法。</p>
 *
 * <p>横线形状是 {@code ————✦————}：中间的符号（默认 {@code ✦}，U+2726）
 * <b>左右两侧填充段数严格相等</b>，所以不管标题多宽，星星都精确落在屏幕正中。</p>
 *
 * <h2>为什么长度要乘 2</h2>
 * <p>原版 title 的缩放是 4，subtitle 是 2 —— 同一段文字放在 subtitle 里只占一半的显示宽度。
 * 所以“横线显示宽度 = 标题显示宽度 × {@code scale}”换算成字宽时，
 * 需要多乘一个 {@value #TITLE_TO_SUBTITLE}：
 * {@code 横线字宽 = 2 × scale × 标题字宽}。</p>
 *
 * <h2>字宽估算</h2>
 * <ul>
 *   <li>半角（ASCII 等）→ 6 像素（与 {@code CompassUtil} 的实测一致）；</li>
 *   <li>CJK / 全角 / 中文标点（含 {@code —}、{@code ✦} 等）→ 9 像素；</li>
 *   <li>空格 → 4 像素（{@code font/include/space.json}）。</li>
 * </ul>
 * <p>只是用来估长度，不追求像素级精确；觉得线长了短了，调 {@code divider-scale} 即可。</p>
 */
public final class DividerUtil {

    /** 原版 title 缩放。 */
    static final int TITLE_SCALE = 4;
    /** 原版 subtitle 缩放（正好是 title 的一半）。 */
    static final int SUBTITLE_SCALE = 2;
    /** title 与 subtitle 的缩放比。 */
    static final int TITLE_TO_SUBTITLE = TITLE_SCALE / SUBTITLE_SCALE;

    /** 半角字符宽度。 */
    static final int HALF_WIDTH_PX = 6;
    /** CJK / 全角字符宽度。 */
    static final int WIDE_WIDTH_PX = 9;
    /** 空格宽度。 */
    static final int SPACE_WIDTH_PX = 4;

    /** 单侧填充最多重复多少段（防止配置写了个离谱的倍率把字幕撑爆）。 */
    static final int MAX_REPEAT = 60;

    /**
     * 横线在 subtitle 里的最大宽度（单位：字体像素，即未乘缩放的原始宽度）。
     *
     * <p>subtitle 是居中绘制且<b>不换行</b>的，超出屏幕两边直接被裁掉。
     * 客户端可用的画布约 320 字体像素宽，而 subtitle 的缩放是 2，
     * 所以横线自身宽度不能超过 320 / 2 = 160 字体像素，这里留一点余量。</p>
     */
    static final int MAX_LINE_WIDTH_PX = 152;

    private DividerUtil() {
    }

    /**
     * 生成标题下方的横线（{@code 填充 + 中间符号 + 填充}，左右等长）。
     *
     * @param plainTitle 标题的纯文本（用来量宽度），null / 空串时返回空串
     * @param fill       填充字符，如 {@code "—"}；空串表示不画线（只留中间符号）
     * @param center     中间符号，如 {@code "✦"}；空串表示不画（纯横线）
     * @param scale      横线显示宽度是标题显示宽度的多少倍；{@code <= 0} 表示不画线
     * @return 画好的横线，永不返回 null
     */
    public static String build(String plainTitle, String fill, String center, double scale) {
        if (scale <= 0) {
            return "";
        }
        String head = fill == null ? "" : fill;
        String mid = center == null ? "" : center;
        if (head.isEmpty() && mid.isEmpty()) {
            return "";
        }
        if (plainTitle == null || plainTitle.isBlank()) {
            // 标题是空的（或只有空格）就没什么好“划线”的
            return "";
        }
        int titleWidth = pixelWidth(plainTitle);
        if (titleWidth <= 0) {
            return "";
        }
        // 目标字宽 = 2 × scale × 标题字宽，并夹在屏幕内
        int targetPx = Math.min(MAX_LINE_WIDTH_PX,
                (int) Math.round(TITLE_TO_SUBTITLE * scale * titleWidth));

        int fillPx = pixelWidth(head);
        int midPx = pixelWidth(mid);
        if (fillPx <= 0) {
            // 没给填充字符：只能把中间符号单独显示出来
            return midPx > 0 && midPx <= MAX_LINE_WIDTH_PX ? mid : "";
        }

        // 两侧各多少段：n = (目标宽 - 符号宽) / (2 × 填充宽)，向下取整
        int side = Math.max(0, (targetPx - midPx) / (2 * fillPx));
        // 目标宽还放得下时至少各来一段，避免短标题只剩一个孤零零的符号
        if (side == 0 && midPx < targetPx) {
            side = 1;
        }
        // 双保险：段数与总宽都夹住，保证不会被客户端裁掉
        side = Math.min(side, MAX_REPEAT);
        while (side > 0 && 2 * side * fillPx + midPx > MAX_LINE_WIDTH_PX) {
            side--;
        }
        if (side <= 0) {
            return midPx > 0 && midPx <= MAX_LINE_WIDTH_PX ? mid : "";
        }
        String pad = head.repeat(side);
        return pad + mid + pad;
    }

    /**
     * 估算一段文本在原版字体里的宽度（单位：像素，subtitle 缩放下的字形宽度）。
     *
     * @param text 纯文本，null 视为空
     * @return 像素宽度，空文本为 0
     */
    public static int pixelWidth(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int px = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            px += charWidth(cp);
            i += Character.charCount(cp);
        }
        return px;
    }

    /** 单个码点的宽度。 */
    private static int charWidth(int codePoint) {
        if (codePoint == ' ') {
            return SPACE_WIDTH_PX;
        }
        return isWide(codePoint) ? WIDE_WIDTH_PX : HALF_WIDTH_PX;
    }

    /**
     * 是否为“宽字符”。
     *
     * <p>除了 CJK 与全角区段，这里把<b>中文标点与常用装饰符号</b>也算作宽字符：
     * 破折号 {@code —}/{@code ―}（U+2014/U+2015）在原版 unicode 字体里撑满字格，
     * 星星 {@code ✦}（U+2726）这类 dingbats 也是按宽字形绘制的。</p>
     */
    private static boolean isWide(int cp) {
        return (cp >= 0x1100 && cp <= 0x115F)          // 谚文字母
                || (cp >= 0x2010 && cp <= 0x2015)      // 各种横杠/破折号（含中文 ——）
                || (cp >= 0x2018 && cp <= 0x201F)      // 中文引号等标点
                || (cp >= 0xFE10 && cp <= 0xFE19)      // 竖排标点
                || (cp >= 0x2700 && cp <= 0x27BF)      // dingbats（含 ✦）
                || (cp >= 0x2E80 && cp <= 0xA4CF)      // CJK 部首、汉字、日文假名等
                || (cp >= 0xAC00 && cp <= 0xD7A3)      // 谚文音节
                || (cp >= 0xF900 && cp <= 0xFAFF)      // CJK 兼容汉字
                || (cp >= 0xFE30 && cp <= 0xFE4F)      // CJK 兼容形式
                || (cp >= 0xFF00 && cp <= 0xFF60)      // 全角形式
                || (cp >= 0xFFE0 && cp <= 0xFFE6)
                || (cp >= 0x20000 && cp <= 0x3FFFD);   // 扩展区汉字
    }
}
