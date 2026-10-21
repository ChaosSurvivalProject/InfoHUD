package dev.infohud.config;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

/**
 * 时间显示乱码规则（{@code day-night.garbled}）。
 *
 * <p>下界与末地没有昼夜循环：{@code World#getTime()} 一直在 6000 附近不动，
 * 对玩家来说“游戏时间 / 时段标签”既不准也没意义。这个规则让这些维度里的
 * 时间类占位符直接显示成乱码（默认 {@code ??:??}，套用原版 {@code &k} 随机字符格式，
 * 看起来就是一块跳动的乱码块）。</p>
 *
 * <p>两个开关维度：</p>
 * <ul>
 *   <li>{@code dimensions} —— 哪些维度生效（Bukkit 的 {@link World.Environment} 名）；</li>
 *   <li>{@code formats} —— 哪些占位符乱码、各自显示成什么文本（键即占位符名，
 *       没列出来的占位符保持原样）。</li>
 * </ul>
 *
 * <p>默认：主世界以外的下界 / 末地生效，{@code gametime} 与 {@code daynight} 都乱码。
 * {@code enabled: false} 或把 {@code dimensions} 清空即可整体关闭。</p>
 */
public final class GarbledTimeRules {

    /** 占位符名 → 乱码后取代的文本（键统一小写，值可能带颜色代码）。 */
    private final Map<String, String> formats;
    private final Set<World.Environment> environments;
    private final boolean enabled;

    private GarbledTimeRules(boolean enabled, Set<World.Environment> environments, Map<String, String> formats) {
        this.enabled = enabled;
        this.environments = environments;
        this.formats = formats;
    }

    /**
     * 从 {@code day-night} 配置节读取 {@code garbled} 子节；缺配一律用内置默认值。
     *
     * @param dayNightSection {@code day-night} 节点，可为 null
     */
    public static GarbledTimeRules fromSection(ConfigurationSection dayNightSection) {
        ConfigurationSection section = dayNightSection == null
                ? null : dayNightSection.getConfigurationSection("garbled");
        boolean enabled = section == null || section.getBoolean("enabled", true);

        Set<World.Environment> environments = readEnvironments(section);
        Map<String, String> formats = readFormats(section);
        return new GarbledTimeRules(enabled, environments, formats);
    }

    /** 开关本身是否打开（{@code day-night.garbled.enabled}）。 */
    public boolean enabled() {
        return enabled;
    }

    /** 该维度是否在乱码名单里（不判断开关，只看名单）。 */
    public boolean targets(World.Environment environment) {
        return environment != null && environments.contains(environment);
    }

    /**
     * 该占位符在该维度下是否要显示乱码。
     *
     * @param environment 玩家所在维度的 {@link World.Environment}
     * @param placeholder 占位符名（不带 {@code %}，如 {@code gametime}）
     */
    public boolean garbles(World.Environment environment, String placeholder) {
        return enabled && targets(environment) && formats.containsKey(normalize(placeholder));
    }

    /**
     * 按需把占位符的值替换成乱码文本。
     *
     * @param environment 玩家所在维度
     * @param placeholder 占位符名（如 {@code gametime}）
     * @param original    正常算出来的值
     * @return 命中规则时返回配置的乱码文本，否则原样返回 {@code original}
     */
    public String apply(World.Environment environment, String placeholder, String original) {
        if (!enabled || !targets(environment)) {
            return original;
        }
        String garbled = formats.get(normalize(placeholder));
        return garbled == null ? original : garbled;
    }

    /** 配置了乱码的占位符名（小写，只读）。 */
    public Map<String, String> formats() {
        return formats;
    }

    /** 生效维度（只读）。 */
    public Set<World.Environment> environments() {
        return environments;
    }

    // ------------------------- 解析 -------------------------

    /**
     * 读 {@code dimensions}：Bukkit 的 {@link World.Environment} 名（{@code NORMAL} /
     * {@code NETHER} / {@code THE_END}，大小写不敏感），另外兼容几个常见别名：
     * {@code overworld} → {@code NORMAL}、{@code end} → {@code THE_END}。
     * <p>三种情况分开处理：</p>
     * <ul>
     *   <li>没配这一项 → 默认下界 + 末地；</li>
     *   <li>显式写了空列表 {@code dimensions: []} → 一个维度都不乱码（明确的关闭意图）；</li>
     *   <li>写了但全是无效名字 → 退回默认，避免手滑写错就静默失效。</li>
     * </ul>
     */
    private static Set<World.Environment> readEnvironments(ConfigurationSection section) {
        if (section == null || !section.isSet("dimensions")) {
            return defaultEnvironments();
        }
        List<String> raw = section.getStringList("dimensions");
        if (raw.isEmpty()) {
            return Collections.emptySet();
        }
        Set<World.Environment> result = EnumSet.noneOf(World.Environment.class);
        for (String entry : raw) {
            World.Environment environment = parseEnvironment(entry);
            if (environment != null) {
                result.add(environment);
            }
        }
        // 全是无效值等于没写对：退回默认，而不是“一个维度都不生效”
        return result.isEmpty() ? defaultEnvironments() : result;
    }

    /** 默认生效维度：下界 + 末地（这两个维度没有昼夜循环）。 */
    private static Set<World.Environment> defaultEnvironments() {
        return Collections.unmodifiableSet(EnumSet.of(World.Environment.NETHER, World.Environment.THE_END));
    }

    private static World.Environment parseEnvironment(String raw) {
        String value = normalize(raw);
        if (value.isEmpty()) {
            return null;
        }
        switch (value) {
            case "overworld":
            case "world":
                return World.Environment.NORMAL;
            case "end":
                return World.Environment.THE_END;
            default:
                break;
        }
        try {
            return World.Environment.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * 读 {@code formats}：键是占位符名（不带 {@code %}，写 {@code %gametime%} 也认），
     * 值是乱码文本。缺配时默认 {@code gametime} 与 {@code daynight}。
     */
    private static Map<String, String> readFormats(ConfigurationSection section) {
        ConfigurationSection formatSection = section == null ? null : section.getConfigurationSection("formats");
        Map<String, String> result = new LinkedHashMap<>();
        if (formatSection != null) {
            for (String key : formatSection.getKeys(false)) {
                String placeholder = normalize(key);
                String text = formatSection.getString(key);
                if (placeholder.isEmpty() || text == null || text.isEmpty()) {
                    continue;
                }
                result.put(placeholder, text);
            }
        }
        if (result.isEmpty()) {
            result.put("gametime", "&k??:??");
            result.put("daynight", "&k????");
        }
        return Collections.unmodifiableMap(result);
    }

    /** 占位符名归一化：去空白、去两侧 {@code %}、转小写。 */
    private static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        if (value.startsWith("%")) {
            value = value.substring(1);
        }
        if (value.endsWith("%")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
