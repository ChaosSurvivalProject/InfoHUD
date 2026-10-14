package dev.infohud.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.block.Biome;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import dev.infohud.InfoHUD;
import dev.infohud.Permissions;
import dev.infohud.config.PluginConfig;
import dev.infohud.text.TextUtil;

/**
 * {@code /infohud} 命令：帮助、重载、个人开关、状态查看。
 *
 * <p>权限：{@code infohud.use}（toggle/status）、{@code infohud.admin}（reload、代管他人）。</p>
 */
public final class InfoHUDCommand implements CommandExecutor, TabCompleter {

    private static final String PREFIX = "&a[InfoHUD] &r";
    private static final String HELP = """
            &e─── InfoHUD ───&r
            &7/infohud &f- 显示本帮助
            &7/infohud reload &f- 重载配置文件 &8(infohud.admin)
            &7/infohud toggle [玩家] &f- 开关自己的 HUD；带玩家名需要 &cinfohud.admin&r
            &7/infohud status &f- 查看当前 HUD 内容与运行参数 &8(infohud.use)""";

    private final InfoHUD plugin;

    public InfoHUDCommand(InfoHUD plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            send(sender, HELP);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload":
                return reload(sender);
            case "toggle":
                return toggle(sender, args);
            case "status":
                return status(sender);
            case "help":
                send(sender, HELP);
                return true;
            default:
                send(sender, PREFIX + "&c未知子命令 &f" + args[0]);
                send(sender, HELP);
                return true;
        }
    }

    /** /infohud reload */
    private boolean reload(CommandSender sender) {
        if (!sender.hasPermission(Permissions.ADMIN)) {
            send(sender, PREFIX + "&c你没有权限重载配置。");
            return true;
        }
        long start = System.nanoTime();
        plugin.reloadPlugin();
        long millis = (System.nanoTime() - start) / 1_000_000L;
        PluginConfig config = plugin.config();
        send(sender, PREFIX + "&a配置已重载&r（&f" + millis + "ms&r），刷新间隔 &f" + config.updateIntervalTicks()
                + " tick&r，时区 &f" + config.timezone().getId() + "&r，群系映射 &f" + config.biomeNameCount() + " 条"
                + (config.preferencesInConfig() ? "&7（偏好写入 config.yml，注释已被重写）" : ""));
        return true;
    }

    /** /infohud toggle [玩家] */
    private boolean toggle(CommandSender sender, String[] args) {
        Player target;
        if (args.length >= 2) {
            if (!sender.hasPermission(Permissions.ADMIN)) {
                send(sender, PREFIX + "&c修改其他玩家的开关需要 &finfohud.admin&c 权限。");
                return true;
            }
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                send(sender, PREFIX + "&c找不到在线玩家 &f" + args[1]);
                return true;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            send(sender, PREFIX + "&c控制台请使用 &f/" + "infohud toggle <玩家名>");
            return true;
        }

        if (!target.hasPermission(Permissions.USE)) {
            send(sender, PREFIX + "&c该玩家没有 &ffinfohud.use&c 权限，HUD 对其始终关闭。");
            return true;
        }

        PluginConfig config = plugin.config();
        boolean enabled = plugin.preferences().toggle(target.getUniqueId(), config.enabledByDefault());
        String state = enabled ? "&a已开启" : "&c已关闭";
        if (target == sender) {
            send(sender, PREFIX + "HUD " + state);
        } else {
            send(sender, PREFIX + "已为 &f" + target.getName() + "&r " + state);
            send(target, PREFIX + "管理员已把你的 HUD " + state);
        }
        return true;
    }

    /** /infohud status */
    private boolean status(CommandSender sender) {
        PluginConfig config = plugin.config();
        send(sender, "&e─── InfoHUD &fv" + plugin.pluginVersion() + " ───");
        send(sender, "&7运行模式&r Paper/Purpur &7刷新&r &f" + config.updateIntervalTicks() + " tick"
                + "&7 群系提示&r " + (config.notifyEnabled() ? "&a开" : "&c关")
                + "&7 文本模式&r &f" + config.textMode().name().toLowerCase(Locale.ROOT));
        send(sender, "&7时区&r &f" + config.timezone().getId() + " &7偏好条数&r &f" + plugin.preferences().size()
                + " &7在线玩家&r &f" + Bukkit.getOnlinePlayers().size());
        if (!(sender instanceof Player player)) {
            send(sender, PREFIX + "&7（状态详情需要在游戏里执行）");
            return true;
        }
        boolean hudOn = player.hasPermission(Permissions.USE)
                && plugin.preferences().enabled(player.getUniqueId(), config.enabledByDefault());
        Biome biome = plugin.biomes().currentBiome(player, config);
        String preview = plugin.renderer().preview(player, biome, config);
        send(sender, "&7你的 HUD&r " + (hudOn ? "&a开启" : "&c关闭")
                + (plugin.preferences().isSet(player.getUniqueId()) ? "&7（个人设置）" : "&7（跟随默认值）"));
        send(sender, "&7当前群系&r &f" + dev.infohud.util.BiomeUtil.displayName(biome, config)
                + " &8[&f" + dev.infohud.util.BiomeUtil.idOf(biome) + "&8]");
        send(sender, "&7ActionBar 预览&r &f" + preview);
        return true;
    }

    /** 统一发一条带颜色解析的消息；多行文本逐行解析发送。 */
    private void send(CommandSender sender, String raw) {
        if (raw.indexOf('\n') < 0) {
            sender.sendMessage(TextUtil.parse(raw, plugin.config()));
            return;
        }
        for (String line : raw.split("\n")) {
            sender.sendMessage(TextUtil.parse(line, plugin.config()));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String head = args[0].toLowerCase(Locale.ROOT);
            for (String option : new String[]{"help", "reload", "status", "toggle"}) {
                if (option.startsWith(head)
                        && (!option.equals("reload") || sender.hasPermission(Permissions.ADMIN))) {
                    out.add(option);
                }
            }
            return out;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("toggle") && sender.hasPermission(Permissions.ADMIN)) {
            String head = args[1].toLowerCase(Locale.ROOT);
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(head)) {
                    out.add(online.getName());
                }
            }
        }
        return out;
    }
}
