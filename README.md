# InfoHUD

轻量级 **服务端** 实时信息 HUD 插件：在玩家屏幕下方的 ActionBar 上持续显示

1. 游戏内时间（24 小时制 `HH:MM`）
2. 现实世界时间（服务器时区，`HH:MM:SS`）
3. 当前时段（日出 / 白天 / 日落 / 黑夜，边界可配）
4. 玩家当前所处生物群系（中文映射可配）
5. 进入新群系时在聊天栏推送提示（带刷屏抑制）

**客户端不需要安装任何东西**，原版客户端即可看到全部信息。目标服务端：Purpur 1.21.11，同 jar 兼容 Paper 1.21.11。

---

## 一、安装

1. 确认服务端是 **Paper 或 Purpur 1.21.11**（其它版本需重新编译，见第六节），Java **21**。
2. 下载构建产物 `build/libs/InfoHUD-1.0.0.jar`。
3. 上传到服务端 `plugins/` 目录。
4. 启动（或重启）服务端。插件会自动生成 `plugins/InfoHUD/config.yml`。
5. 进服即可看到 HUD；改完配置执行 `/infohud reload`，无需重启。

> 只放服务端即可，不要放进客户端 `mods/`。它是 Bukkit 插件，不是 Fabric/Forge 模组。

---

## 二、命令

| 命令 | 说明 | 权限 |
| --- | --- | --- |
| `/infohud` | 显示帮助 | — |
| `/infohud reload` | 重载 `config.yml`，并按新间隔重启刷新任务 | `infohud.admin` |
| `/infohud toggle` | 开 / 关自己的 HUD（个人偏好会落盘） | `infohud.use` |
| `/infohud toggle <玩家>` | 代管其他玩家的开关 | `infohud.admin` |
| `/infohud status` | 查看运行参数、当前群系与 ActionBar 预览 | 无（游戏内执行才有个人数据） |

别名：`/ihud`、`/hud`。命令带 Tab 补全（`toggle` 会列出在线玩家）。

## 三、权限节点

| 节点 | 默认 | 作用 |
| --- | --- | --- |
| `infohud.use` | 所有人 | 接收 ActionBar HUD；可开关自己的 HUD |
| `infohud.notify` | 所有人 | 进入新群系时接收聊天栏提示 |
| `infohud.admin` | OP | `/infohud reload`、代管他人开关 |
| `infohud.*` | OP | 以上全部 |

没有 `infohud.use` 的玩家不会被推送任何 ActionBar 消息（任务直接跳过，不产生开销）。

---

## 四、配置说明（`plugins/InfoHUD/config.yml`）

### 4.1 `hud`

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `enabled-by-default` | `true` | 玩家没有个人偏好时的默认开关 |
| `update-interval-ticks` | `20` | ActionBar 刷新间隔；**最小 10**（0.5 秒），填更小会被抬到 10，上限 200 |
| `display-format` | 见文件 | 显示模板，占位符见 4.5 |
| `realtime-format` | `HH:mm:ss` | 现实时间格式（Java `DateTimeFormatter` 模式） |
| `timezone` | `Asia/Shanghai` | IANA 时区名；写错自动回退系统时区并在控制台告警 |
| `text-mode` | `auto` | `auto` / `legacy` / `minimessage`，见 4.4 |
| `ampersand-colors` | `true` | 是否把 `&` 当颜色代码（关掉后只有 `§` 生效） |
| `placeholderapi-parse` | `true` | 装了 PlaceholderAPI 时，把最终文本再交给 PAPI 解析一次 |
| `cache-components` | `true` | 同一轮刷新内复用解析好的组件（人多时明显省 CPU） |

### 4.2 `day-night`

区间单位与 `World#getTime()` 一致（0 ~ 24000）：`tick 0/24000 = 06:00`、`6000 = 12:00`、`12000 = 18:00`、`18000 = 00:00`。

```yaml
day-night:
  sunrise-start: 0        # 日出
  sunrise-end: 1000
  sunrise-late-start: 22000  # 夜间尾段也算日出的起点；填 -1 关闭这段回绕
  day-start: 1000         # 白天
  day-end: 11000
  sunset-start: 11000     # 日落
  sunset-end: 13000
  night-start: 13000      # 黑夜
  night-end: 23000
  labels:
    sunrise: "&e日出"      # 标签里可以直接带颜色代码
    day: "&a白天"
    sunset: "&6日落"
    night: "&9黑夜"
```

判定优先级为 **日出 → 白天 → 日落 → 黑夜**。因为原版 22000~24000 天色已经开始发亮，
默认 `night-end: 23000` 与尾段日出存在重叠，重叠区按需求算作「日出」；
想要“23000 之后算黑夜”就把 `sunrise-late-start` 改成 `-1`，同时把 `night-end` 设为 `24000`。
边界写反（起点大于终点）会自动交换，配置留白时按白天/黑夜兜底，不会出现空档。

### 4.3 `biome-notification` / `biome-names` / `storage`

```yaml
biome-notification:
  enabled: true
  message-format: "&a[InfoHUD] &e已到达 &b%biome%"
  min-move-distance: 5        # 距上次提示至少走 5 格才发，防交界线刷屏
  query-cooldown-ticks: 5     # 每名玩家两次群系查询的最小间隔（0 = 每次跨方块都查）
  loaded-chunk-only: true     # 只在区块已加载时查询，绝不触发区块生成
  notify-on-world-change: true

storage:
  preferences-in-config: false  # true 时偏好写进 config.yml（会丢失注释，见下）
  save-debounce-seconds: 5      # 多次 toggle 合并成一次写盘
```

- **首次进服不会弹提示**：玩家上线（或 `/infohud reload`）时只建立群系基线，不发消息。
- **换世界与传送会正确刷新**：过下界门/末地门、`/tp`、`/spawn` 之后约 1 秒内重取群系；
  这种“坐标跨世界”的情况不做距离比较（Bukkit 的跨世界距离计算会抛异常），
  是否提示由 `notify-on-world-change` 决定。
- 群系变了但还没走够 `min-move-distance` 时，提示**不会丢**，而是挂起等走够距离后补发；
  HUD 本身立刻显示新群系，不受该阈值影响。
- `biome-names` 内置了 1.21.11 全部原版群系的中文映射（含 `desert_hills`、`snowy_tundra` 等
  1.18 前旧 ID 兼容项）。没配置的群系显示原始英文 ID；自定义群系请写完整键，
  例如 `"mymod:crystal_hills": "水晶丘陵"`。

关于 `preferences-in-config`：默认 `false`，个人开关存在 `plugins/InfoHUD/data.yml`，
由 Bukkit `saveConfig()` 写回 `config.yml` 会**抹掉整个文件的注释**，所以只在需要
严格用 `Plugin#getConfig()` 持久化时才打开。

### 4.4 颜色格式

同一行里可以混写传统代码和 MiniMessage 标签：

```yaml
display-format: "<gradient:#00ffcc:#0088ff>时间</gradient> &f%gametime% &8| &a%biome%"
```

- `text-mode: auto` —— 出现 MiniMessage 标签就整体按 MiniMessage 解析，其中的 `&a`/`§a`/`&l`
  会被自动展开成对应标签（并保持成对闭合）；没有标签则按传统代码解析。
- `text-mode: legacy` —— 只认 `&`/`§`，`<red>` 会当普通字符输出。
- `text-mode: minimessage` —— 强制 MiniMessage。
- 十六进制：传统写法 `&#RRGGBB`，或 MiniMessage 的 `<color:#RRGGBB>`。

### 4.5 占位符

| 占位符 | 含义 | 示例 |
| --- | --- | --- |
| `%gametime%` | 游戏时间 | `14:30` |
| `%realtime%` | 现实时间 | `21:45:12` |
| `%daynight%` | 时段标签 | `白天` |
| `%biome%` | 群系显示名 | `沙漠` |
| `%biome_id%` | 群系原始 ID | `desert` |
| `%world%` | 世界名 | `world` |
| `%x%` `%y%` `%z%` | 玩家方块坐标 | `128` |
| `%ping%` | 玩家延迟 | `32` |
| `%tps%` | 服务器 TPS（1 分钟窗口） | `20.0` |
| `%player%` | 玩家名（仅提示模板） | `Steve` |

提示模板 `biome-notification.message-format` 支持 `%biome%`、`%biome_id%`、`%player%`。

---

## 五、PlaceholderAPI（可选）

装了 PlaceholderAPI（`softdepend`，不装不影响本插件）后会额外注册扩展 `infohud`：

```
%infohud_gametime%   游戏时间 HH:MM
%infohud_realtime%   现实时间
%infohud_daynight%   昼夜时段
%infohud_biome%      当前群系显示名
%infohud_biome_id%   当前群系 ID
%infohud_world%      当前世界名
```

其它插件（全息、计分板、菜单）也能用这些占位符。群系取值来自缓存，**异步解析不会碰区块**。
反向集成也支持：在 `display-format` 里写 `%player_name%` 之类的 PAPI 占位符即可
（由 `hud.placeholderapi-parse` 控制）。

---

## 六、性能设计

需求目标是 50+ 玩家在线无可见 TPS 影响，实现上做了这些约束：

| 环节 | 做法 |
| --- | --- |
| 刷新频率 | 主线程任务，默认 20 tick，**硬下限 10 tick**（不可能配置成每 tick 刷屏） |
| 游戏时间 | 启动时预生成 24000 条 `HH:MM` 文本表，运行期只做数组下标访问 |
| 世界级数值 | 游戏时间/时段**按世界**每轮算一次，同世界玩家复用 |
| 现实时间 / TPS | 每轮只算一次，与玩家数无关 |
| 群系查询 | 不在 HUD 任务里查区块：只在“玩家确实跨方块”（`PlayerMoveEvent#hasChangedBlock()`，O(1) 早退）+“每玩家查询冷却到期”时读一次，且限定已加载区块 |
| 组件解析 | 同轮内文本相同直接复用同一个不可变 `Component`；`Component` 不可变，复用安全 |
| 磁盘 IO | 偏好写盘防抖合并；`data.yml` 走异步线程写，主线程零阻塞 |
| 内存 | 玩家离线即清理群系缓存；只有个人偏好按 UUID 长期保留（每人一个布尔值） |

主线程上跑的仍然是 `World#getTime()`、`sendActionBar()` 这类必须在主线程的调用；
把群系查询彻底挪到异步需要跨线程读区块，在 Paper 上反而不安全，因此采用“缓存 + 节流”。

### 自行构建

```bash
# 需要 JDK 21
gradle build          # 产物：build/libs/InfoHUD-1.0.0.jar（含单元测试）
```

想改服务端版本，编辑 `build.gradle.kts` 里的
`io.papermc.paper:paper-api:<版本>-R0.1-SNAPSHOT` 与 `src/main/resources/plugin.yml` 的
`api-version`（群系 ID 有变化时顺手更新 `biome-names`）。

---

## 七、目录结构

```
build.gradle.kts / settings.gradle.kts        Gradle（Kotlin DSL）构建脚本
src/main/resources/plugin.yml                 插件描述：命令、权限、api-version
src/main/resources/config.yml                 默认配置（含全群系中文映射）
src/main/java/dev/infohud/
  InfoHUD.java                                主类：装载、reload、任务生命周期
  Permissions.java                            权限节点常量
  config/PluginConfig.java                    配置快照（不可变，reload 整体替换）
  config/DayNightRules.java                   昼夜时段判定
  config/PreferenceStore.java                 HUD 开关缓存 + 防抖落盘
  time/TimeUtil.java                          游戏/现实时间换算（预生成表）
  time/DayNightPhase.java                     时段枚举
  util/BiomeUtil.java                         群系读取、ID 与显示名
  biome/BiomeService.java                     群系缓存、切换判定、提示发送
  hud/HudTask.java                            BukkitRunnable 刷新任务
  hud/HudRenderer.java                        占位符填充 + 组件复用
  hud/HudContext.java / WorldSnapshot.java    每轮公共上下文 / 世界快照
  listener/PlayerMoveListener.java            仅跨方块触发的群系复检
  listener/PlayerConnectionListener.java      上线建基线 / 离线清理 / 换世界重建
  command/InfoHUDCommand.java                 /infohud 子命令与 Tab 补全
  text/TextUtil.java                          §& 与 MiniMessage 双格式解析
  placeholder/InfoHUDExpansion.java           PlaceholderAPI 扩展
  placeholder/PapiBridge.java                 PAPI 调用桥（按需加载）
src/test/java/…                               时间换算、时段判定、文本解析单元测试
```

---

## 八、常见问题

**Q：进服没有 HUD？**
A：依次检查 —— 控制台是否有 `InfoHUD v1.0.0 已启用`；玩家是否有 `infohud.use`；
是否被 `/infohud toggle` 关掉过（`/infohud status` 会显示“个人设置”还是“跟随默认值”）。

**Q：现实时间不对？**
A：改 `hud.timezone`，用 IANA 名称（`Asia/Shanghai`、`UTC`、`Asia/Tokyo`）。写错会回退系统时区，
控制台有告警。

**Q：HUD 里群系显示 `--`？**
A：玩家所在区块尚未加载（刚传送落地的一瞬间），下一轮刷新就会恢复正常。

**Q：改了 `min-move-distance` 还是刷屏？**
A：那是“真跨了两个群系”。把 `biome-notification.enabled` 设为 `false` 只保留 HUD，
或调大 `query-cooldown-ticks` / `min-move-distance`。

**Q：Purpur 专属功能能用吗？**
A：可以。插件只用 Paper API，Purpur 完整兼容；同一 jar 在 Paper 与 Purpur 上都能跑。

**Q：支持 1.20.x 吗？**
A：不支持。`api-version: 1.21` + Java 21 字节码，换版本请按第六节重新编译。
