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
 * 玩家 HUD 开关偏好的存储层：底部信息栏（ActionBar）与顶部罗盘（BossBar）<b>各自独立</b>。
 *
 * <p>每名玩家两条开关，取值三态：</p>
 * <ul>
 *   <li>{@code null}（未设置）——跟随 {@code config.yml} 的默认值；</li>
 *   <li>{@code true} / {@code false}——玩家通过 {@code /infohud toggle} 显式设置过。</li>
 * </ul>
 *
 * <p>落盘有两种后端，由 {@code storage.preferences-in-config} 切换：</p>
 * <ul>
 *   <li>{@code false}（默认）——写 {@code plugins/InfoHUD/data.yml}，用 {@link YamlConfiguration}
 *       在<b>异步线程</b>落盘，config.yml 的注释不会被破坏；</li>
 *   <li>{@code true} ——按“用 {@code Plugin#getConfig()} 持久化”的字面要求写回 config.yml 的
 *       {@code player-preferences} 节点。注意 Bukkit 的 {@code saveConfig()} 会重写整个
 *       config.yml 并丢掉全部注释，所以这不是默认方案。</li>
 * </ul>
 *
 * <p>兼容旧版单开关数据：老文件形如 {@code <uuid>: true}，加载时只作用于底部信息栏，
 * 罗盘保持未设置（跟随配置默认值）。</p>
 *
 * <p>写盘做了防抖：多次 toggle 只会在最后一个防抖窗口结束后写一次。</p>
 */
public final class PreferenceStore implements Listener {

    /** 单玩家的两条独立开关；null 字段 = 未设置，跟随配置默认值。 */
    public static final class PlayerPrefs {
        Boolean actionBar;
        Boolean compass;
    }

    /** 可独立开关的 HUD 部件。 */
    public enum Flag {
        /** 底部信息栏（ActionBar）。 */
        ACTION_BAR,
        /** 顶部罗盘（BossBar）。 */
        COMPASS;
    }

    /** 内存态：玩家 → 独立开关（只含有显式设置过至少一项的玩家）。 */
    private final Map<UUID, PlayerPrefs> preferences = new ConcurrentHashMap<>();

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
            UUID id;
            try {
                id = UUID.fromString(entry.getKey());
            } catch (IllegalArgumentException ex) {
                continue; // 手工改坏的 key，跳过
            }
            Object value = entry.getValue();
            if (value instanceof Boolean legacy) {
                // 旧版单开关：只作用于底部信息栏，罗盘跟随配置默认值
                prefs(id).actionBar = legacy;
            } else if (value instanceof ConfigurationSection section) {
                // 新版格式：<uuid>: { action-bar: x, compass: y }（缺省字段视为未设置）
                Object actionBar = section.get("action-bar");
                if (actionBar instanceof Boolean b) {
                    prefs(id).actionBar = b;
                }
                Object compass = section.get("compass");
                if (compass instanceof Boolean b) {
                    prefs(id).compass = b;
                }
            }
        }
    }

    private PlayerPrefs prefs(UUID id) {
        return preferences.computeIfAbsent(id, u -> new PlayerPrefs());
    }

    /** 玩家当前是否应该看到底部信息栏。 */
    public boolean actionBar(UUID playerId, boolean fallback) {
        PlayerPrefs p = preferences.get(playerId);
        return p == null || p.actionBar == null ? fallback : p.actionBar;
    }

    /** 玩家当前是否应该看到顶部罗盘。 */
    public boolean compass(UUID playerId, boolean fallback) {
        PlayerPrefs p = preferences.get(playerId);
        return p == null || p.compass == null ? fallback : p.compass;
    }

    /** 玩家是否显式设置过某条开关（{@code /infohud status} 用来区分“默认开”和“我开的”）。 */
    public boolean isSet(UUID playerId, Flag flag) {
        PlayerPrefs p = preferences.get(playerId);
        if (p == null) {
            return false;
        }
        return flag == Flag.ACTION_BAR ? p.actionBar != null : p.compass != null;
    }

    /**
     * 设置指定开关并安排落盘。必须在主线程调用（只改内存 + 提交调度任务，没有磁盘 IO）。
     */
    public void set(UUID playerId, Flag flag, boolean value) {
        PlayerPrefs p = prefs(playerId);
        boolean changed;
        if (flag == Flag.ACTION_BAR) {
            changed = p.actionBar == null || p.actionBar != value;
            p.actionBar = value;
        } else {
            changed = p.compass == null || p.compass != value;
            p.compass = value;
        }
        if (changed) {
            dirty = true;
            scheduleWrite();
        }
    }

    /** 反转指定开关并返回新值。 */
    public boolean toggle(UUID playerId, Flag flag, boolean fallback) {
        boolean next = flag == Flag.ACTION_BAR
                ? !actionBar(playerId, fallback)
                : !compass(playerId, fallback);
        set(playerId, flag, next);
        return next;
    }

    /**
     * 玩家离线时确保变更落盘。
     *
     * <p>注意：内存表里<b>不</b>删除该玩家，否则重启后个人开关就丢了（需求要求持久化偏好）。
     * 每名玩家只占一条 {@code UUID -> PlayerPrefs} 记录，长期跑服也不会失控。</p>
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
        Map<UUID, Map<String, Boolean>> snapshot = snapshotForWrite();
        if (plugin.config().preferencesInConfig()) {
            synchronized (ioLock) {
                FileConfiguration fileConfiguration = plugin.getConfig();
                fileConfiguration.set("player-preferences", null);
                for (Map.Entry<UUID, Map<String, Boolean>> entry : snapshot.entrySet()) {
                    fileConfiguration.set("player-preferences." + entry.getKey(), entry.getValue());
                }
                plugin.saveConfig();
                dirty = false;
            }
            return;
        }

        YamlConfiguration out = new YamlConfiguration();
        for (Map.Entry<UUID, Map<String, Boolean>> entry : snapshot.entrySet()) {
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

    /** 只取“显式设置过”的开关：action-bar / compass 各键在值为 null 时不写入。 */
    private Map<UUID, Map<String, Boolean>> snapshotForWrite() {
        Map<UUID, Map<String, Boolean>> out = new HashMap<>();
        for (Map.Entry<UUID, PlayerPrefs> entry : preferences.entrySet()) {
            Map<String, Boolean> flags = new HashMap<>(2);
            if (entry.getValue().actionBar != null) {
                flags.put("action-bar", entry.getValue().actionBar);
            }
            if (entry.getValue().compass != null) {
                flags.put("compass", entry.getValue().compass);
            }
            if (!flags.isEmpty()) {
                out.put(entry.getKey(), flags);
            }
        }
        return out;
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

    /** 当前内存中的玩家偏好条数（{@code /infohud status} 展示用）。 */
    public int size() {
        return preferences.size();
    }
}
