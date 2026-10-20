package dev.infohud.time;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 时间换算工具。
 *
 * <p>性能说明：游戏时间字符串（HH:MM）在类加载时一次性预生成 24000 条并放进数组，
 * 运行期只做一次数组下标访问。HUD 每秒要为几十名玩家取时间，这样能完全避免
 * 主线程上的 {@code String.format} / 除法运算。</p>
 */
public final class TimeUtil {

    /**  Minecraft 一个完整昼夜的 tick 数。0/24000 = 06:00，6000 = 12:00，12000 = 18:00，18000 = 00:00。 */
    public static final int DAY_TICKS = 24000;

    /** 预生成的游戏时间文本表，下标即 tick（0 ~ 23999）。 */
    private static final String[] GAME_TIME_LABELS = buildGameTimeTable();

    private TimeUtil() {
    }

    /**
     * 把任意 tick 数（可能为负或超过 24000）折算到 [0, 24000) 区间。
     *
     * @param rawTime {@code World#getTime()} 的原始值
     * @return 区间内的 tick
     */
    public static int wrap(long rawTime) {
        long value = rawTime % DAY_TICKS;
        if (value < 0) {
            value += DAY_TICKS;
        }
        return (int) value;
    }

    /** tick 对应的“小时”（0-23，已按 06:00 起点偏移）。 */
    public static int hourOf(long rawTime) {
        return (wrap(rawTime) / 1000 + 6) % 24;
    }

    /** tick 对应的“分钟”（0-59）。 */
    public static int minuteOf(long rawTime) {
        return wrap(rawTime) % 1000 * 60 / 1000;
    }

    /**
     * 游戏时间，格式 HH:MM（24 小时制）。
     *
     * @param rawTime {@code World#getTime()}
     * @return 例如 "14:30"
     */
    public static String gameTime(long rawTime) {
        return GAME_TIME_LABELS[wrap(rawTime)];
    }

    /**
     * 世界已流逝的天数，<b>从 1 开始</b>（新世界开局即“第 1 天”）。
     *
     * <p>{@code World#getTime()} 每天都会归零，跨天的累计值必须用 {@code World#getFullTime()}。
     * 原版 {@code /time query day}（以及 F3）给的是从 0 开始的编号，本方法比它大 1。</p>
     *
     * @param fullTime {@code World#getFullTime()} 原始 tick
     * @return 天数，至少为 1（负数/异常值兜底）
     */
    public static long worldDay(long fullTime) {
        long day = fullTime / DAY_TICKS + 1;
        return day < 1L ? 1L : day;
    }

    /** {@link #worldDay} 的字符串形式，供模板直接填充。 */
    public static String worldDays(long fullTime) {
        return Long.toString(worldDay(fullTime));
    }

    /**
     * 现实世界时间。
     *
     * @param zone      时区（来自配置，非法值已在配置层回退）
     * @param formatter 格式化器（来自配置的 DateTimeFormatter 模式）
     * @return 例如 "21:45:12"
     */
    public static String realTime(ZoneId zone, DateTimeFormatter formatter) {
        return ZonedDateTime.now(zone).format(formatter);
    }

    /**
     * 手工拼接时间字符串，比 {@code String.format} 快一个数量级。
     */
    private static String[] buildGameTimeTable() {
        String[] table = new String[DAY_TICKS];
        char[] buffer = new char[5];
        for (int tick = 0; tick < DAY_TICKS; tick++) {
            int hours = (tick / 1000 + 6) % 24;
            int minutes = tick % 1000 * 60 / 1000;
            buffer[0] = (char) ('0' + hours / 10);
            buffer[1] = (char) ('0' + hours % 10);
            buffer[2] = ':';
            buffer[3] = (char) ('0' + minutes / 10);
            buffer[4] = (char) ('0' + minutes % 10);
            table[tick] = new String(buffer);
        }
        return table;
    }
}
