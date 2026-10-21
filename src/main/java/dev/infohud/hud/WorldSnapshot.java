package dev.infohud.hud;

import dev.infohud.time.WorldDate;

/**
 * 某个世界在本次 HUD 刷新中的快照。
 *
 * <p>同一世界里的所有玩家游戏时间和时段完全一致，所以每个世界每轮只算一次，
 * 再分发给该世界的玩家 —— 50 人同屏时时间换算从 50 次降到 1 次。</p>
 *
 * @param rawTime       {@code World#getTime()} 原始 tick
 * @param gameTime      游戏时间文本（HH:MM）
 * @param dayNightLabel 时段标签（已带配置里的颜色代码）
 * @param worldDays     世界天数文本（从 1 开始，取自 {@code World#getFullTime()}）
 * @param date          由世界天数换算出的日期与季节（年/月/日 + 季节键名与显示名）
 */
public record WorldSnapshot(long rawTime, String gameTime, String dayNightLabel, String worldDays, WorldDate date) {

    /** {@code %world_year%}：年份文本。 */
    public String worldYear() {
        return Integer.toString(date.year());
    }

    /** {@code %world_month%}：月份文本（1~12，不补零）。 */
    public String worldMonth() {
        return Integer.toString(date.month());
    }

    /** {@code %world_day%}：当月第几天文本（1~31，不补零）。 */
    public String worldDay() {
        return Integer.toString(date.day());
    }

    /** {@code %world_date%}：游戏日期文本，格式 {@code yyyy-MM-dd}。 */
    public String worldDate() {
        return date.isoDate();
    }

    /** {@code %world_season%}：季节键名（如 {@code spring}）。 */
    public String worldSeason() {
        return date.season();
    }

    /** {@code %world_season_name%}：季节显示名（可能带颜色代码，交给 TextUtil 解析）。 */
    public String worldSeasonName() {
        return date.seasonName();
    }
}
