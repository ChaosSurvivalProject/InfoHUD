package dev.infohud.time;

/**
 * 游戏内时段。标签文案由配置文件 {@code day-night.labels} 提供，
 * 这里只表示“现在是哪个时段”。
 */
public enum DayNightPhase {

    SUNRISE("日出"),
    DAY("白天"),
    SUNSET("日落"),
    NIGHT("黑夜");

    /** 配置缺失时的兜底标签。 */
    private final String fallbackLabel;

    DayNightPhase(String fallbackLabel) {
        this.fallbackLabel = fallbackLabel;
    }

    public String fallbackLabel() {
        return fallbackLabel;
    }
}
