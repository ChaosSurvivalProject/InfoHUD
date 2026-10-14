package dev.infohud.placeholder;

import org.bukkit.entity.Player;

import me.clip.placeholderapi.PlaceholderAPI;

/**
 * PlaceholderAPI 调用桥。
 *
 * <p>这个类只允许在“确认 PlaceholderAPI 已安装”之后被首次引用：一旦类加载时
 * 找不到 {@link PlaceholderAPI}，JVM 会抛 {@code NoClassDefFoundError}。
 * 主类通过 {@code plugin.placeholderApiReady()} 保证这一点。</p>
 */
public final class PapiBridge {

    private PapiBridge() {
    }

    /**
     * 把字符串里的外部占位符（{@code %player_name%} 等）解析成值。
     *
     * @param player 玩家
     * @param text   已填充 InfoHUD 自身占位符的文本
     * @return 解析后的文本；PAPI 内部异常时返回原文，不影响 HUD
     */
    public static String setPlaceholders(Player player, String text) {
        try {
            return PlaceholderAPI.setPlaceholders(player, text);
        } catch (RuntimeException | StackOverflowError ex) {
            return text;
        }
    }
}
