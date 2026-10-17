package dev.infohud.biome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 群系记忆：冷却判定、按玩家与群系分别计时、清理与关闭开关。
 */
class BiomeMemoryTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    @DisplayName("没记录过：任何群系都不静默")
    void unknownBiomeIsNotSuppressed() {
        BiomeMemory memory = new BiomeMemory();
        assertFalse(memory.tooSoon(PLAYER, "plains", 1_000L, 300_000L));
    }

    @Test
    @DisplayName("刚记过：冷却期内静默，冷却期满后放行")
    void suppressedWithinCooldownOnly() {
        BiomeMemory memory = new BiomeMemory();
        memory.remember(PLAYER, "plains", 1_000L);
        assertTrue(memory.tooSoon(PLAYER, "plains", 1_001L, 300_000L));
        assertTrue(memory.tooSoon(PLAYER, "plains", 300_999L, 300_000L));
        // 正好到点就放行（用 < 比较，边界不需要再等一个 tick）
        assertFalse(memory.tooSoon(PLAYER, "plains", 301_000L, 300_000L));
    }

    @Test
    @DisplayName("记忆按群系分开：平原记过不影响森林")
    void suppressionIsPerBiome() {
        BiomeMemory memory = new BiomeMemory();
        memory.remember(PLAYER, "plains", 1_000L);
        assertTrue(memory.tooSoon(PLAYER, "plains", 2_000L, 300_000L));
        assertFalse(memory.tooSoon(PLAYER, "forest", 2_000L, 300_000L));
    }

    @Test
    @DisplayName("记忆按玩家分开：别人的记忆不影响我")
    void suppressionIsPerPlayer() {
        BiomeMemory memory = new BiomeMemory();
        memory.remember(PLAYER, "plains", 1_000L);
        assertFalse(memory.tooSoon(OTHER, "plains", 2_000L, 300_000L));
        assertEquals(1, memory.size(PLAYER));
        assertEquals(0, memory.size(OTHER));
    }

    @Test
    @DisplayName("冷却时长 <= 0 视为关闭记忆")
    void zeroCooldownDisablesMemory() {
        BiomeMemory memory = new BiomeMemory();
        memory.remember(PLAYER, "plains", 1_000L);
        assertFalse(memory.tooSoon(PLAYER, "plains", 1_001L, 0L));
        assertFalse(memory.tooSoon(PLAYER, "plains", 1_001L, -5L));
    }

    @Test
    @DisplayName("系统时间被回拨时按“刚展示过”静默，不刷屏")
    void clockGoesBackwards() {
        BiomeMemory memory = new BiomeMemory();
        memory.remember(PLAYER, "plains", 100_000L);
        assertTrue(memory.tooSoon(PLAYER, "plains", 90_000L, 300_000L));
    }

    @Test
    @DisplayName("forget 清掉单个玩家，clear 清掉全部")
    void forgetAndClear() {
        BiomeMemory memory = new BiomeMemory();
        memory.remember(PLAYER, "plains", 1_000L);
        memory.remember(OTHER, "desert", 1_000L);
        memory.forget(PLAYER);
        assertEquals(0, memory.size(PLAYER));
        assertFalse(memory.tooSoon(PLAYER, "plains", 2_000L, 300_000L));
        assertEquals(1, memory.size(OTHER));
        memory.clear();
        assertEquals(0, memory.size(OTHER));
    }

    @Test
    @DisplayName("同一群系重复 remember 只更新时间戳，条目数不增长")
    void rememberIsIdempotent() {
        BiomeMemory memory = new BiomeMemory();
        memory.remember(PLAYER, "plains", 1_000L);
        memory.remember(PLAYER, "plains", 200_000L);
        assertEquals(1, memory.size(PLAYER));
        // 以最后一次记录为准
        assertTrue(memory.tooSoon(PLAYER, "plains", 499_999L, 300_000L));
        assertFalse(memory.tooSoon(PLAYER, "plains", 500_000L, 300_000L));
    }

    @Test
    @DisplayName("null 玩家/群系不抛异常")
    void nullSafe() {
        BiomeMemory memory = new BiomeMemory();
        assertFalse(memory.tooSoon(null, "plains", 1_000L, 300_000L));
        assertFalse(memory.tooSoon(PLAYER, null, 1_000L, 300_000L));
        memory.remember(null, "plains", 1_000L);
        memory.remember(PLAYER, null, 1_000L);
        assertEquals(0, memory.size(PLAYER));
    }
}
