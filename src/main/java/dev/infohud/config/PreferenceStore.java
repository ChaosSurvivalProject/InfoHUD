package dev.infohud.config;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import dev.infohud.InfoHUD;

/**
 * 玩家 HUD 开关偏好的存储层。
 *
 * <p>内存态是需求里要求的 {@code ConcurrentHashMap<UUID, Boolean>}（key=玩家，value=是否开启）。
 * 落盘有两种后端，由 {@code storage.preferences-in-config} 切换：</p>
 * <ul>
 *   <li>{@code false}（默认）——写 {@code plugins/InfoHUD/data.yml}，用 {@link YamlConfiguration}
 *       在<b>异步线程</b>落盘，config.yml 的注释不会被破坏；</li>
 *   <li>{@code true} ——按“用 {@code Plugin#getConfig()} 持久化”的字面要求写回 config.yml 的
 *       {@code player-preferences} 节点。注意 Bukkit 的 {@code saveConfig()} 会重写整个
 *       config.yml 并丢掉全部注释，所以这不是默认方案。</li>
 * </ul>
 *
 * <p>写盘做了防抖：多次 toggle 只会在最后一个防抖窗口结束后写一次。</p>
 */
public final class PreferenceStore implements Listener {

    /** 内存态：玩家 → 是否开启 HUD（不含未设置过偏好的玩家）。 */
    private final Map<UUID, Boolean> preferences = new ConcurrentHashMap<>();

    private final InfoHUD plugin;
    private final Object ioLock = new Object();

    /** 防抖窗口内挂起的写盘任务。 */
    private BukkitTask pendingWrite;

    /** 是否有尚未落盘的变更。 */
    private volatile boolean dirty;

    public PreferenceStore(InfoHUD plugin) {
        this.plugin = plugin;
    }

    /** data.yml 路径。 */
    private File dataFile() {
        return new File(plugin.getDataFolder(), "data.yml");
    }

    /**
     * 从磁盘载入偏好。调用前 config 必须已经解析好。
     */
    public void load() {
        preferences.clear();
        Map<String, ?> raw;
        if (plugin.config().preferencesInConfig()) {
            ConfigurationSection section = plugin.getConfig().getConfigurationSection("player-preferences");
            raw = section == null ? Map.of() : section.getValues(false);
        } else {
            File file = dataFile();
            if (!file.isFile()) {
                return;
            }
            raw = YamlConfiguration.loadConfiguration(file).getValues(false);
        }
        for (Map.Entry<String, ?> entry : raw.entrySet()) {
            if (entry.getValue() instanceof Boolean value) {
                try {
                    preferences.put(UUID.fromString(entry.getKey()), value);
                } catch (IllegalArgumentException ignored) {
                    // 手工改坏的 key，跳过
                }
            }
        }
    }

    /**
     * 玩家当前是否应该看到 HUD。
     *
     * @param playerId 玩家
     * @param fallback 没有个人偏好时的默认值（{@code hud.enabled-by-default}）
     */
    public boolean enabled(UUID playerId, boolean fallback) {
        Boolean value = preferences.get(playerId);
        return value == null ? fallback : value;
    }

    /** 玩家是否显式设置过偏好（{@code /infohud status} 用来区分“默认开”和“我开的”）。 */
    public boolean isSet(UUID playerId) {
        return preferences.containsKey(playerId);
    }

    /**
     * 设置并安排落盘。必须在主线程调用（只改内存 + 提交调度任务，没有磁盘 IO）。
     */
    public void set(UUID playerId, boolean value) {
        Boolean old = preferences.put(playerId, value);
        if (old == null || old != value) {
            dirty = true;
            scheduleWrite();
        }
    }

    /** 反转当前状态并返回新值。 */
    public boolean toggle(UUID playerId, boolean fallback) {
        boolean next = !enabled(playerId, fallback);
        set(playerId, next);
        return next;
    }

    /**
     * 玩家离线时确保变更落盘。
     *
     * <p>注意：内存表里<b>不</b>删除该玩家，否则重启后个人开关就丢了（需求要求持久化偏好）。
     * 每名玩家只占一个 {@code UUID -> Boolean} 条目，长期跑服也不会失控。</p>
     */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (dirty) {
            scheduleWrite();
        }
    }

    /**
     * 立即写盘。
     *
     * @param synchronous true 用于 {@code onDisable}（调度器已不可用，必须同步写）
     */
    public void write(boolean synchronous) {
        if (!dirty) {
            return;
        }
        PluginConfig config = plugin.config();
        Map<UUID, Boolean> snapshot = new HashMap<>(preferences);
        if (config.preferencesInConfig()) {
            synchronized (ioLock) {
                FileConfiguration fileConfiguration = plugin.getConfig();
                fileConfiguration.set("player-preferences", null);
                for (Map.Entry<UUID, Boolean> entry : snapshot.entrySet()) {
                    fileConfiguration.set("player-preferences." + entry.getKey(), entry.getValue());
                }
                plugin.saveConfig();
                dirty = false;
            }
            return;
        }

        YamlConfiguration out = new YamlConfiguration();
        for (Map.Entry<UUID, Boolean> entry : snapshot.entrySet()) {
            out.set(entry.getKey().toString(), entry.getValue());
        }

        if (synchronous) {
            saveYaml(out);
            dirty = false;
            return;
        }
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                synchronized (ioLock) {
                    saveYaml(out);
                }
            });
            dirty = false;
        } catch (IllegalStateException ex) {
            // 插件正在停用，退回同步写
            saveYaml(out);
            dirty = false;
        }
    }

    private void saveYaml(YamlConfiguration configuration) {
        try {
            File file = dataFile();
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                plugin.getLogger().warning("无法创建插件数据目录：" + parent.getAbsolutePath());
                return;
            }
            configuration.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("写入 data.yml 失败：" + ex.getMessage());
        }
    }

    /** 安排一次防抖写盘（主线程）。 */
    private void scheduleWrite() {
        if (pendingWrite != null && !pendingWrite.isCancelled()) {
            return;
        }
        long delayTicks = Math.max(1L, plugin.config().saveDebounceMillis() / 50L);
        try {
            pendingWrite = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                pendingWrite = null;
                write(false);
            }, delayTicks);
        } catch (IllegalStateException ex) {
            write(true);
        }
    }

    /** 关机 / 停用时调用：取消挂起任务并同步落盘。 */
    public void shutdown() {
        if (pendingWrite != null) {
            pendingWrite.cancel();
            pendingWrite = null;
        }
        write(true);
    }

    /** 当前内存中的偏好条数（{@code /infohud status} 展示用）。 */
    public int size() {
        return preferences.size();
    }
}
