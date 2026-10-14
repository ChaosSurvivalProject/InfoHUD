package dev.infohud;

import org.bukkit.configuration.file.YamlConfiguration;

import dev.infohud.config.PluginConfig;

/**
 * 测试辅助：直接从 YAML 文本造一个 {@link PluginConfig}，不需要真实服务器。
 */
public final class TestConfigs {

    private TestConfigs() {
    }

    /**
     * @param yaml config.yml 片段（可以是空串，此时全部走内置默认值）
     */
    public static PluginConfig config(String yaml) {
        YamlConfiguration configuration = new YamlConfiguration();
        try {
            configuration.loadFromString(yaml == null ? "" : yaml);
        } catch (Exception ex) {
            throw new RuntimeException("测试用的 YAML 写错了", ex);
        }
        return PluginConfig.load(configuration, null);
    }

    /** 用插件 jar 里的默认 config.yml 同构的一份配置（手工同步，改动时两边一起改）。 */
    public static PluginConfig defaultConfig() {
        return config("""
                hud:
                  enabled-by-default: true
                  update-interval-ticks: 20
                  timezone: "Asia/Shanghai"
                  text-mode: auto
                  ampersand-colors: true
                day-night:
                  sunrise-start: 0
                  sunrise-end: 1000
                  sunrise-late-start: 22000
                  day-start: 1000
                  day-end: 11000
                  sunset-start: 11000
                  sunset-end: 13000
                  night-start: 13000
                  night-end: 23000
                  labels:
                    sunrise: "&e日出"
                    day: "&a白天"
                    sunset: "&6日落"
                    night: "&9黑夜"
                biome-names:
                  desert: "沙漠"
                  plains: "平原"
                """);
    }
}
