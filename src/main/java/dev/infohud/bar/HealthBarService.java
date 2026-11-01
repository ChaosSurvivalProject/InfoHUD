package dev.infohud.bar;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffectType;

import dev.infohud.InfoHUD;
import dev.infohud.Permissions;
import dev.infohud.config.PluginConfig;
import dev.infohud.text.TextUtil;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * 受击生物血条：**只有玩家攻击生物**（近战、玩家射出的投射物，或可归因到玩家的爆炸）
 * 时，为该攻击者生成一条
 * <b>只属于他自己</b>的 BossBar，停留 {@code bar.time} 个 tick（默认 60 = 3 秒）后自动消失。
 *
 * <p><b>血条是「攻击者 × 生物」一一独占的</b>：玩家 A 打了僵尸，只有 A 能看到这条血条，
 * 旁边的玩家 B 完全看不到；B 自己也去打了同一只僵尸，才会生成 B 自己的那一条，
 * 并且两条各自独立计时、独立刷新（A 一直在打只会刷新 A 那条，B 那条照常倒计时消失）。</p>
 *
 * <ul>
 *   <li>再次受击：刷新标题/进度并重置倒计时（只影响攻击者自己那条）；</li>
 *   <li>死亡（{@link EntityDeathEvent} 或最后一次伤害把血打空）：该生物身上所有攻击者的
 *       血条都切换到 {@code bar.title.dead} 模板，进度归零，停留<b>更短的</b>
 *       {@code bar.dead-time} 个 tick（默认 20 = 1 秒）—— 死亡是终局信息，看一眼就够；</li>
 *   <li>黑名单（{@code blacklist}，默认 ENDER_DRAGON / WITHER / ARMOR_STAND）内的
 *       实体类型不显示血条；玩家默认不在名单内 —— 打玩家同样出血条，
 *       标题里 {@code <name>} 显示他的游戏 ID；</li>
 *   <li>爆炸归因：爆炸弓等 RPG 武器在<b>箭命中点</b>调不带来源的 {@code createExplosion}，
 *       产生的爆炸伤害事件<b>没有 damager</b>（连 {@code EntityDamageByEntityEvent} 都不是，
 *       所以本服务监听基类 {@code EntityDamageEvent}）—— 玩家投射物命中时记一笔
 *       （位置 + 射手 + tick），爆炸伤害按「同世界、1 秒内、受击点距命中点 4 格内」
 *       反查归因给射手；玩家点燃的 TNT 按 {@code TNTPrimed#getSource()} 归因。
 *       怪物火球、苦力怕等与玩家无关的爆炸仍不出血条；</li>
 *   <li>持续效果归因（中毒/着火）：{@code POISON} / {@code FIRE_TICK} 同样没有 damager，
 *       按「玩家之前给这只生物挂上过效果」归因 —— 燃烧箭/火焰附加点燃
 *       （{@link EntityCombustByEntityEvent}）、命中时目标已带毒/火（投射物命中检查）、
 *       玩家的毒云覆盖到生物（{@link AreaEffectCloudApplyEvent}）都会记一条效果记录，
 *       80 tick 内这只生物的毒/火掉血算到施加者头上（只认生物不认位置，跑远了也算）；</li>
 *   <li>总开关 {@code bar.enabled} 关闭时不生成新血条，{@code reload} 会摘掉已显示的血条；</li>
 *   <li>每名玩家还能用 {@code /infohud toggle healthbar} 自己开关血条，权限 {@code infohud.healthbar}；
 *       关掉的玩家<b>连包都不收</b>（受击时不会给他人建血条）。</li>
 * </ul>
 *
 * <p>标题占位符（模板里原样写尖括号形式）：</p>
 * <ul>
 *   <li>{@code <entity>} —— 实体原版翻译名（用 translatable 组件下发，客户端按自己语言渲染）；</li>
 *   <li>{@code <name>} —— 实体自定义名；攻击对象是玩家但没有命名牌时显示他的游戏 ID
 *       （{@code displayName()}，昵称插件的前后缀也会带上）；其它无名生物显示 {@code <entity>}；</li>
 *   <li>{@code <health>} / {@code <max>} —— 当前 / 最大生命值（死亡显示 0）；</li>
 *   <li>{@code <damage>} —— 本次受到的最终伤害（已扣减护甲等修正）。</li>
 * </ul>
 *
 * <p>标题模板整条交给 {@link MiniMessage} 一次解析（非严格模式，未知标签丢弃），
 * 五个占位符注册为自定义标签，颜色/状态在占位符之间连续不断
 * （比如 {@code <gray>} 会一直作用到 {@code <health>/<max>} 整段）；
 * 开启 {@code hud.ampersand-colors} 时 {@code &} 颜色代码先原地展开成 MiniMessage 标签
 * （{@link TextUtil#expandLegacy}），两种写法都支持。</p>
 *
 * <p><b>为什么用 Adventure 的 {@link BossBar} 而不是 {@code Bukkit#createBossBar}：</b>
 * 后者只有 {@code setTitle(String)}，服务端会把字符串当 {@code §} 代码解析成
 * <b>literal</b> 组件（{@code CraftChatMessage.fromString} 不认 JSON 也不认 translatable），
 * 于是 {@code Component.translatable(...)} 序列化后只剩一个裸翻译键，客户端就原样显示
 * {@code entity.minecraft.enderman}。Adventure 的 BossBar 标题是真正的 {@link Component}，
 * Paper 下发时保留 translatable，客户端按自己的语言渲染成“末影人 / Enderman”。
 * 标题里的 {@code §} 代码此时由 MiniMessage 解析，不再需要 {@code LegacyComponentSerializer}。</p>
 *
 * <p>所有操作都在主线程（事件 + {@link HealthBarSweepTask} 清扫），
 * 表用 {@link ConcurrentHashMap} 纯防御，与 {@code BiomeService} 同一套约定。</p>
 */
public final class HealthBarService implements Listener {

    /** 血条标题专用 MiniMessage（非严格：未知标签丢弃，不会打断标题）。 */
    private static final MiniMessage TITLE_MINI = MiniMessage.builder().strict(false).build();

    /**
     * 血条归属键：一条血条属于「某个攻击者 × 某个生物」这一对。
     *
     * <p>用这个键而不是「生物」当键，是血条互相独立的关键：
     * 同一个人打同一只怪 → 同一对（刷新原血条）；
     * 不同人打同一只怪 → 不同对（各自一条、各自计时、只有自己看得见）。</p>
     *
     * <p>包内可见是给单测用的：{@code HealthBarServiceTest} 直接断言
     * “玩家不同 → 键不同”，防止以后有人把键改回只用生物 UUID，
     * 那会让所有人的血条重新串在一起。</p>
     */
    record BarKey(UUID playerId, UUID entityId) {
    }

    /** 单条血条状态：BossBar 本体 + 归属的攻击者 + 到点消失的绝对 tick + 本次受到的伤害。 */
    private static final class BarState {
        final BossBar bar;
        final UUID playerId;
        long expiryTick;
        double lastDamage;
        /**
         * 标题里<b>当前已经显示</b>的血量 / 上限（格式化后的文本）。
         *
         * <p>用来判断“血量真的变了”才重发标题：清扫任务每 5 tick 跑一次，
         * 若不做这个比较，一条静止的血条每秒会白发 4 次标题包。</p>
         */
        String shownHealth;
        String shownMax;
        /** 是否已经切到死亡样式：死亡后不再按存活血量同步。 */
        boolean dead;

        BarState(BossBar bar, UUID playerId) {
            this.bar = bar;
            this.playerId = playerId;
        }
    }

    private final Map<BarKey, BarState> bars = new ConcurrentHashMap<>();
    private final InfoHUD plugin;

    public HealthBarService(InfoHUD plugin) {
        this.plugin = plugin;
    }

    // ------------------------- 间接伤害归因（爆炸 / 点燃 / 中毒） -------------------------

    /**
     * 玩家投射物的一次命中记录：爆炸类伤害事件发生时，按「同世界 + 时间接近 + 距离接近」
     * 反查这些记录，把没有来源实体的爆炸（爆炸弓的 {@code createExplosion}、
     * 玩家点燃的 TNT 等）归因给射手。
     *
     * <p>只记坐标不记实体引用，记录本身不持实体对象；过期记录在每次爆炸归因时顺手清理。</p>
     */
    private record HitRecord(UUID shooterId, UUID projectileId, UUID worldId, long tick, Location location) {
    }

    /**
     * 命中记录的保留窗口（tick）。爆炸伤害紧跟命中发生，1 秒（20 tick）足够宽裕；
     * 记录只在 {@link #onProjectileHit} 追加、在爆炸归因时按窗口清理，不会无限增长。
     */
    private static final long HIT_ATTRIBUTION_WINDOW_TICKS = 20;

    /** 命中点与受击者的归因距离上限（格）。爆炸中心 = 命中点，原版 4~5 格半径内都受爆炸伤害。 */
    private static final double HIT_ATTRIBUTION_DISTANCE_SQ = 4.0 * 4.0;

    /** 玩家投射物命中记录（<b>非玩家</b>投射物不记，见 {@link #onProjectileHit}）。 */
    private final List<HitRecord> recentHits = new ArrayList<>();

    // ------------------------- 持续效果归因（中毒 / 着火） -------------------------

    /**
     * 「生物身上挂上了玩家施加的中毒/着火」的一次记录：后续该生物的
     * {@code POISON} / {@code FIRE_TICK} 持续伤害没有 damager，
     * 按「同一只生物 + 记录未过期」直接归因给施加者。
     *
     * <p>记录<b>按目标生物分组</b>：毒/火是挂在具体生物身上的持续状态，
     * 与爆炸那种「一次事件伤一片」不同，不需要位置——同一只生物在效果存续期内
     * 掉血就算到施加者头上。</p>
     */
    private record EffectRecord(UUID applierId, UUID victimId, UUID worldId, long tick, long expiryTick) {
    }

    /**
     * 效果记录的默认存续时长（tick）：命中时只能看到 PotionEffect 的剩余时长，
     * 记录统一记这么久；实际毒/火伤害若超出窗口就不再归因（宁可少算不可错算）。
     * 火焰附加 I 实际烧 4 秒、中毒常见 5~12 秒，80 tick（4 秒）是保守值。
     */
    private static final long EFFECT_ATTRIBUTION_TICKS = 80;

    /** 玩家施加的中毒/着火记录（按目标生物分组，见 {@link #recordEffect}）。 */
    private final List<EffectRecord> recentEffects = new ArrayList<>();

    // ------------------------- 事件 -------------------------

    /**
     * <b>只有玩家造成的伤害</b>才触发：玩家直接攻击、玩家射出的投射物（箭、弩箭、
     * 喷溅药水……）、可归因到玩家的爆炸（爆炸弓命中后的爆炸、玩家点燃的 TNT）。
     * 自然掉血（摔落、火焰、溺水、窒息、虚空）与找不到责任玩家的伤害
     * （怪物互殴、骷髅射箭、苦力怕自爆、铁砧坠落）都不出血条。
     *
     * <p>血条只发给<b>造成这次伤害的那名玩家</b>，其他在线玩家收不到任何东西。</p>
     *
     * <p>{@code MONITOR + ignoreCancelled}：被其它插件取消的伤害不改血量，直接跳过。</p>
     *
     * <p><b>监听基类 {@link EntityDamageEvent} 而不是 {@code EntityDamageByEntityEvent}：</b>
     * 爆炸弓等 RPG 插件在命中点调的是<b>不带来源实体</b>的 {@code createExplosion}，
     * 生物受到的爆炸伤害事件没有 damager，根本不是 ByEntity 子类 —— 只听子类会漏掉。
     * 没有直接玩家来源时交给 {@link #attributeExplosion}，按命中记录/TNT 来源归因，
     * 归因不到就维持「不出血条」的原行为。</p>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        PluginConfig config = plugin.config();
        if (!config.healthBarEnabled()) {
            return;
        }
        if (!(event.getEntity() instanceof LivingEntity living)) {
            return;
        }
        Player attacker = null;
        boolean debug = plugin.config().debugExplosionAttribution();
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            attacker = playerAttacker(byEntity.getDamager());
        }
        if (attacker == null) {
            // 爆炸伤害没有直接来源时，按「最近的玩家投射物命中 / 玩家点燃的 TNT」归因；
            // 中毒/着火这类持续伤害按「玩家之前挂上的效果记录」归因
            EntityDamageEvent.DamageCause cause = event.getCause();
            if (debug) {
                plugin.getLogger().info("[效果归因] onDamage 无直接玩家来源，尝试归因：victim="
                        + nameOf(event.getEntity()) + " cause=" + cause
                        + (event instanceof EntityDamageByEntityEvent byEntity
                                ? " damager=" + damagerName(byEntity.getDamager()) : "（事件无 damager）"));
            }
            if (cause == EntityDamageEvent.DamageCause.POISON
                    || cause == EntityDamageEvent.DamageCause.FIRE_TICK) {
                attacker = attributeEffect(living, cause);
            } else {
                attacker = attributeExplosion(event);
            }
            if (attacker == null) {
                return;
            }
        }
        // 爆炸会伤到射手自己：不给自己出「自己的血条」（毒/火不会伤到施加者，此判断同样安全）
        if (attacker.getUniqueId().equals(living.getUniqueId())) {
            return;
        }
        // 攻击者自己关了血条（或没权限）：不建、也不刷新，别人更不会看到他的血条
        if (!seesHealthBars(attacker)) {
            return;
        }
        double damage = event.getFinalDamage();
        if (damage <= 0) {
            return;
        }
        if (isBlacklisted(config, living)) {
            return;
        }
        // 伤害事件在扣血<b>之前</b>触发，得自己算受击后的血量
        double healthAfter = Math.max(0.0, living.getHealth() - damage);
        boolean dead = healthAfter <= 0;
        String healthText = formatHealth(dead ? 0.0 : healthAfter);
        String maxText = formatHealth(maxHealth(living));
        Component title = buildTitle(living, healthText, maxText, formatHealth(damage), dead, config);
        float progress = (float) (dead ? 0.0 : progressOf(healthAfter, living));

        BarKey key = new BarKey(attacker.getUniqueId(), living.getUniqueId());
        BarState state = bars.get(key);
        if (state == null) {
            // 首帧就带上真实标题/进度：避免先给玩家一条空标题血条再补发更新包
            state = createBar(config, attacker, key, title, progress);
        } else {
            state.bar.name(title);
            state.bar.progress(progress);
        }
        state.lastDamage = damage;
        // 记下刚显示出去的血量：之后清扫任务据此判断要不要为“自然回血”重发包
        state.shownHealth = healthText;
        state.shownMax = maxText;
        state.dead = dead;
        state.expiryTick = Bukkit.getCurrentTick() + lifetimeTicks(config, dead);
    }

    /**
     * 伤害来源对应的「玩家攻击者」，不是玩家造成的伤害返回 null：
     * <ul>
     *   <li>玩家本人（近战、末影珍珠瞬移判定等直接伤害）；</li>
     *   <li>玩家射出的投射物（箭 / 弩箭 / 雪球 / 喷溅与滞留药水……）。</li>
     * </ul>
     *
     * <p>只回溯一层 {@code getShooter()}，因此骷髅射的箭、发射器射的箭、铁砧坠落
     * 这类非玩家来源同样不会触发出血条（TNT 与命中后爆炸见 {@link #attributeExplosion}）。</p>
     */
    private static Player playerAttacker(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }

    /**
     * 爆炸类伤害归因：事件没有直接玩家来源时（无 damager 的爆炸、或来源不是玩家/
     * 玩家投射物），按辅助证据找出责任玩家。
     *
     * <p>两类证据（只看 {@code BLOCK_EXPLOSION} / {@code ENTITY_EXPLOSION}）：</p>
     * <ol>
     *   <li><b>玩家投射物命中</b>：爆炸弓这类 RPG 武器在箭命中点调 {@code createExplosion}，
     *       产生的爆炸伤害事件<b>没有 damager</b> —— 按「同世界、1 秒内、受击点距命中点
     *       4 格内」反查 {@link #recentHits} 归因给射手（4 格 ≈ 原版爆炸的杀伤半径，
     *       爆炸中心就是命中点）；</li>
     *   <li><b>玩家点燃的 TNT</b>：TNT 引用的来源玩家还在（默认 80 tick 引信，够长），
     *       直接取 {@code getSource()}。</li>
     * </ol>
     *
     * <p>非爆炸原因（火焰、摔落、凋零效果……）与找不到证据的爆炸不归因，维持原行为。</p>
     */
    private Player attributeExplosion(EntityDamageEvent event) {
        EntityDamageEvent.DamageCause cause = event.getCause();
        boolean debug = plugin.config().debugExplosionAttribution();
        if (cause != EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
                && cause != EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) {
            if (debug) {
                plugin.getLogger().info("[爆炸归因] 跳过：非爆炸原因 cause=" + cause
                        + " victim=" + nameOf(event.getEntity()));
            }
            return null;
        }
        Player byHit = playerFromRecentHit(event.getEntity().getLocation(), debug);
        if (byHit != null) {
            return byHit;
        }
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            Player byTnt = tntSource(byEntity.getDamager());
            if (byTnt != null) {
                if (debug) {
                    plugin.getLogger().info("[爆炸归因] TNT 来源=" + byTnt.getName()
                            + " victim=" + nameOf(event.getEntity()));
                }
                return byTnt;
            }
        }
        if (debug) {
            plugin.getLogger().info("[爆炸归因] 失败：cause=" + cause
                    + " victim=" + nameOf(event.getEntity())
                    + " 近 " + HIT_ATTRIBUTION_WINDOW_TICKS + " tick 内命中记录 "
                    + recentHits.size() + " 条（阈值：同世界+距离≤4 格）");
        }
        return null;
    }

    /** 调试日志用：实体的简短描述。 */
    private static String nameOf(Entity entity) {
        return entity.getType().name() + "/" + entity.getUniqueId().toString().substring(0, 8);
    }

    /** 调试日志用：伤害来源的简短描述（区分 null 来源与实体类型）。 */
    private static String damagerName(Entity damager) {
        return damager == null ? "null" : nameOf(damager);
    }

    /**
     * 按「同世界、{@value #HIT_ATTRIBUTION_WINDOW_TICKS} tick 内、命中点
     * {@value #HIT_ATTRIBUTION_DISTANCE_SQ}^½ 格内」反查玩家投射物命中记录。
     * 顺便清掉过期记录；同窗口多条命中（连射）取最近的一条。
     */
    private Player playerFromRecentHit(Location explosionLocation, boolean debug) {
        long now = Bukkit.getCurrentTick();
        UUID worldId = explosionLocation.getWorld().getUID();
        HitRecord best = null;
        Iterator<HitRecord> it = recentHits.iterator();
        while (it.hasNext()) {
            HitRecord record = it.next();
            if (now - record.tick() > HIT_ATTRIBUTION_WINDOW_TICKS) {
                it.remove();
                continue;
            }
            if (!record.worldId().equals(worldId)) {
                continue;
            }
            if (!record.location().getWorld().getUID().equals(worldId)) {
                it.remove();
                continue;
            }
            double distanceSq = record.location().distanceSquared(explosionLocation);
            if (debug) {
                plugin.getLogger().info("[爆炸归因] 命中记录：shooter=" + Bukkit.getPlayer(record.shooterId())
                        + " tick 差=" + (now - record.tick())
                        + " 距离²=" + String.format(Locale.ROOT, "%.1f", distanceSq)
                        + "（阈值 " + HIT_ATTRIBUTION_DISTANCE_SQ + "）");
            }
            if (distanceSq > HIT_ATTRIBUTION_DISTANCE_SQ) {
                continue;
            }
            if (best == null || record.tick() >= best.tick()) {
                best = record;
            }
        }
        if (best == null) {
            return null;
        }
        Player shooter = Bukkit.getPlayer(best.shooterId());
        if (debug) {
            plugin.getLogger().info("[爆炸归因] 命中成功：shooter="
                    + (shooter == null ? "已离线" : shooter.getName()));
        }
        return shooter;
    }

    /** TNT 伤害：来源玩家还挂在实体引用上（引信 80 tick）时归因给他。 */
    private static Player tntSource(Entity damager) {
        return damager instanceof TNTPrimed tnt && tnt.getSource() instanceof Player player ? player : null;
    }

    /**
     * 玩家投射物命中时记一笔（只有<b>玩家</b>是射手才记）：
     * 本体命中（玩家近战直接走 damager=player，用不上）之后紧跟的爆炸由此归因。
     *
     * <p>记两次（LOWEST + MONITOR）并去重：RPG 插件常在<b>自己的</b> LOWEST/LOW 处理器里
     * 就同步调 {@code createExplosion}，爆炸伤害事件在本次命中事件还没走完时就发出了，
     * 只记 MONITOR 会来不及。LOWEST 那次保证「爆炸查记录时记录已存在」，
     * MONITOR 那次兜住 LOWEST 与 MONITOR 之间被取消的命中（去重靠 tick 判定）。</p>
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onProjectileHitEarly(ProjectileHitEvent event) {
        recordHit(event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        recordHit(event);
    }

    private void recordHit(ProjectileHitEvent event) {
        if (!(event.getEntity().getShooter() instanceof Player player)) {
            if (plugin.config().debugExplosionAttribution()) {
                plugin.getLogger().info("[爆炸归因] 投射物命中但射手不是玩家："
                        + event.getEntity().getType()
                        + " shooter=" + (event.getEntity().getShooter() == null ? "null" : "非玩家"));
            }
            return;
        }
        Location location = event.getEntity().getLocation();
        long now = Bukkit.getCurrentTick();
        UUID worldId = location.getWorld().getUID();
        // 同一 tick 内同一投射物的重复记录（LOWEST + MONITOR 各一次）只留一条
        for (int i = recentHits.size() - 1; i >= 0; i--) {
            HitRecord record = recentHits.get(i);
            if (now - record.tick() > HIT_ATTRIBUTION_WINDOW_TICKS) {
                recentHits.remove(i);
                continue;
            }
            if (record.projectileId().equals(event.getEntity().getUniqueId())
                    && record.tick() == now) {
                return;
            }
        }
        recentHits.add(new HitRecord(player.getUniqueId(), event.getEntity().getUniqueId(),
                worldId, now, location));
        if (plugin.config().debugExplosionAttribution()) {
            plugin.getLogger().info("[爆炸归因] 记录投射物命中：shooter=" + player.getName()
                    + " projectile=" + event.getEntity().getType()
                    + " 位置=" + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ());
        }
        // 命中的是生物且它身上已带毒/火（燃烧箭、药水箭命中上效果）：
        // 记录效果归属，供后续 FIRE_TICK / POISON 持续伤害归因（LOWEST/MONITOR 双跑，recordEffect 幂等可接受）
        if (event.getHitEntity() instanceof LivingEntity hitLiving) {
            recordEffectsOnHit(hitLiving, player);
        }
    }

    // ------------------------- 持续效果归因 -------------------------

    /**
     * 归因「生物身上的中毒/着火持续伤害」：按<b>这只生物</b>身上未过期的效果记录，
     * 找到最近一个施加效果的玩家。
     *
     * <p>毒/火是挂在具体生物身上的持续状态，掉血只认「这只生物 + 记录未过期」，
     * 不看位置（中了毒的生物跑出十格远，掉血还是记到施加者头上）。</p>
     */
    private Player attributeEffect(LivingEntity victim, EntityDamageEvent.DamageCause cause) {
        boolean debug = plugin.config().debugExplosionAttribution();
        long now = Bukkit.getCurrentTick();
        UUID victimId = victim.getUniqueId();
        UUID worldId = victim.getWorld().getUID();
        Player matched = null;
        EffectRecord best = null;
        Iterator<EffectRecord> it = recentEffects.iterator();
        while (it.hasNext()) {
            EffectRecord record = it.next();
            if (record.expiryTick() <= now) {
                it.remove();
                continue;
            }
            if (!record.victimId().equals(victimId) || !record.worldId().equals(worldId)) {
                continue;
            }
            if (best == null || record.tick() >= best.tick()) {
                best = record;
                matched = Bukkit.getPlayer(record.applierId());
            }
        }
        if (debug) {
            plugin.getLogger().info("[效果归因] " + cause + " 归因" + (matched == null ? "失败" : "成功：applier=" + matched.getName())
                    + " victim=" + nameOf(victim) + " 有效记录 " + recentEffects.size() + " 条");
        }
        return matched;
    }

    /**
     * 通用入口：把「玩家给生物挂上了中毒/着火」记进效果表。
     *
     * @param victim       被挂上效果的生物
     * @param applier      施加效果的玩家
     * @param effectTypeId 效果标记（POISON / FIRE，仅用于日志）
     */
    private void recordEffect(LivingEntity victim, Player applier, String effectTypeId) {
        long now = Bukkit.getCurrentTick();
        recentEffects.add(new EffectRecord(applier.getUniqueId(), victim.getUniqueId(),
                victim.getWorld().getUID(), now, now + EFFECT_ATTRIBUTION_TICKS));
        if (plugin.config().debugExplosionAttribution()) {
            plugin.getLogger().info("[效果归因] 记录 " + effectTypeId + "：applier=" + applier.getName()
                    + " victim=" + nameOf(victim)
                    + " 有效期 " + EFFECT_ATTRIBUTION_TICKS + " tick");
        }
    }

    /**
     * 投射物命中并<b>真的打中生物</b>时检查其身上的毒/火效果：
     * 燃烧箭（命中时目标已着火）、药水箭（命中上毒）这类「效果挂在目标身上」的
     * RPG 武器，后续 {@code FIRE_TICK} / {@code POISON} 持续伤害由此归因给射手。
     *
     * <p>挂在 {@link #recordHit} 的玩家分支里做：射手不是玩家的命中没有归因价值。</p>
     */
    private void recordEffectsOnHit(LivingEntity victim, Player shooter) {
        if (victim.getFireTicks() > 0) {
            recordEffect(victim, shooter, "FIRE");
        }
        if (victim.hasPotionEffect(PotionEffectType.POISON)) {
            recordEffect(victim, shooter, "POISON");
        }
    }

    /**
     * 玩家<b>直接点燃</b>生物（燃烧箭命中引燃、火焰附加近战、打火石）：
     * {@link EntityCombustByEntityEvent} 只在「实体 A 点燃实体 B」时发出，
     * 来源是燃烧箭（射手=玩家）时记录，供后续 {@code FIRE_TICK} 归因。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCombustByEntity(EntityCombustByEntityEvent event) {
        Player shooter = playerAttacker(event.getCombuster());
        if (shooter == null || !(event.getEntity() instanceof LivingEntity victim)) {
            return;
        }
        recordEffect(victim, shooter, "FIRE");
    }

    /**
     * 滞留药水/毒云碰到生物：云的来源是玩家投射物（药水箭滞留版、玩家扔的滞留药水）
     * 时，给被 Cloud 覆盖到的生物记录效果，供后续 {@code POISON} 归因。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAreaEffectCloudApply(AreaEffectCloudApplyEvent event) {
        if (!(event.getEntity() instanceof AreaEffectCloud cloud)) {
            return;
        }
        if (!(cloud.getSource() instanceof Player applier)) {
            return;
        }
        boolean poison = cloud.getBasePotionType() != null
                && cloud.getBasePotionType().getPotionEffects().stream()
                        .anyMatch(effect -> effect.getType().equals(PotionEffectType.POISON));
        if (!poison) {
            return;
        }
        for (LivingEntity victim : event.getAffectedEntities()) {
            recordEffect(victim, applier, "POISON");
        }
    }

    /** 死亡时：该生物身上<b>所有攻击者</b>的血条都切到 dead 模板、进度归零、按更短的死亡时长重新计时。 */
    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof LivingEntity living)) {
            return;
        }
        UUID entityId = living.getUniqueId();
        PluginConfig config = plugin.config();
        long expiry = Bukkit.getCurrentTick() + lifetimeTicks(config, true);
        String maxText = formatHealth(maxHealth(living));
        for (Map.Entry<BarKey, BarState> entry : bars.entrySet()) {
            if (!entry.getKey().entityId().equals(entityId)) {
                continue;
            }
            BarState state = entry.getValue();
            state.expiryTick = expiry;
            state.dead = true;
            state.shownHealth = "0";
            state.shownMax = maxText;
            state.bar.name(buildTitle(living, "0", maxText, formatHealth(state.lastDamage), true, config));
            state.bar.progress(0.0f);
        }
    }

    /**
     * 这条血条该停留多久（tick）：死亡走 {@code bar.dead-time}，存活走 {@code bar.time}。
     *
     * <p>「最后一击把血打空」的伤害事件也会走到这里（{@link #onDamage} 里 {@code dead = true}），
     * 所以濒死/击杀那一帧就已经按短时长计时，随后到达的 {@link EntityDeathEvent} 会再刷新一次，
     * 两者结果一致。</p>
     */
    private static int lifetimeTicks(PluginConfig config, boolean dead) {
        return dead ? config.healthBarDeadTimeTicks() : config.healthBarTimeTicks();
    }

    /**
     * 玩家离线：删掉属于他的所有血条。
     *
     * <p>血条本来就只发给他一个人，因此不需要（也不应该）动别人的血条。
     * 上线时也无需补发：他刚上线，自然没有任何「自己打出来的」血条。</p>
     */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        Iterator<Map.Entry<BarKey, BarState>> it = bars.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BarKey, BarState> entry = it.next();
            if (entry.getKey().playerId().equals(id)) {
                event.getPlayer().hideBossBar(entry.getValue().bar);
                it.remove();
            }
        }
        recentHits.removeIf(record -> record.shooterId().equals(id));
        recentEffects.removeIf(record -> record.applierId().equals(id));
    }

    // ------------------------- 玩家级开关 -------------------------

    /**
     * 该玩家当前是否应该看到血条：服务端总闸 + 权限 + 个人开关三者同时满足。
     *
     * <p>服务端总闸 {@code bar.enabled} 关闭时任何人都看不到（也不会新建血条）；
     * 权限 {@code infohud.healthbar} 被收回时该玩家永远看不到；
     * 个人开关未设置时跟随 {@code bar.enabled-by-default}。</p>
     */
    private boolean seesHealthBars(Player player) {
        PluginConfig config = plugin.config();
        return config.healthBarEnabled()
                && player.hasPermission(Permissions.HEALTH_BAR)
                && plugin.preferences().healthBar(player.getUniqueId(), config.healthBarEnabledByDefault());
    }

    /**
     * 玩家切换了自己的血条开关后立即生效：开启则补上自己现有的血条，
     * 关闭则立刻把自己的血条摘掉，不等下一次受击。
     *
     * <p>只动<b>这个玩家自己</b>的血条 —— 他的开关不该影响别人看到的内容。</p>
     */
    public void refreshFor(Player player) {
        boolean visible = seesHealthBars(player);
        UUID id = player.getUniqueId();
        for (Map.Entry<BarKey, BarState> entry : bars.entrySet()) {
            if (!entry.getKey().playerId().equals(id)) {
                continue;
            }
            if (visible) {
                player.showBossBar(entry.getValue().bar);
            } else {
                player.hideBossBar(entry.getValue().bar);
            }
        }
    }

    // ------------------------- 生命周期 -------------------------

    /**
     * 扫掉已到期的血条（由 {@link HealthBarSweepTask} 每 5 tick 调一次），
     * 顺便把<b>还没到期</b>的血条同步到生物当前血量。
     * 空表时只是走一遍空迭代器，开销可忽略。
     *
     * @return 清扫后剩余的血条数
     */
    int sweepExpired() {
        long now = Bukkit.getCurrentTick();
        Iterator<Map.Entry<BarKey, BarState>> it = bars.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BarKey, BarState> entry = it.next();
            BarState state = entry.getValue();
            if (state.expiryTick <= now) {
                hideFrom(state);
                it.remove();
                continue;
            }
            syncHealth(entry.getKey(), state);
        }
        return bars.size();
    }

    /**
     * 把血条同步到生物的<b>实时血量</b>：悦灵、狼、猫、马这类生物会自然回血，
     * 药水、生命偷取、其它插件 {@code setHealth} 也会改血量 —— 只靠受击事件更新的话，
     * 标题会一直停在“挨打那一刻”的旧数值直到血条消失。
     *
     * <p>只在<b>存活</b>且血量/上限<b>真的变了</b>时才重发标题包：
     * 本方法每 5 tick 跑一次，不做比较的话一条静止的血条每秒要白发 4 次。
     * 死亡样式是终局信息，不再跟随（生物已经死了，{@code getHealth()} 恒为 0）。</p>
     */
    private void syncHealth(BarKey key, BarState state) {
        if (state.dead) {
            return;
        }
        Entity entity = Bukkit.getEntity(key.entityId());
        if (!(entity instanceof LivingEntity living) || !living.isValid() || living.isDead()) {
            return;
        }
        double health = Math.max(0.0, living.getHealth());
        String healthText = formatHealth(health);
        String maxText = formatHealth(maxHealth(living));
        if (healthText.equals(state.shownHealth) && maxText.equals(state.shownMax)) {
            return;
        }
        state.shownHealth = healthText;
        state.shownMax = maxText;
        PluginConfig config = plugin.config();
        state.bar.name(buildTitle(living, healthText, maxText, formatHealth(state.lastDamage), false, config));
        state.bar.progress((float) progressOf(health, living));
    }

    /** 配置重载 / 插件停用：摘掉全部血条（之后按新配置重建）。 */
    public void closeAll() {
        for (BarState state : bars.values()) {
            hideFrom(state);
        }
        bars.clear();
    }

    /** 当前挂着的血条数（status 展示用；跨全部玩家合计）。 */
    public int size() {
        return bars.size();
    }

    // ------------------------- 内部 -------------------------

    /**
     * 为「这名攻击者 + 这只生物」建一条血条，并且<b>只发包给他一个人</b>。
     */
    private BarState createBar(PluginConfig config, Player attacker, BarKey key,
                               Component title, float progress) {
        BossBar bar = BossBar.bossBar(title, progress,
                toAdventure(config.healthBarColor()), toAdventure(config.healthBarOverlay()));
        attacker.showBossBar(bar);
        BarState state = new BarState(bar, attacker.getUniqueId());
        bars.put(key, state);
        return state;
    }

    /** 让血条的主人不再看到它（Adventure 侧没有 removeAll，必须精确 hide 给持有者）。 */
    private static void hideFrom(BarState state) {
        Player owner = Bukkit.getPlayer(state.playerId);
        if (owner != null) {
            owner.hideBossBar(state.bar);
        }
    }

    /** Bukkit 颜色 → Adventure 颜色：同名枚举，跨包按名字映射（包内可见，供单测）。 */
    static BossBar.Color toAdventure(BarColor color) {
        return BossBar.Color.valueOf(color.name());
    }

    /** Bukkit 样式 → Adventure 样式：两套命名不同（SOLID↔PROGRESS、SEGMENTED_6↔NOTCHED_6）。 */
    static BossBar.Overlay toAdventure(BarStyle style) {
        return switch (style) {
            case SOLID -> BossBar.Overlay.PROGRESS;
            case SEGMENTED_6 -> BossBar.Overlay.NOTCHED_6;
            case SEGMENTED_10 -> BossBar.Overlay.NOTCHED_10;
            case SEGMENTED_12 -> BossBar.Overlay.NOTCHED_12;
            case SEGMENTED_20 -> BossBar.Overlay.NOTCHED_20;
        };
    }

    /** 按实体当前状态 + 配置模板构造血条标题组件（血量文本由调用方算好传进来）。 */
    private Component buildTitle(LivingEntity living, String health, String max, String damage, boolean dead,
                                 PluginConfig config) {
        String template = dead ? config.healthBarDeadTitle() : config.healthBarLivingTitle();
        Component entityName = entityName(living);
        return titleComponent(template, config, entityName, displayName(living), health, max, damage);
    }

    /**
     * 把模板里的占位符替换掉，返回完整标题组件（包内可见，供纯逻辑单测）。
     *
     * <p>整条模板一次交给 {@link MiniMessage} 解析（非严格模式，未知标签丢弃），
     * 五个占位符注册成<b>自定义标签</b>：颜色/MiniMessage 状态在占位符之间连续不断
     * （比如 {@code <gray>} 会覆盖 {@code <health>/<max>} 整段），这是逐段切分再拼接
     * 做不到的。</p>
     *
     * <p>{@code &} 颜色代码在开启 {@code hud.ampersand-colors} 时先原地展开成
     * MiniMessage 标签（与 HUD 的混写支持同一套语义），因此血条标题同时支持
     * {@code <gray>} 与 {@code &7} 两种写法。</p>
     *
     * <p>{@code §} 代码<b>始终</b>展开（{@link TextUtil#expandLegacy} 对 {@code §} 不受
     * {@code ampersand-colors} 影响）。这一步不能省：标题现在是直接下发的组件，
     * 不像旧实现那样先序列化成 {@code §} 串再由服务端重新解析，留下的裸 {@code §}
     * 会被客户端当普通字符显示出来。</p>
     *
     * @param entityName {@code <entity>} 的值（translatable 组件，客户端按自己语言渲染）
     * @param customName {@code <name>} 的值，可为 null（此时回退 entityName）
     */
    static Component titleComponent(String template, PluginConfig config, Component entityName,
                                    Component customName, String health, String max, String damage) {
        if (template == null || template.isEmpty()) {
            return Component.empty();
        }
        String input = TextUtil.expandLegacy(template, config.ampersandColors());
        Component nameValue = customName != null ? customName : entityName;
        // selfClosingInserting 在 4.26 里对 <tag> 裸写法和 <tag/> 都能处理（实测）
        return TITLE_MINI.deserialize(input,
                TagResolver.resolver("entity", Tag.selfClosingInserting(entityName)),
                TagResolver.resolver("name", Tag.selfClosingInserting(nameValue)),
                TagResolver.resolver("health", Tag.selfClosingInserting(Component.text(health))),
                TagResolver.resolver("max", Tag.selfClosingInserting(Component.text(max))),
                TagResolver.resolver("damage", Tag.selfClosingInserting(Component.text(damage))));
    }

    /** 黑名单命中判定：配置两侧已大写归一化，实体侧转大写后查表。 */
    private static boolean isBlacklisted(PluginConfig config, LivingEntity entity) {
        Set<String> blacklist = config.healthBarBlacklist();
        if (blacklist.isEmpty()) {
            return false;
        }
        return blacklist.contains(entity.getType().name().toUpperCase(Locale.ROOT));
    }

    /**
     * 实体类型名（可翻译组件）：客户端按自己语言渲染
     * （{@code entity.minecraft.zombie} → 客户端语言里的“僵尸 / Zombie”）。
     *
     * <p>{@code translationKey()} 对 {@code EntityType.UNKNOWN}（自定义/模组实体）会抛异常，
     * 这里退回大写的类型名，避免事件回调里抛异常刷控制台。</p>
     */
    private static Component entityName(LivingEntity entity) {
        try {
            return Component.translatable(entity.getType().translationKey());
        } catch (RuntimeException ex) {
            return Component.text(entity.getType().name());
        }
    }

    /** 自定义名；玩家没有命名牌时回退显示名（游戏 ID / 昵称插件的前后缀），其它生物回退 null。 */
    private static Component displayName(LivingEntity entity) {
        Component name = customName(entity);
        if (name == null && entity instanceof Player player) {
            name = player.displayName();
        }
        return name;
    }

    /** 自定义名；没有（或空名）返回 null，由 {@code <name>} 回退 {@code <entity>}。 */
    private static Component customName(LivingEntity entity) {
        Component name = entity.customName();
        if (name == null || PlainTextComponentSerializer.plainText().serialize(name).isEmpty()) {
            return null;
        }
        return name;
    }

    /** 最大生命值：优先取属性实例（1.21.4 起难度修正直接改属性），取不到回退基础值。 */
    private static double maxHealth(LivingEntity entity) {
        AttributeInstance instance = entity.getAttribute(Attribute.MAX_HEALTH);
        if (instance != null && instance.getValue() > 0) {
            return instance.getValue();
        }
        return entity.getMaxHealth();
    }

    private static double progressOf(double health, LivingEntity entity) {
        double max = maxHealth(entity);
        if (max <= 0) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, health / max));
    }

    /** 血量/伤害展示：整数不带小数（20.0 → "20"），小数保留 1 位（19.5 → "19.5"）。 */
    static String formatHealth(double value) {
        if (value <= 0) {
            return "0";
        }
        long rounded = Math.round(value);
        if (Math.abs(value - rounded) < 0.05) {
            return Long.toString(rounded);
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
