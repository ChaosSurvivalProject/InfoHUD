package dev.infohud.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 游戏时间换算：tick 0/24000 → 06:00，6000 → 12:00，12000 → 18:00，18000 → 00:00。
 */
class TimeUtilTest {

    @Test
    @DisplayName("关键 tick 换算成需求给的 HH:MM")
    void gameTimeKeyTicks() {
        assertEquals("06:00", TimeUtil.gameTime(0));
        assertEquals("06:00", TimeUtil.gameTime(24000));
        assertEquals("12:00", TimeUtil.gameTime(6000));
        assertEquals("18:00", TimeUtil.gameTime(12000));
        assertEquals("00:00", TimeUtil.gameTime(18000));
    }

    @Test
    @DisplayName("非整千 tick 按 60/1000 折算分钟")
    void gameTimeMinutes() {
        assertEquals("06:54", TimeUtil.gameTime(900));    // 900 * 60 / 1000 = 54 分
        assertEquals("12:30", TimeUtil.gameTime(6500));   // 500 -> 30 分
        assertEquals("23:59", TimeUtil.gameTime(17999));  // 17999/1000=17 -> 23 时，999 -> 59 分
        assertEquals("05:59", TimeUtil.gameTime(23999));
    }

    @Test
    @DisplayName("越界与负数 tick 会被折回一天内")
    void gameTimeWraps() {
        assertEquals("12:00", TimeUtil.gameTime(30000));  // 30000 - 24000 = 6000
        assertEquals("05:59", TimeUtil.gameTime(-1));     // -1 -> 23999
        assertEquals(0, TimeUtil.wrap(24000));
        assertEquals(23999, TimeUtil.wrap(-1));
    }

    @Test
    @DisplayName("hourOf / minuteOf 与查表结果一致")
    void hourMinuteMatchTable() {
        for (int tick = 0; tick < TimeUtil.DAY_TICKS; tick += 137) {
            String expected = String.format("%02d:%02d", TimeUtil.hourOf(tick), TimeUtil.minuteOf(tick));
            assertEquals(expected, TimeUtil.gameTime(tick), "tick=" + tick);
        }
    }

    @Test
    @DisplayName("世界天数从 1 开始，按 fullTime / 24000 累加")
    void worldDayCountsFromOne() {
        assertEquals(1L, TimeUtil.worldDay(0));
        assertEquals(1L, TimeUtil.worldDay(23_999));
        assertEquals(2L, TimeUtil.worldDay(24_000));
        assertEquals(2L, TimeUtil.worldDay(47_999));
        assertEquals(128L, TimeUtil.worldDay(127L * TimeUtil.DAY_TICKS));
        assertEquals("1", TimeUtil.worldDays(0));
        assertEquals("128", TimeUtil.worldDays(127L * TimeUtil.DAY_TICKS + 500));
    }

    @Test
    @DisplayName("负数或异常 fullTime 兜底为第 1 天，不会出现 0 或负天数")
    void worldDayClampsToAtLeastOne() {
        assertEquals(1L, TimeUtil.worldDay(-1));
        assertEquals(1L, TimeUtil.worldDay(-TimeUtil.DAY_TICKS * 5));
    }

    @Test
    @DisplayName("现实时间按指定时区输出，形状为 HH:mm:ss")
    void realTimeShape() {
        String value = TimeUtil.realTime(ZoneId.of("Asia/Shanghai"), DateTimeFormatter.ofPattern("HH:mm:ss"));
        assertTrue(value.matches("\\d{2}:\\d{2}:\\d{2}"), value);
    }
}
