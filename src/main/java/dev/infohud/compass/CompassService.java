package dev.infohud.compass;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;

import dev.infohud.config.PluginConfig;
import dev.infohud.util.CompassUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * 顶部 BossBar 罗盘：每名玩家一条独占 BossBar（各自朝向不同），
 * 标题是以当前朝向为中心的 180° 刻度窗口（正中高亮格即方向，角度数字为刻度读法，见 {@link CompassUtil}），
 * 进度条位置同样是指针（北 = 0%，东 = 25%，南 = 50%，西 = 75%）。
 *
 * <p>两张表都用 {@link ConcurrentHashMap}：主线程写，读方只有主线程任务，
 * 不需要额外同步（与 {@code BiomeService} 同一套约定）。</p>
 * <ul>
 *   <li>{@code bars}：玩家 → 独占 BossBar + 上次发包的刻度位置；</li>
 *   <li>{@code titles}：<b>对齐后的</b>方位角 → 标题的 {@code §} 代码串。
 *       标题只按 5° 刻度变化，因此整圈只有 {@code 360 / 5 = 72} 种可能，缓存永不膨胀。</li>
 * </ul>
 *
 * <p>性能约束：两类包分开判定，各自只在真正变化时才发——</p>
 * <ul>
 *   <li>标题：跨 5° 刻度才发一次（慢转时约 5° 一个包）；</li>
 *   <li>进度：按 1° 粒度跟手（进度是纯客户端插值的位置值，比标题便宜）。</li>
 * </ul>
 * <p>玩家站桩不动时每轮任务只有两次整数比较 + 零网络包。</p>
 *
 * <p>说明：本版本的 BossBar 接口只有 {@code setTitle(String)}（无 Component 重载），
 * 标题组件统一序列化为 {@code §} 代码串再下发；新 API 没有 {@code close()}，
 * 清理用 {@code removeAll() + hide()}，BossBar 对象由 GC 回收。</p>
 *
 * <p>BossBar 的创建/更新都必须在主线程，由 {@link CompassTask} 保证。</p>
 */
public final class CompassService {

    /** 单玩家状态：独占的 BossBar，以及上次发包时的方位角 / 刻度位置。 */
    private static final class BarState {
        BossBar bar;
        /** 上次发进度的 1° 方位角，-1 表示还没发过。 */
        int lastBearing = -1;
        /** 上次发标题的 5° 对齐方位角，标题只在这个粒度变化。 */
        int lastAligned = -1;
    }

    /** BossBar 标题是字符串，组件统一序列化成 {@code §} 代码。 */
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('\u00A7')
            .build();

    private final Map<UUID, BarState> bars = new ConcurrentHashMap<>();
    private final Map<Integer, String> titles = new ConcurrentHashMap<>();

    /**
     * 刷新某玩家的罗盘（主线程）。BossBar 尚未创建时按当前配置懒创建。
     *
     * <p>标题按 5° 刻度、进度按 1° 粒度分别判定，各自没变就不发包；
     * 玩家站桩不动时只剩两次整数比较。</p>
     */
    public void update(Player player, PluginConfig config) {
        UUID id = player.getUniqueId();
        BarState state = bars.get(id);
        if (state == null) {
            state = createBar(config);
            state.bar.addPlayer(player);
            bars.put(id, state);
        }
        int bearing = CompassUtil.bearingOf(player.getLocation().getYaw());
        // 标题：只在跨 5° 刻度时重发（整圈 72 种，缓存复用）
        int aligned = CompassUtil.alignedBearing(bearing);
        if (aligned != state.lastAligned) {
            state.lastAligned = aligned;
            state.bar.setTitle(titles.computeIfAbsent(aligned,
                    b -> LEGACY.serialize(CompassUtil.buildTitle(b))));
        }
        // 进度：按 1° 跟手，保证转向时指针顺滑
        if (bearing != state.lastBearing) {
            state.lastBearing = bearing;
            state.bar.setProgress(CompassUtil.progressOf(bearing));
        }
    }

    private BarState createBar(PluginConfig config) {
        BarState state = new BarState();
        state.bar = Bukkit.createBossBar("", config.compassColor(), config.compassStyle());
        return state;
    }

    /** 玩家关闭罗盘 / 离线：摘掉玩家并隐藏，避免 BossBar 对象泄漏。 */
    public void closeFor(UUID id) {
        BarState state = bars.remove(id);
        if (state != null) {
            state.bar.removeAll();
            state.bar.hide();
        }
    }

    /** 配置重载 / 插件停用：清掉全部 BossBar（重载后按新配置重建）。 */
    public void closeAll() {
        bars.forEach((id, state) -> {
            state.bar.removeAll();
            state.bar.hide();
        });
        bars.clear();
    }

    /** 当前挂着的 BossBar 条数（status 展示用）。 */
    public int size() {
        return bars.size();
    }
}
