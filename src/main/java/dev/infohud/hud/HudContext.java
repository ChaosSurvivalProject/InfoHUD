package dev.infohud.hud;

import java.util.Locale;

import org.bukkit.Bukkit;

/**
 * HUD 每轮刷新期间的公共上下文（现实时间、TPS 这类跟玩家无关的值只算一次）。
 */
public final class HudContext {

    /** 现实时间，例如 "21:45:12"。 */
    private final String realtime;

    /** 服务器 TPS 文本；模板里没用到 %tps% 时是空串。 */
    private final String tps;

    public HudContext(String realtime, String tps) {
        this.realtime = realtime;
        this.tps = tps;
    }

    public String realtime() {
        return realtime;
    }

    public String tps() {
        return tps;
    }

    /** TPS 保留 1 位小数；取 1 分钟窗口（下标 0）。 */
    public static String formatTps(double[] tps) {
        if (tps == null || tps.length == 0) {
            return "";
        }
        return String.format(Locale.ROOT, "%.1f", tps[0]);
    }

    /** Bukkit 不提供该值时（极少数 fork）返回空串而不是抛异常。 */
    public static String readTps(boolean needed) {
        if (!needed) {
            return "";
        }
        try {
            return formatTps(Bukkit.getTPS());
        } catch (Throwable ignored) {
            return "";
        }
    }
}
