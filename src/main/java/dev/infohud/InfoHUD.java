package dev.infohud;

import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import dev.infohud.biome.BiomeService;
import dev.infohud.command.InfoHUDCommand;
import dev.infohud.config.PluginConfig;
import dev.infohud.config.PreferenceStore;
import dev.infohud.hud.HudRenderer;
import dev.infohud.hud.HudTask;
import dev.infohud.listener.PlayerConnectionListener;
import dev.infohud.listener.PlayerMoveListener;
import dev.infohud.placeholder.InfoHUDExpansion;

/**
 * InfoHUD 主类。
 *
 * <p>轻量级实时信息 HUD：在 ActionBar 上持续显示游戏时间、现实时间、昼夜时段与当前生物群系，
 * 并在玩家进入新群系时发一条聊天栏提示。纯服务端插件，客户端无需安装任何东西。</p>
 *
 * <p>运行结构：</p>
 * <pre>
 *   InfoHUD (JavaPlugin)
 *     ├─ PluginConfig          不可变配置快照，reload 时整体替换（volatile）
 *     ├─ PreferenceStore       UUID → 是否开启 HUD（ConcurrentHashMap + 防抖落盘）
 *     ├─ BiomeService          UUID → 上次群系缓存 + 切换提示判定
 *     ├─ HudTask (BukkitRunnable)  主线程定时刷新 ActionBar
 *     │    └─ HudRenderer      占位符替换 + 组件复用缓存
 *     ├─ PlayerMoveListener    仅跨方块时触发群系复检
 *     ├─ PlayerConnectionListener 上线建基线 / 离线清理 / 换世界重建
 *     └─ InfoHUDExpansion      PlaceholderAPI 可选集成
 * </pre>
 */
public final class InfoHUD extends JavaPlugin {

    /** 当前配置快照。reload 时替换引用；读方不加锁。 */
    private volatile PluginConfig config;

    private PreferenceStore preferences;
    private BiomeService biomes;
    private HudRenderer renderer;

    /** HUD 刷新任务句柄。 */
    private BukkitTask hudTask;

    /** 是否可用 PlaceholderAPI（软依赖）。 */
    private volatile boolean placeholderApiReady;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.config = PluginConfig.load(getConfig(), getLogger());
        this.preferences = new PreferenceStore(this);
        this.biomes = new BiomeService();
        this.renderer = new HudRenderer(this);

        try {
            this.preferences.load();
        } catch (RuntimeException ex) {
            getLogger().log(Level.WARNING, "读取玩家偏好失败，本次启动使用默认值", ex);
        }

        PluginManager pluginManager = getServer().getPluginManager();
        pluginManager.registerEvents(new PlayerMoveListener(this), this);
        pluginManager.registerEvents(new PlayerConnectionListener(this), this);
        pluginManager.registerEvents(this.preferences, this);

        PluginCommand command = getCommand("infohud");
        if (command == null) {
            // plugin.yml 没写对（例如打包时漏了 resources），直接拒绝启动比静默无命令好
            getLogger().severe("找不到 infohud 命令定义，请检查 plugin.yml 是否完整打包。");
        } else {
            InfoHUDCommand handler = new InfoHUDCommand(this);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }

        hookPlaceholderApi(pluginManager);

        // 为已经在线的玩家（热重载 / 插件管理工具后加载）建立群系基线，不发提示
        this.biomes.prime(Bukkit.getOnlinePlayers(), this.config);

        startHudTask();

        getLogger().info("InfoHUD v" + pluginVersion() + " 已启用："
                + "刷新 " + config.updateIntervalTicks() + " tick"
                + "，时区 " + config.timezone().getId()
                + "，群系映射 " + config.biomeNameCount() + " 条"
                + (placeholderApiReady ? "，已对接 PlaceholderAPI" : ""));
    }

    @Override
    public void onDisable() {
        stopHudTask();
        if (preferences != null) {
            preferences.shutdown();
        }
        if (biomes != null) {
            biomes.forgetAll();
        }
    }

    /**
     * 重载配置：重新解析 config.yml、重读偏好、按新的刷新间隔重启任务。
     *
     * <p>全程主线程，且只做纯内存操作 + 一次 YAML 解析，耗时通常 &lt; 1ms。</p>
     */
    public void reloadPlugin() {
        reloadConfig();
        this.config = PluginConfig.load(getConfig(), getLogger());
        this.preferences.load();
        this.biomes.resetCooldown();
        this.biomes.prime(Bukkit.getOnlinePlayers(), this.config);
        restartHudTask();
    }

    /** 检测并注册 PlaceholderAPI 扩展。 */
    private void hookPlaceholderApi(PluginManager pluginManager) {
        if (pluginManager.getPlugin("PlaceholderAPI") == null) {
            this.placeholderApiReady = false;
            return;
        }
        try {
            InfoHUDExpansion expansion = new InfoHUDExpansion(this);
            this.placeholderApiReady = expansion.register();
            if (!placeholderApiReady) {
                getLogger().warning("PlaceholderAPI 扩展注册失败（可能已有同名 infohud 扩展）。");
            }
        } catch (NoSuchMethodError | NoClassDefFoundError | RuntimeException ex) {
            // PAPI 版本过老或结构变化：关掉集成，HUD 本体照常工作
            this.placeholderApiReady = false;
            getLogger().log(Level.WARNING, "PlaceholderAPI 集成不可用，已跳过（不影响 HUD 功能）", ex);
        }
    }

    /** 启动 HUD 刷新任务。 */
    private void startHudTask() {
        int interval = config.updateIntervalTicks();
        // 首轮延迟一个间隔，避免玩家还没进服就空转
        this.hudTask = new HudTask(this).runTaskTimer(this, interval, interval);
    }

    /** 重启用 HUD 刷新任务（reload 后应用新的间隔）。 */
    private void restartHudTask() {
        stopHudTask();
        startHudTask();
    }

    private void stopHudTask() {
        if (hudTask != null) {
            hudTask.cancel();
            hudTask = null;
        }
    }

    // --------------------------- getters ---------------------------

    /** 当前配置快照（只读）。 */
    public PluginConfig config() {
        return config;
    }

    public PreferenceStore preferences() {
        return preferences;
    }

    public BiomeService biomes() {
        return biomes;
    }

    public HudRenderer renderer() {
        return renderer;
    }

    /** PlaceholderAPI 是否可用（决定 {@code PapiBridge} 能否被加载）。 */
    public boolean placeholderApiReady() {
        return placeholderApiReady;
    }

    /** 插件版本（来自 plugin.yml，打包时由 Gradle 过滤填入）。 */
    public String pluginVersion() {
        return getPluginMeta().getVersion();
    }
}
