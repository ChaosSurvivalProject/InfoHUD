package dev.infohud.hud;

/**
 * 某个世界在本次 HUD 刷新中的快照。
 *
 * <p>同一世界里的所有玩家游戏时间和时段完全一致，所以每个世界每轮只算一次，
 * 再分发给该世界的玩家 —— 50 人同屏时时间换算从 50 次降到 1 次。</p>
 *
 * @param rawTime       {@code World#getTime()} 原始 tick
 * @param gameTime      游戏时间文本（HH:MM）
 * @param dayNightLabel 时段标签（已带配置里的颜色代码）
 */
public record WorldSnapshot(long rawTime, String gameTime, String dayNightLabel) {
}
