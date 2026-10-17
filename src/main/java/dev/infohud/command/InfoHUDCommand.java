package dev.infohud.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

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
import dev.infohud.config.PreferenceStore;
import dev.infohud.text.TextUtil;
import dev.infohud.util.CompassUtil;
import net.kyori.adventure.text.Component;

/**
 * {@code /infohud} 命令：帮助、重载、个人开关（底部信息栏 / 顶部罗盘各自独立）、状态查看。
 *
 * <p>权限：{@code infohud.use}（底部信息栏）、{@code infohud.compass}（顶部罗盘）、
 * {@code infohud.admin}（reload、代管他人）。</p>
 */
public final class InfoHUDCommand implements CommandExecutor, TabCompleter {

    private static final String PREFIX = "&a[InfoHUD] &r";
    private static final String HELP = """
            &e─── InfoHUD ───&r
            &7/infohud &f- 显示本帮助
            &7/infohud reload &f- 重载配置文件 &8(infohud.admin)
            &7/infohud toggle &f- 开关自己的底部信息栏 &8(infohud.use)
            &7/infohud toggle compass &f- 开关自己的顶部罗盘 &8(infohud.compass)
            &7/infohud toggle <compass|actionbar> [玩家] &f- 代管他人，需要 &cinfohud.admin&r
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

    /**
     * /infohud toggle [compass|actionbar] [玩家]
     *
     * <p>缺省开关对象是底部信息栏（保持旧版 {@code /infohud toggle} 语义）；
     * {@code compass} 切顶部罗盘；{@code actionbar}/{@code hud} 显式切底部信息栏。
     * 带玩家名需 {@code infohud.admin}（旧用法 {@code toggle <玩家名>} 仍按底部信息栏处理）。</p>
     */
    private boolean toggle(CommandSender sender, String[] args) {
        PreferenceStore.Flag flag;
        int playerArg;
        String first = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        if (first.equals("compass")) {
            flag = PreferenceStore.Flag.COMPASS;
            playerArg = 2;
        } else if (first.equals("actionbar") || first.equals("hud")) {
            flag = PreferenceStore.Flag.ACTION_BAR;
            playerArg = 2;
        } else {
            flag = PreferenceStore.Flag.ACTION_BAR;
            playerArg = 1;
        }

        Player target;
        if (args.length > playerArg) {
            if (!sender.hasPermission(Permissions.ADMIN)) {
                send(sender, PREFIX + "&c修改其他玩家的开关需要 &finfohud.admin&c 权限。");
                return true;
            }
            target = Bukkit.getPlayerExact(args[playerArg]);
            if (target == null) {
                send(sender, PREFIX + "&c找不到在线玩家 &f" + args[playerArg]);
                return true;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            send(sender, PREFIX + "&c控制台请使用 &f/infohud toggle " + flagKey(flag) + " <玩家名>");
            return true;
        }

        String perm = flag == PreferenceStore.Flag.COMPASS ? Permissions.COMPASS : Permissions.USE;
        if (!target.hasPermission(perm)) {
            send(sender, PREFIX + "&c该玩家没有 &f" + perm + "&c 权限，该部件对其始终关闭。");
            return true;
        }

        PluginConfig config = plugin.config();
        boolean fallback = flag == PreferenceStore.Flag.COMPASS
                ? config.compassEnabledByDefault() : config.enabledByDefault();
        boolean enabled = plugin.preferences().toggle(target.getUniqueId(), flag, fallback);
        if (!enabled && flag == PreferenceStore.Flag.COMPASS) {
            // 立即摘掉 BossBar，不等下一轮任务
            plugin.compass().closeFor(target.getUniqueId());
        }
        String what = flag == PreferenceStore.Flag.COMPASS ? "顶部罗盘" : "底部信息栏";
        String state = enabled ? "&a已开启" : "&c已关闭";
        if (target == sender) {
            send(sender, PREFIX + what + " " + state);
        } else {
            send(sender, PREFIX + "已把 &f" + target.getName() + "&r 的" + what + " " + state);
            send(target, PREFIX + "管理员已把你的" + what + " " + state);
        }
        return true;
    }

    private static String flagKey(PreferenceStore.Flag flag) {
        return flag == PreferenceStore.Flag.COMPASS ? "compass" : "actionbar";
    }

    /** /infohud status */
    private boolean status(CommandSender sender) {
        PluginConfig config = plugin.config();
        send(sender, "&e─── InfoHUD &fv" + plugin.pluginVersion() + " ───");
        send(sender, "&7运行模式&r Paper/Purpur &7信息栏刷新&r &f" + config.updateIntervalTicks() + " tick"
                + "&7 罗盘刷新&r &f" + config.compassUpdateIntervalTicks() + " tick"
                + "&7 群系提示&r " + (config.notifyEnabled() ? "&a开" : "&c关")
                + "&7 提示方式&r &f" + (config.notifyMode() == PluginConfig.NotificationMode.CENTER
                        ? "中央大字 &8(" + config.centerDurationSeconds() + "s&8)" : "聊天栏")
                + "&7 文本模式&r &f" + config.textMode().name().toLowerCase(Locale.ROOT));
        send(sender, "&7时区&r &f" + config.timezone().getId() + " &7偏好条数&r &f" + plugin.preferences().size()
                + " &7在线玩家&r &f" + Bukkit.getOnlinePlayers().size());
        send(sender, "&7受击血条&r " + (config.healthBarEnabled() ? "&a开启" : "&c关闭")
                + "&7 停留&r &f" + config.healthBarTimeTicks() + " tick"
                + "&7 黑名单&r &f" + config.healthBarBlacklist().size() + " 项"
                + "&7 当前显示&r &f" + plugin.healthBars().size() + " 条");
        if (!(sender instanceof Player player)) {
            send(sender, PREFIX + "&7（状态详情需要在游戏里执行）");
            return true;
        }
        UUID id = player.getUniqueId();
        boolean barOn = player.hasPermission(Permissions.USE)
                && plugin.preferences().actionBar(id, config.enabledByDefault());
        boolean compassOn = player.hasPermission(Permissions.COMPASS)
                && plugin.preferences().compass(id, config.compassEnabledByDefault());
        send(sender, "&7底部信息栏&r " + (barOn ? "&a开启" : "&c关闭")
                + (plugin.preferences().isSet(id, PreferenceStore.Flag.ACTION_BAR) ? "&7（个人设置）" : "&7（跟随默认值）"));
        send(sender, "&7顶部罗盘&r " + (compassOn ? "&a开启" : "&c关闭")
                + (plugin.preferences().isSet(id, PreferenceStore.Flag.COMPASS) ? "&7（个人设置）" : "&7（跟随默认值）"));
        Biome biome = plugin.biomes().currentBiome(player, config);
        String preview = plugin.renderer().preview(player, biome, config);
        send(sender, "&7当前群系&r &f" + dev.infohud.util.BiomeUtil.displayName(biome, config)
                + " &8[&f" + dev.infohud.util.BiomeUtil.idOf(biome) + "&8]");
        send(sender, "&7ActionBar 预览&r &f" + preview);
        send(sender, "&7罗盘预览&r ",
                CompassUtil.buildTitle(CompassUtil.bearingOf(player.getLocation().getYaw())));
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

    /** 发组件消息（罗盘预览等直接构造好的组件）。 */
    private void send(CommandSender sender, String prefix, Component component) {
        sender.sendMessage(TextUtil.parse(prefix, plugin.config()).append(component));
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
        if (!args[0].equalsIgnoreCase("toggle")) {
            return out;
        }
        if (args.length == 2) {
            String head = args[1].toLowerCase(Locale.ROOT);
            for (String option : new String[]{"actionbar", "compass"}) {
                if (option.startsWith(head)) {
                    out.add(option);
                }
            }
            // 旧用法：toggle <玩家名>（admin）
            if (sender.hasPermission(Permissions.ADMIN)) {
                addPlayersMatching(out, head);
            }
            return out;
        }
        if (args.length >= 3 && isFlagName(args[1]) && sender.hasPermission(Permissions.ADMIN)) {
            addPlayersMatching(out, args[2].toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private static boolean isFlagName(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        return lower.equals("compass") || lower.equals("actionbar") || lower.equals("hud");
    }

    private static void addPlayersMatching(List<String> out, String head) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getName().toLowerCase(Locale.ROOT).startsWith(head)) {
                out.add(online.getName());
            }
        }
    }
}
