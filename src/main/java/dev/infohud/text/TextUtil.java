package dev.infohud.text;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.infohud.config.PluginConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * 文本解析工具：把配置里的字符串转成 Adventure {@link Component}。
 *
 * <p>同时支持两种写法，并且可以在同一行里混用：</p>
 * <ul>
 *   <li>传统颜色代码：{@code &a} / {@code §a}（0-9、a-f、k-o、r）以及 {@code &#RRGGBB} 十六进制；</li>
 *   <li>MiniMessage 标签：{@code <red>}、{@code <bold>}、{@code <gradient>}、{@code <click:...>} 等。</li>
 * </ul>
 *
 * <p>实现要点：Adventure 的 {@link LegacyComponentSerializer} 只认一种标记字符，
 * MiniMessage 又完全不认 {@code §}/{@code &}。因此：</p>
 * <ol>
 *   <li>文本中出现 MiniMessage 标签 —— 先把传统代码<b>原地展开</b>成 MiniMessage 标签
 *       （按原版语义维护颜色/格式状态，并保证开闭配对），已有的 MiniMessage 标签原样保留，
 *       再整体交给 MiniMessage 解析；</li>
 *   <li>没有 MiniMessage 标签 —— 把 {@code §} 归一成 {@code &}（或反向）后交给传统解析器。</li>
 * </ol>
 */
public final class TextUtil {

    /** 原版（ Bukkit 聊天里常见的）颜色代码标记 {@code §}。 */
    private static final char SECTION = '\u00A7';

    /** MiniMessage 标签形态：{@code <tag>}、{@code </tag>}、{@code <tag:arg:arg>}。 */
    private static final Pattern MINI_TAG =
            Pattern.compile("</?[a-z][a-z0-9_]*(?::[^<>]*)?>", Pattern.CASE_INSENSITIVE);

    /** 非严格模式：无法识别的标签按普通文本输出，不会因为配置写错而打断 HUD。 */
    private static final MiniMessage MINI = MiniMessage.builder().strict(false).build();

    /** {@code &} 代码解析器（开启 {@code &#RRGGBB} 十六进制）。 */
    private static final LegacyComponentSerializer LEGACY_AMP = LegacyComponentSerializer.builder()
            .character('&')
            .hexCharacter('#')
            .hexColors()
            .build();

    /** {@code §} 代码解析器。 */
    private static final LegacyComponentSerializer LEGACY_SECTION = LegacyComponentSerializer.builder()
            .character(SECTION)
            .hexCharacter('#')
            .hexColors()
            .build();

    private TextUtil() {
    }

    /**
     * 按配置选择的模式解析文本。
     *
     * @param raw    原始字符串，可为 null
     * @param config 配置快照
     * @return 组件，永不返回 null（空串 → {@link Component#empty()}）
     */
    public static Component parse(String raw, PluginConfig config) {
        if (raw == null || raw.isEmpty()) {
            return Component.empty();
        }
        switch (config.textMode()) {
            case MINIMESSAGE:
                return MINI.deserialize(expandLegacy(raw, config.ampersandColors()), TagResolver.standard());
            case LEGACY:
                // 显式关掉 MiniMessage：文本里的 <xxx> 一律当普通字符
                return config.ampersandColors()
                        ? LEGACY_AMP.deserialize(raw.replace(SECTION, '&'))
                        : LEGACY_SECTION.deserialize(raw);
            case AUTO:
            default:
                if (MINI_TAG.matcher(raw).find()) {
                    return MINI.deserialize(expandLegacy(raw, config.ampersandColors()), TagResolver.standard());
                }
                return config.ampersandColors()
                        ? LEGACY_AMP.deserialize(raw.replace(SECTION, '&'))
                        : LEGACY_SECTION.deserialize(raw);
        }
    }

    /**
     * 把传统颜色代码展开为 MiniMessage 标签，使两种写法可以混在一行里。
     *
     * <p>按原版语义：</p>
     * <ul>
     *   <li>写颜色代码会同时清掉加粗/斜体等格式；</li>
     *   <li>{@code &r} / {@code §r} 重置全部样式；</li>
     *   <li>文本里本来就写对的 MiniMessage 标签（{@code <gradient:...>} 等）原样透传，
     *       其余位置的 {@code <} 转义为 {@code \<}，避免被误解析。</li>
     * </ul>
     *
     * <p>包外使用方：血条标题（{@code dev.infohud.bar}）先把 {@code &} 代码展开，
     * 再整条交给带自定义占位符标签的 MiniMessage 解析。</p>
     */
    public static String expandLegacy(String raw, boolean allowAmpersand) {
        StringBuilder out = new StringBuilder(raw.length() + 32);
        StringBuilder segment = new StringBuilder(raw.length());
        List<String> formats = new ArrayList<>(4);
        String color = null;

        Matcher tagMatcher = MINI_TAG.matcher(raw);

        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);

            // 情况一：命中一个真正的 MiniMessage 标签 —— 原样搬过去
            if (c == '<' && tagMatcher.region(i, raw.length()).lookingAt()) {
                flushSegment(out, segment, color, formats);
                out.append(raw, i, tagMatcher.end());
                i = tagMatcher.end() - 1;
                continue;
            }

            // 情况二：命中一个传统颜色代码
            boolean markerAllowed = c == SECTION || (c == '&' && allowAmpersand);
            String tag = null;
            if (markerAllowed && i + 1 < raw.length()) {
                tag = toMiniTag(Character.toLowerCase(raw.charAt(i + 1)));
            }
            if (tag == null) {
                // 情况三：普通字符
                if (c == '<') {
                    segment.append("\\<");
                } else {
                    segment.append(c);
                }
                continue;
            }
            i++; // 吃掉代码字符
            flushSegment(out, segment, color, formats);
            if (tag.isEmpty()) {                 // &r / §r
                color = null;
                formats.clear();
            } else if (isColorTag(tag)) {        // 颜色代码：换色并清格式（原版行为）
                color = tag;
                formats.clear();
            } else if (!formats.remove(tag)) {   // 同一格式代码出现两次 = 关掉它
                formats.add(tag);
            }
        }
        flushSegment(out, segment, color, formats);
        return out.toString();
    }

    /** 输出一个片段：开标签 + 文本 + 闭标签，然后清空文本缓冲。 */
    private static void flushSegment(StringBuilder out, StringBuilder segment, String color, List<String> formats) {
        if (segment.length() == 0) {
            return;
        }
        if (color == null && formats.isEmpty()) {
            out.append(segment);
        } else {
            if (color != null) {
                out.append('<').append(color).append('>');
            }
            for (String format : formats) {
                out.append('<').append(format).append('>');
            }
            out.append(segment);
            for (int i = formats.size() - 1; i >= 0; i--) {
                out.append("</").append(formats.get(i)).append('>');
            }
            if (color != null) {
                out.append("</").append(color).append('>');
            }
        }
        segment.setLength(0);
    }

    private static boolean isColorTag(String tag) {
        switch (tag) {
            case "black":
            case "dark_blue":
            case "dark_green":
            case "dark_aqua":
            case "dark_red":
            case "dark_purple":
            case "gold":
            case "gray":
            case "dark_gray":
            case "blue":
            case "green":
            case "aqua":
            case "red":
            case "light_purple":
            case "yellow":
            case "white":
                return true;
            default:
                return false;
        }
    }

    /**
     * 传统代码字符 → MiniMessage 标签名。
     *
     * @return {@code null} 表示这不是颜色/格式代码；空串表示 {@code r}（重置）
     */
    private static String toMiniTag(char code) {
        switch (code) {
            case '0':
                return "black";
            case '1':
                return "dark_blue";
            case '2':
                return "dark_green";
            case '3':
                return "dark_aqua";
            case '4':
                return "dark_red";
            case '5':
                return "dark_purple";
            case '6':
                return "gold";
            case '7':
                return "gray";
            case '8':
                return "dark_gray";
            case '9':
                return "blue";
            case 'a':
                return "green";
            case 'b':
                return "aqua";
            case 'c':
                return "red";
            case 'd':
                return "light_purple";
            case 'e':
                return "yellow";
            case 'f':
                return "white";
            case 'k':
                return "obfuscated";
            case 'l':
                return "bold";
            case 'm':
                return "strikethrough";
            case 'n':
                return "underlined";
            case 'o':
                return "italic";
            case 'r':
                return "";
            default:
                return null;
        }
    }

    /**
     * 占位符替换：成对传入 {@code {"%token%", "值", "%token2%", "值2", ...}}。
     * 先 {@code indexOf} 判断再 {@code replace}，模板里没有的 token 不产生额外字符串拷贝。
     */
    public static String fill(String template, String... pairs) {
        String result = template;
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (result.indexOf(pairs[i]) >= 0) {
                result = result.replace(pairs[i], pairs[i + 1]);
            }
        }
        return result;
    }
}
