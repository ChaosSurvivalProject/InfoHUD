package dev.infohud.biome;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 群系记忆：记住每个玩家最近一次<b>展示过</b>某个群系的时刻，用于“一段时间内不重复展示”。
 *
 * <p>纯内存实现，不落盘：服务器重启或玩家下线（{@code BiomeService#forget}）即清空。
 * 键为玩家 UUID → 群系 ID → 上次展示时刻（{@code System.currentTimeMillis()}）。</p>
 *
 * <p>选毫秒而不是 tick：服务器 tick 计数重启归零、且卡服时会“走过的时间比 tick 少”，
 * 而这里要的是真实经过的时间，用墙钟更直观。时间源由调用方传入，方便单元测试。</p>
 *
 * <p>线程约定与 {@code BiomeService} 一致：主线程写，异步线程（PlaceholderAPI 等）不会碰这张表，
 * 用 {@link ConcurrentHashMap} 纯防御。</p>
 */
public final class BiomeMemory {

    /** 玩家 → （群系 ID → 上次展示的毫秒时间戳）。 */
    private final Map<UUID, Map<String, Long>> shownAt = new ConcurrentHashMap<>();

    /**
     * 这个群系现在是否应当<b>静默</b>（刚才展示过，还在冷却里）。
     *
     * @param playerId       玩家
     * @param biomeId        群系 ID（见 {@code BiomeUtil#idOf}）
     * @param nowMillis      当前毫秒时间
     * @param cooldownMillis 冷却时长；{@code <= 0} 表示关闭记忆功能（永不静默）
     * @return true = 冷却期内已展示过，本次不展示
     */
    public boolean tooSoon(UUID playerId, String biomeId, long nowMillis, long cooldownMillis) {
        if (cooldownMillis <= 0 || playerId == null || biomeId == null) {
            return false;
        }
        Map<String, Long> perBiome = shownAt.get(playerId);
        if (perBiome == null) {
            return false;
        }
        Long last = perBiome.get(biomeId);
        // 时间戳比现在还晚（系统时间被回拨）时按“刚展示过”处理，宁可静默也不刷屏
        return last != null && nowMillis - last < cooldownMillis;
    }

    /** 记录“这个群系刚给该玩家展示过”。 */
    public void remember(UUID playerId, String biomeId, long nowMillis) {
        if (playerId == null || biomeId == null) {
            return;
        }
        shownAt.computeIfAbsent(playerId, key -> new ConcurrentHashMap<>()).put(biomeId, nowMillis);
    }

    /** 该玩家已记住多少个群系（{@code /infohud status} 展示用）。 */
    public int size(UUID playerId) {
        Map<String, Long> perBiome = shownAt.get(playerId);
        return perBiome == null ? 0 : perBiome.size();
    }

    /** 玩家离线：清掉他的记忆，避免 UUID 表无限增长。 */
    public void forget(UUID playerId) {
        if (playerId != null) {
            shownAt.remove(playerId);
        }
    }

    /** 插件停用：清掉全部记忆。 */
    public void clear() {
        shownAt.clear();
    }
}
