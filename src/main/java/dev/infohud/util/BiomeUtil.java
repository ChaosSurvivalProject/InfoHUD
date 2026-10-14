package dev.infohud.util;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Biome;
import org.bukkit.entity.Player;

import dev.infohud.config.PluginConfig;

/**
 * 群系读取与命名映射。
 *
 * <p>1.21.11 里 {@code org.bukkit.block.Biome} 是注册表类型（实现 {@code Keyed}），
 * 用 {@code biome.getKey()} 拿到 {@link NamespacedKey}，去掉 {@code minecraft:} 前缀后
 * 去配置表 {@code biome-names} 里查中文名；命名空间不是 minecraft 的（其它插件/数据包
 * 自定义群系）则保留完整 {@code namespace:path}，防止和原版 ID 撞名。</p>
 */
public final class BiomeUtil {

    private static final String MINECRAFT_NAMESPACE = "minecraft";

    /** 区块未加载 / 拿不到群系时的占位文本。 */
    public static final String UNKNOWN = "--";

    private BiomeUtil() {
    }

    /**
     * 读取玩家当前群系。
     *
     * <p>{@code World#getBiome(Location)} 必须在主线程调用，且会触发区块访问；
     * 因此 {@code loadedChunkOnly} 为 true 时先判定区块是否已加载，避免把未加载区块拉进内存。</p>
     *
     * @param player          玩家
     * @param loadedChunkOnly 是否只在区块已加载时查询
     * @return 群系，取不到时返回 null
     */
    public static Biome readBiome(Player player, boolean loadedChunkOnly) {
        Location location = player.getLocation();
        World world = location.getWorld();
        if (world == null) {
            return null;
        }
        if (loadedChunkOnly && !world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            return null;
        }
        try {
            return world.getBiome(location);
        } catch (IllegalStateException | IllegalArgumentException ex) {
            // 极端情况（玩家所在区块刚好被卸载）下放弃这一次查询，下一个间隔再试
            return null;
        }
    }

    /**
     * 群系 ID（原版去 {@code minecraft:} 前缀，自定义保留命名空间）。
     */
    public static String idOf(Biome biome) {
        if (biome == null) {
            return UNKNOWN;
        }
        NamespacedKey key = biome.getKey();
        if (key == null) {
            return UNKNOWN;
        }
        return MINECRAFT_NAMESPACE.equals(key.getNamespace())
                ? key.getKey()
                : key.getNamespace() + ":" + key.getKey();
    }

    /**
     * 群系显示名：命中 {@code biome-names} 配置就用中文名（配置值本身也可带颜色代码），
     * 未配置则回退原始英文 ID。
     */
    public static String displayName(Biome biome, PluginConfig config) {
        String id = idOf(biome);
        if (UNKNOWN.equals(id)) {
            return UNKNOWN;
        }
        String mapped = config.biomeName(id);
        if (mapped != null) {
            return mapped;
        }
        // 自定义群系带命名空间时，只显示路径部分更易读（命名空间已在 %biome_id% 里保留）
        int colon = id.indexOf(':');
        return colon > 0 ? id.substring(colon + 1).replace('_', ' ') : id.replace('_', ' ');
    }
}
