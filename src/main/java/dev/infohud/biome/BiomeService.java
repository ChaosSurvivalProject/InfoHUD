package dev.infohud.biome;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.entity.Player;

import dev.infohud.Permissions;
import dev.infohud.config.PluginConfig;
import dev.infohud.text.DividerUtil;
import dev.infohud.text.TextUtil;
import dev.infohud.util.BiomeUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;

/**
 * 玩家群系状态跟踪：缓存上一次群系、判定是否切换、按距离阈值发提示。
 *
 * <p>三张表都用 {@link ConcurrentHashMap}：主线程写、异步线程（PlaceholderAPI、偏好落盘）只读，
 * 不需要额外同步。</p>
 * <ul>
 *   <li>{@code lastBiome}：需求里的 {@code ConcurrentHashMap<UUID, Biome>}，玩家上一次所在群系；</li>
 *   <li>{@code lastCheckTick}：上一次真正查询群系时的服务器 tick，构成“每玩家查询冷却”，
 *       避免同一玩家一秒跨多个方块时把主线程打满；</li>
 *   <li>{@code anchors}：上一次<b>发送提示</b>的坐标，配合 {@code min-move-distance}
 *       抑制在群系交界线反复横跳造成的刷屏。</li>
 * </ul>
 *
 * <p>刷屏抑制的细节：玩家在“距上次提示不足 min-move-distance”时跨进新群系，提示不会丢，
 * 而是挂到 {@code pending}，等走够距离补发 —— 既不高频刷屏，也不会“换了群系完全没提示”。</p>
 *
 * <p>群系记忆（{@link BiomeMemory}）：同一个群系在 {@code biome-notification.memory-seconds}
 * 内已经展示过，就不再重复展示 —— 在平原/森林之间来回走动不会反复弹同一个名字。
 * 记忆只存在内存里，玩家下线即清理，不落盘。</p>
 *
 * <p>切换提示的展示方式由 {@code biome-notification.mode} 一处决定：
 * {@code title}（默认，屏幕中央大字，停留 {@code center-duration-seconds}，默认 3s）、
 * {@code chat}（聊天栏消息）、{@code off}（关闭，完全不提示；等价于旧的
 * {@code enabled: false}）。</p>
 *
 * <p>跨世界注意：Bukkit 的 {@code Location#distanceSquared} 在两个坐标属于不同世界时直接抛
 * {@code IllegalArgumentException}，所以所有距离判定都必须先确认同世界。</p>
 */
public final class BiomeService {

    private final Map<UUID, Biome> lastBiome = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> lastCheckTick = new ConcurrentHashMap<>();
    private final Map<UUID, Location> anchors = new ConcurrentHashMap<>();
    /** 被距离阈值暂时挡下、等走够距离后补发的提示。 */
    private final Map<UUID, Biome> pending = new ConcurrentHashMap<>();
    /** 挂起提示的起始坐标（用来量“还需要走多远”）。 */
    private final Map<UUID, Location> pendingFrom = new ConcurrentHashMap<>();
    /** 群系记忆：某群系最近一次展示的时刻，冷却期内不再重复展示（纯内存）。 */
    private final BiomeMemory memory = new BiomeMemory();

    /**
     * 玩家移动（监听器已过滤为“确实跨了方块”）时调用。
     *
     * @param player 玩家
     * @param config 当前配置快照（只读）
     * @return 玩家当前群系；区块未加载等取不到的情况返回 null
     */
    public Biome onMove(Player player, PluginConfig config) {
        UUID id = player.getUniqueId();

        // 1) 每玩家查询冷却
        int cooldown = config.queryCooldownTicks();
        if (cooldown > 0) {
            Integer lastTick = lastCheckTick.get(id);
            int currentTick = Bukkit.getCurrentTick();
            if (lastTick != null && currentTick - lastTick < cooldown) {
                return lastBiome.get(id);
            }
            lastCheckTick.put(id, currentTick);
        }

        // 2) 读群系（loaded-chunk-only 时不会触发区块生成）
        Biome current = BiomeUtil.readBiome(player, config.loadedChunkOnly());
        if (current == null) {
            return null;
        }

        Biome previous = lastBiome.put(id, current);

        // 3) 第一次拿到群系（刚上线、落到未加载区域）只建立基线，不提示 ——
        //    对应需求“首次进入服务器时不应发送欢迎提示”
        if (previous == null) {
            anchors.put(id, player.getLocation());
            return current;
        }

        boolean canNotify = config.notifyEnabled() && player.hasPermission(Permissions.NOTIFY);
        if (previous.equals(current)) {
            if (canNotify) {
                flushPending(player, config);
            }
            return current;
        }

        // 4) 群系发生变化
        if (!canNotify) {
            return current;
        }
        Location anchor = anchors.get(id);
        if (readyToNotify(player, anchor, config)) {
            anchors.put(id, player.getLocation());
            clearPending(id);
            notifyOnceRemembered(player, current, config);
        } else {
            // 暂时不满足条件：先记下，之后走够距离补发（HUD 立刻就是新群系，不受影响）
            pending.put(id, current);
            pendingFrom.put(id, player.getLocation());
        }
        return current;
    }

    /**
     * HUD / PlaceholderAPI 读取当前群系：优先用缓存，没有就补一次查询。
     */
    public Biome currentBiome(Player player, PluginConfig config) {
        Biome cached = lastBiome.get(player.getUniqueId());
        return cached != null ? cached : onMove(player, config);
    }

    /** 只读缓存值，供异步场景（PlaceholderAPI）使用；未缓存返回 null，绝不触发区块查询。 */
    public Biome cachedBiome(UUID playerId) {
        return lastBiome.get(playerId);
    }

    /** 该玩家已记住多少个群系（{@code /infohud status} 展示用）。 */
    public int memorySize(UUID playerId) {
        return memory.size(playerId);
    }

    /** 玩家离线：清掉所有状态，避免 UUID 表无限增长。 */
    public void forget(UUID playerId) {
        lastBiome.remove(playerId);
        lastCheckTick.remove(playerId);
        anchors.remove(playerId);
        clearPending(playerId);
        // 群系记忆不落盘，玩家下线即丢弃（下次上线当成“没来过”）
        memory.forget(playerId);
    }

    /** 重载配置：清掉冷却，让下一次移动立刻按新参数重新对齐。 */
    public void resetCooldown() {
        lastCheckTick.clear();
        // reload 后按新的 memory-seconds 重新计数，避免旧记忆把新配置“吃掉”
        memory.clear();
    }

    /** 插件停用：清掉全部缓存，避免热重载后残留。 */
    public void forgetAll() {
        lastBiome.clear();
        lastCheckTick.clear();
        anchors.clear();
        pending.clear();
        pendingFrom.clear();
        memory.clear();
    }

    /** 启动 / reload 后为在线玩家建立群系基线（不发提示）。 */
    public void prime(Iterable<? extends Player> players, PluginConfig config) {
        for (Player player : players) {
            Biome biome = BiomeUtil.readBiome(player, config.loadedChunkOnly());
            if (biome != null) {
                UUID id = player.getUniqueId();
                lastBiome.put(id, biome);
                anchors.put(id, player.getLocation());
                clearPending(id);
            }
        }
    }

    /**
     * 换世界 / 传送后刷新：绕开每玩家查询冷却，走正常的 {@link #onMove} 比较流程，
     * 是否提示由 {@code notify-on-world-change} 决定（见 {@link #readyToNotify}）。
     */
    public void refresh(Player player, PluginConfig config) {
        if (!player.isOnline()) {
            return;
        }
        lastCheckTick.remove(player.getUniqueId());
        onMove(player, config);
    }

    // ------------------------------------------------------------------

    /**
     * 现在是否适合发提示。
     *
     * <ul>
     *   <li>没有锚点（第一次）：允许；</li>
     *   <li>锚点在别的世界：不比距离（比了会抛异常），由 {@code notify-on-world-change} 决定；</li>
     *   <li>同世界：需走够 {@code min-move-distance}。</li>
     * </ul>
     */
    private boolean readyToNotify(Player player, Location anchor, PluginConfig config) {
        if (anchor == null) {
            return true;
        }
        World anchorWorld = anchor.getWorld();
        if (anchorWorld == null || !anchorWorld.equals(player.getWorld())) {
            return config.notifyOnWorldChange();
        }
        return farEnough(anchor, player.getLocation(), config.minMoveDistance());
    }

    /** 挂起的提示是否已经走够距离，走够就补发。 */
    private void flushPending(Player player, PluginConfig config) {
        UUID id = player.getUniqueId();
        Biome waiting = pending.get(id);
        if (waiting == null) {
            return;
        }
        Location from = pendingFrom.get(id);
        World fromWorld = from == null ? null : from.getWorld();
        // 挂起期间换了世界：距离判定无意义，直接按“够远”处理
        boolean sameWorld = fromWorld != null && fromWorld.equals(player.getWorld());
        if (sameWorld && !farEnough(from, player.getLocation(), config.minMoveDistance())) {
            return;
        }
        pending.remove(id);
        pendingFrom.remove(id);
        anchors.put(id, player.getLocation());
        notifyOnceRemembered(player, waiting, config);
    }

    private void clearPending(UUID id) {
        pending.remove(id);
        pendingFrom.remove(id);
    }

    /** 两点距离是否达到阈值（阈值 &lt;= 0 视为总是满足）。仅用于同世界坐标。 */
    private static boolean farEnough(Location from, Location to, double minDistance) {
        if (minDistance <= 0 || from == null) {
            return true;
        }
        return from.distanceSquared(to) >= minDistance * minDistance;
    }

    /**
     * 按“群系记忆”决定要不要展示，展示后立刻记下时刻。
     *
     * <p>记忆粒度是 <b>玩家 + 群系</b>：同一个群系在 {@code memory-seconds} 内只展示一次；
     * 中途去过别的群系也不会重置计时（想要“必须离开过才重算”的语义很容易反过来把提示吞掉，
     * 这里选更可预期的超时制）。{@code memory-seconds = 0} 即完全关闭记忆。</p>
     */
    private void notifyOnceRemembered(Player player, Biome biome, PluginConfig config) {
        UUID id = player.getUniqueId();
        String biomeId = BiomeUtil.idOf(biome);
        long now = System.currentTimeMillis();
        if (memory.tooSoon(id, biomeId, now, config.memoryMillis())) {
            return;
        }
        memory.remember(id, biomeId, now);
        sendNotification(player, biome, config);
    }

    /**
     * 发送提示：按配置选择屏幕中央大字或聊天栏消息。
     *
     * <p>{@code title} 模式用 title 放大字文案、subtitle 放它下方的横线：
     * subtitle 的缩放只有 title 的一半，正好可以做出“细线贴在字下面”的效果
     * （横线字符数会按缩放比补足，见 {@link DividerUtil#build}）。</p>
     */
    private void sendNotification(Player player, Biome biome, PluginConfig config) {
        PluginConfig.NotificationMode mode = config.notifyMode();
        if (mode == PluginConfig.NotificationMode.OFF) {
            // 兜底：正常流程里 notifyEnabled() 已经拦过，这里再挡一次，防止将来漏判
            return;
        }
        String name = BiomeUtil.displayName(biome, config);
        if (mode == PluginConfig.NotificationMode.TITLE) {
            Component title = TextUtil.parse(
                    TextUtil.fill(config.centerTitle(),
                            "%biome%", name,
                            "%biome_id%", BiomeUtil.idOf(biome),
                            "%player%", player.getName()),
                    config);
            Component subtitle = buildDivider(title, config);
            int staySeconds = config.centerDurationSeconds();
            player.showTitle(Title.title(title, subtitle,
                    Title.Times.times(Duration.ofSeconds(1),
                            Duration.ofSeconds(staySeconds), Duration.ofSeconds(1))));
            return;
        }
        // 走到这里只可能是 CHAT：OFF 在调用前就被 notifyEnabled() 拦掉了
        String filled = TextUtil.fill(config.notifyFormat(),
                "%biome%", name,
                "%biome_id%", BiomeUtil.idOf(biome),
                "%player%", player.getName());
        player.sendMessage(TextUtil.parse(filled, config));
    }

    /**
     * 标题下方的横线组件。
     *
     * <p>形状是 {@code ————✦————}：填充段在中间符号左右<b>等长</b>，所以符号精确居中。
     * 横线继承标题最外层的颜色（白色标题 → 白色横线，和参考图一致），
     * 并强制加粗 + 关掉斜体 —— 原版字体的斜体会让横线歪掉。
     * 横线长度为 0 时返回空组件（等于不画 subtitle）。</p>
     */
    private static Component buildDivider(Component title, PluginConfig config) {
        String fill = config.centerDivider();
        String symbol = config.centerDividerSymbol();
        if (config.centerDividerScale() <= 0) {
            return Component.empty();
        }
        String line = DividerUtil.build(PlainTextComponentSerializer.plainText().serialize(title),
                fill, symbol, config.centerDividerScale());
        if (line.isEmpty()) {
            return Component.empty();
        }
        TextColor color = title.color() != null ? title.color() : NamedTextColor.WHITE;
        return Component.text(line, color, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false);
    }
}
