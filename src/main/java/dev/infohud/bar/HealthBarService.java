package dev.infohud.bar;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import dev.infohud.InfoHUD;
import dev.infohud.config.PluginConfig;
import dev.infohud.text.TextUtil;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * 受击生物血条：**只有玩家攻击生物**（近战或玩家射出的投射物）时才为它生成一条
 * <b>独立</b>的 BossBar 显示给全部在线玩家，停留 {@code bar.time} 个 tick
 * （默认 100 = 5 秒）后自动消失。
 *
 * <ul>
 *   <li>再次受击：刷新标题/进度并重置倒计时；</li>
 *   <li>死亡（{@link EntityDeathEvent} 或最后一次伤害把血打空）：切换到 {@code bar.title.dead}
 *       模板，进度归零，同样刷新倒计时；</li>
 *   <li>黑名单（{@code blacklist}，默认 ENDER_DRAGON / WITHER / PLAYER / ARMOR_STAND）内的
 *       实体类型不显示血条；</li>
 *   <li>总开关 {@code bar.enabled} 关闭时不生成新血条，{@code reload} 会摘掉已显示的血条。</li>
 * </ul>
 *
 * <p>标题占位符（模板里原样写尖括号形式）：</p>
 * <ul>
 *   <li>{@code <entity>} —— 实体原版翻译名（用 translatable 组件下发，客户端按自己语言渲染）；</li>
 *   <li>{@code <name>} —— 实体自定义名；没有名字时显示 {@code <entity>}；</li>
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

    /** 单实体血条状态：BossBar 本体 + 到点消失的绝对 tick + 本次受到的伤害。 */
    private static final class BarState {
        final BossBar bar;
        long expiryTick;
        double lastDamage;

        BarState(BossBar bar) {
            this.bar = bar;
        }
    }

    private final Map<UUID, BarState> bars = new ConcurrentHashMap<>();
    private final InfoHUD plugin;

    public HealthBarService(InfoHUD plugin) {
        this.plugin = plugin;
    }

    // ------------------------- 事件 -------------------------

    /**
     * <b>只有玩家造成的伤害</b>才触发：玩家直接攻击，或玩家射出的投射物（箭、弩箭、
     * 喷溅药水……）。自然掉血（摔落、火焰、溺水、窒息、虚空）与非玩家来源
     * （怪物互殴、骷髅射箭、铁砧坠落、TNT）都不出血条。
     *
     * <p>{@code MONITOR + ignoreCancelled}：被其它插件取消的伤害不改血量，直接跳过。</p>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        PluginConfig config = plugin.config();
        if (!config.healthBarEnabled()) {
            return;
        }
        if (!(event.getEntity() instanceof LivingEntity living)) {
            return;
        }
        if (!isPlayerCaused(event.getDamager())) {
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
        Component title = buildTitle(living, healthAfter, damage, dead, config);
        float progress = (float) (dead ? 0.0 : progressOf(healthAfter, living));

        BarState state = bars.get(living.getUniqueId());
        if (state == null) {
            // 首帧就带上真实标题/进度：避免先给玩家一条空标题血条再补发更新包
            state = createBar(config, living, title, progress);
        } else {
            state.bar.name(title);
            state.bar.progress(progress);
        }
        state.lastDamage = damage;
        state.expiryTick = Bukkit.getCurrentTick() + config.healthBarTimeTicks();
    }

    /**
     * 伤害来源是否算「玩家攻击」：
     * <ul>
     *   <li>玩家本人（近战、末影珍珠瞬移判定等直接伤害）；</li>
     *   <li>玩家射出的投射物（箭 / 弩箭 / 雪球 / 喷溅与滞留药水……）。</li>
     * </ul>
     *
     * <p>只回溯一层 {@code getShooter()}，因此骷髅射的箭、发射器射的箭、TNT、
     * 铁砧坠落这类非玩家来源同样不会触发出血条。</p>
     */
    private static boolean isPlayerCaused(Entity damager) {
        if (damager instanceof Player) {
            return true;
        }
        return damager instanceof Projectile projectile && projectile.getShooter() instanceof Player;
    }

    /** 死亡时若血条还在：切到 dead 模板、进度归零、刷新倒计时。 */
    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof LivingEntity living)) {
            return;
        }
        BarState state = bars.get(living.getUniqueId());
        if (state == null) {
            return;
        }
        PluginConfig config = plugin.config();
        state.expiryTick = Bukkit.getCurrentTick() + config.healthBarTimeTicks();
        state.bar.name(buildTitle(living, 0.0, state.lastDamage, true, config));
        state.bar.progress(0.0f);
    }

    /** 新玩家上线：把当时还活着的所有血条加给他。 */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        for (BarState state : bars.values()) {
            event.getPlayer().showBossBar(state.bar);
        }
    }

    /** 玩家离线：从所有血条上摘掉，避免 BossBar 包继续发给已离线的玩家。 */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        for (BarState state : bars.values()) {
            event.getPlayer().hideBossBar(state.bar);
        }
    }

    // ------------------------- 生命周期 -------------------------

    /**
     * 扫掉已到期的血条（由 {@link HealthBarSweepTask} 每 20 tick 调一次）。
     * 空表时只是走一遍空迭代器，开销可忽略。
     *
     * @return 清扫后剩余的血条数
     */
    int sweepExpired() {
        long now = Bukkit.getCurrentTick();
        Iterator<Map.Entry<UUID, BarState>> it = bars.entrySet().iterator();
        while (it.hasNext()) {
            BarState state = it.next().getValue();
            if (state.expiryTick <= now) {
                hideFromAll(state.bar);
                it.remove();
            }
        }
        return bars.size();
    }

    /** 配置重载 / 插件停用：摘掉全部血条（之后按新配置重建）。 */
    public void closeAll() {
        for (BarState state : bars.values()) {
            hideFromAll(state.bar);
        }
        bars.clear();
    }

    /** 当前挂着的血条数（status 展示用）。 */
    public int size() {
        return bars.size();
    }

    // ------------------------- 内部 -------------------------

    private BarState createBar(PluginConfig config, LivingEntity entity, Component title, float progress) {
        BossBar bar = BossBar.bossBar(title, progress,
                toAdventure(config.healthBarColor()), toAdventure(config.healthBarOverlay()));
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.showBossBar(bar);
        }
        BarState state = new BarState(bar);
        bars.put(entity.getUniqueId(), state);
        return state;
    }

    /** 让所有在线玩家都不再看到这条血条（Adventure 侧没有 removeAll，逐个 hide）。 */
    private static void hideFromAll(BossBar bar) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.hideBossBar(bar);
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

    /** 按实体当前状态 + 配置模板构造血条标题组件。 */
    private Component buildTitle(LivingEntity living, double healthAfter, double damage, boolean dead,
                                 PluginConfig config) {
        String template = dead ? config.healthBarDeadTitle() : config.healthBarLivingTitle();
        Component entityName = entityName(living);
        return titleComponent(template, config, entityName, customName(living),
                formatHealth(dead ? 0.0 : healthAfter), formatHealth(maxHealth(living)), formatHealth(damage));
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
