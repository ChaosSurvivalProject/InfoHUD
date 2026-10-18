# InfoHUD

轻量级 **服务端** 实时信息 HUD 插件，两块显示区**各自独立开关**：

**底部 ActionBar（信息栏）** 持续显示

1. 游戏内时间（24 小时制 `HH:MM`）
2. 现实世界时间（服务器时区，`HH:MM:SS`）
3. 当前时段（日出 / 白天 / 日落 / 黑夜，边界可配）
4. 玩家当前所处生物群系（中文映射可配）
5. 进入新群系时显示群系名（默认屏幕中央大字，停留 3s；可配成聊天栏或关闭；同一群系 5 分钟内不重复弹）

**顶部 BossBar（罗盘）** 持续显示方向指示

6. 以当前朝向为中心的 180° 刻度窗口：N/E/S/W + 30° 角度数字 + 5° 圆点，正中高亮刻度即方向，进度条位置即指针，随玩家视角转向实时更新

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
| `/infohud toggle` | 开 / 关自己的**底部信息栏**（个人偏好会落盘） | `infohud.use` |
| `/infohud toggle compass` | 开 / 关自己的**顶部罗盘**（与底部信息栏独立） | `infohud.compass` |
| `/infohud toggle healthbar` | 开 / 关自己的**受击血条**（与信息栏、罗盘独立） | `infohud.healthbar` |
| `/infohud toggle <actionbar\|compass\|healthbar> <玩家>` | 代管其他玩家的开关 | `infohud.admin` |
| `/infohud status` | 查看运行参数、当前群系、ActionBar 预览与罗盘预览 | 无（游戏内执行才有个人数据） |

别名：`/ihud`、`/hud`。命令带 Tab 补全（`toggle` 先补开关名，选定开关后再补在线玩家）。
血条开关对已经在显示的血条**立即生效**，不需要等下一次受击（只影响自己的血条）。

## 三、权限节点

| 节点 | 默认 | 作用 |
| --- | --- | --- |
| `infohud.use` | 所有人 | 接收底部 ActionBar 信息栏；可开关自己的信息栏 |
| `infohud.compass` | 所有人 | 接收顶部 BossBar 罗盘；可开关自己的罗盘 |
| `infohud.healthbar` | 所有人 | 接收受击生物血条；可开关自己的血条 |
| `infohud.notify` | 所有人 | 进入新群系时接收提示（屏幕中央大字或聊天栏，取决于 `biome-notification.mode`） |
| `infohud.admin` | OP | `/infohud reload`、代管他人开关 |
| `infohud.*` | OP | 以上全部 |

没有 `infohud.use` 的玩家不会被推送任何 ActionBar 消息；没有 `infohud.compass` 的玩家不会生成 BossBar（任务直接跳过，不产生开销）；
没有 `infohud.healthbar` 的玩家连血条 BossBar 包都不会收到，也不会留下任何血条。
三者互不影响——可以在 `permissions` 里单独关掉某个部件。

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
  enabled: true                       # 总开关（mode: off 与它等价）
  mode: title                         # title = 屏幕中央大字；chat = 聊天栏；off = 关闭
  message-format: "&a[InfoHUD] &e已到达 &b%biome%"   # mode=chat 时使用
  center-title: "&f%biome%"            # mode=title 时中央大字标题（白色，直接显示群系名）
  divider: "—"                        # 标题下方横线的填充字符（中文破折号）
  divider-symbol: "✦"                 # 横线正中的符号（"" = 纯横线）
  divider-scale: 2.5                  # 横线显示宽度 = 标题宽度 × 该倍数（0 = 不画线）
  center-duration-seconds: 3          # 中央大字停留秒数（1~10）
  memory-seconds: 300         # 群系记忆：同一群系 5 分钟内不再重复展示（0 = 关闭）
  min-move-distance: 5        # 距上次提示至少走 5 格才发，防交界线刷屏
  query-cooldown-ticks: 5     # 每名玩家两次群系查询的最小间隔（0 = 每次跨方块都查）
  loaded-chunk-only: true     # 只在区块已加载时查询，绝不触发区块生成
  notify-on-world-change: true

storage:
  preferences-in-config: false  # true 时偏好写进 config.yml（会丢失注释，见下）
  save-debounce-seconds: 5      # 多次 toggle 合并成一次写盘
```

- **展示方式由 `mode` 一处决定，三选一**：
  - `title`（默认）—— 屏幕中央大字，以 title 形式显示 `center-title` 文案，
    并在 subtitle 里补一条**标题下方的横线**，停留 `center-duration-seconds` 秒（默认 3，范围 1~10 自动夹取）；
  - `chat` —— 聊天栏消息，只使用 `message-format`；
  - `off` —— 完全关闭，不展示任何群系切换提示（与 `enabled: false` 效果相同）。
  兼容旧写法：`center` = `title`，`none` / `false` = `off`，升级不会让旧配置失效。
  `enabled: false` 时无论 `mode` 写什么都不提示（总开关优先）。
- `title` 的默认文案就是**白色的群系名本身**（`&f%biome%`），不再带“进入”字样；想加前缀自己写在模板里即可。
  文案支持占位符 `%biome%`、`%biome_id%`、`%player%`，颜色写法与 `display-format` 完全一致
  （传统 `&`/`§` 代码或 MiniMessage 标签，由 `hud.text-mode` 决定解析方式）。
- **标题横线**：形状是 `————✦————`。
  - `divider` 是填充字符，默认中文破折号 `—`（U+2014）：它在原版字体里撑满字格，
    连续重复会首尾相接成一条实线。只写纯字符，不要写颜色代码。
  - `divider-symbol` 是正中符号，默认 `✦`（U+2726）。填充在符号**左右严格等长**，
    所以不管标题多宽，符号都精确落在屏幕正中；写 `""` 就退化成一条纯横线。
  - `divider-scale` 是横线显示宽度相对标题宽度的倍数（默认 2.5，0 = 不画线，上限 12）。
  - 横线画在 **subtitle** 里，而 subtitle 的客户端缩放只有 title 的一半，所以代码会按缩放比
    自动把字符数补足一倍 —— 不用自己加长。横线颜色跟随标题（白色标题 → 白色横线）、
    强制加粗且不用斜体。长度按“半角 6px / 全角 9px / 空格 4px”估算，并且夹在屏幕内
    （subtitle 不换行，超宽会被裁掉，所以长群系名的横线也不会溢出）。
  - 升级后 `divider: "_"`（上一版默认）会自动迁移成 `—`，跟 `center-title` 的旧值迁移一致。
  升级后若 `center-title` 还是旧默认值（`&b%biome%` 或更早的 `&e进入 &b%biome%`），
  会自动迁移成新的白色默认值；自己改过的模板原样保留。

- **群系记忆（默认 5 分钟）**：按“玩家 + 群系”计时，同一个群系在 `memory-seconds` 内
  已经展示过一次就不再展示 —— 在平原与森林之间来回穿行只会弹一次，离开再回来也不会立刻重弹。
  `0` 为关闭记忆（每次进入都提示），上限 24 小时。记忆**只存在内存里**：玩家下线即清空，
  重启服务器/`/infohud reload` 也会清空，不写任何文件。

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
严格用 `Plugin#getConfig()` 持久化时才打开。个人开关现在分两条（`action-bar` / `compass`）
分别落盘；旧版单开关数据（`<uuid>: true`）加载时只作用于底部信息栏，罗盘跟随配置默认值。

### 4.4 `compass`（顶部 BossBar 罗盘）

```yaml
compass:
  enabled-by-default: true      # 玩家默认是否开启罗盘（个人开关会覆盖它，见 storage）
  update-interval-ticks: 2     # 罗盘刷新间隔（tick），1 = 跟视角实时转
  boss-bar-color: WHITE        # BossBar 颜色（RED/GREEN/YELLOW/BLUE/PURPLE/PINK/WHITE）
  boss-bar-style: SEGMENTED_10 # 样式，SEGMENTED_10 自带 10 段刻度（SOLID/SEGMENTED_6/10/12/20）
```

- 罗盘显示在屏幕**顶部 BossBar**：标题是**以当前朝向为中心的 180° 刻度窗口**（37 格 × 5°），
  **正中一格的高亮刻度就是当前朝向**——落在正南北东西时是金色方向字 `N/E/S/W`，
  落在 30° 倍数时是角度数字，其余角度就是一根青色加粗的圆点。
  刻度读出方式：`W ∙∙∙∙∙ 300 ∙∙∙∙∙ 330 ∙∙∙∙∙ N ∙∙∙∙∙ 30 ∙∙∙∙∙ 60 ∙∙∙∙∙ E`
  （每 30° 一个数字、每 5° 一个圆点）。进度条位置同样是指针（北 = 0%、东 = 25%、南 = 50%、西 = 75%）。
  - **角度数字是自然位宽**（`30`/`60` 两位，`330`/`120` 三位）。
  - **高亮几乎严格居中**：客户端按整体像素宽度居中标题，而 180° 窗口的每半边恰好含
    1 个方位字 + 2 个数字 + 15 个圆点，半宽只可能是 120/126/132px（都是 6 的倍数）。
    由于空格是 4px，**数学上无法精确等宽**；实现里把较窄的一边用空格补到最接近，
    残留误差 ≤ 2px（高亮偏移 ≤ 1px，肉眼不可见）。不补的话最多会偏 6px。
    补位空格只贴在最外侧，刻度之间不会出现空隙。
  - 标题**不再单独显示当前角度数字**：方向看正中高亮那一格即可。
  - **转向时刻度整体平移**，数字与方向字依次滑过窗口，看起来就像罗盘在转。
  - 刻度字形（`∙`）与方向字母、数字**等宽**（都是 6 像素，实测原版 `ascii.png`）。
- **随视角转向更新**：每轮只读玩家自带的 yaw；标题按 5° 刻度、进度按 1° 分别判定，
  各自没变就不发包（站桩零开销）。
- **与底部信息栏完全独立**：
  - 配置：`compass.enabled-by-default` 只管罗盘，`hud.enabled-by-default` 只管信息栏；
  - 权限：`infohud.compass` 与 `infohud.use` 互不影响；
  - 玩家开关：`/infohud toggle compass` 与 `/infohud toggle` 各切各的。

### 4.5 受击血条（`bar`）

```yaml
bar:
  enabled: true                 # 服务端总闸：关掉后所有玩家都收不到血条
  enabled-by-default: true      # 玩家默认是否接收（个人开关会覆盖它）
  title:
    living: "<gray><health>/<max> <reset><name> -<damage><red>♥"
    dead: "<gray><health>/<max> <dark_gray>☠ <name> ☠ <reset>-<damage><red>♥"
  overlay: PROGRESS             # PROGRESS/SOLID、NOTCHED_6/10/12/20
  color: RED
  time: 60                      # 存活时停留 tick（20 tick = 1 秒，默认 3 秒；范围 20~2000）
  dead-time: 20                 # 死亡后停留 tick（默认 1 秒；范围 20~2000）

blacklist:                      # 名单内的实体类型不显示血条
  - ENDER_DRAGON
  - WITHER
  - PLAYER
  - ARMOR_STAND
```

- 只有**玩家造成的伤害**才触发（近战，或玩家射出的箭/弩箭/雪球/药水等投射物）；
  自然掉血（摔落、火焰、溺水、窒息、虚空）与怪物互殴、铁砧、TNT 都不显示。
- 每个「**攻击者 + 生物**」组合各自一条**独立** BossBar，**只有攻击者本人能看见**：
  A 打了僵尸，只有 A 看到血条，旁边的 B 完全看不到；B 自己也去打了同一只僵尸，
  才会生成 B 自己的那一条，两条各自计时、互不刷新。再被攻击刷新内容与倒计时。
  同一只生物死亡时，它身上所有攻击者的血条都会切到 `title.dead`。
  存活时停留 `time`（默认 60 tick = **3 秒**）；死亡时切到 `title.dead` 模板、进度归零，
  并且只再停留 `dead-time`（默认 20 tick = **1 秒**）—— 死亡信息看一眼就够，不必和存活一样久。
- 标题占位符：`<entity>`（原版翻译名，客户端按自己语言渲染）、`<name>`（自定义名，没有则回退 `<entity>`）、
  `<health>` / `<max>`（当前 / 最大生命值，死亡显示 0）、`<damage>`（本次最终伤害）。
- **血条会跟随生物的实时血量**：存活期间每 5 tick 校正一次标题与进度，
  所以悦灵、狼、猫、马这类**会自然回血**的生物挨打之后，血条会跟着涨回去；
  药水、生命偷取、其它插件 `setHealth` 改血量同样会同步。
  只有血量文本真的变化时才重发标题包，静止的血条不产生额外流量。
  死亡后切到 `title.dead` 后就不再跟随（那是终局信息）。
  默认 `time` 只有 3 秒，回血往往来不及看几次；想完整观察就把 `time` 调大（如 `200` = 10 秒）。
- **三层开关**，缺一不可：`bar.enabled`（服务端总闸）→ `infohud.healthbar`（权限）→
  `bar.enabled-by-default` 与 `/infohud toggle healthbar`（个人开关）。关掉的玩家**连包都不收**，
  不会白占带宽，也不会给别人留下血条。
- `/infohud toggle healthbar` 只影响**自己**的血条，且对已经在显示的立即生效：
  关掉立刻摘掉自己的，打开立刻补上自己的；别人的血条不受影响。

### 4.6 颜色格式

同一行里可以混写传统代码和 MiniMessage 标签：

```yaml
display-format: "<gradient:#00ffcc:#0088ff>时间</gradient> &f%gametime% &8| &a%biome%"
```

- `text-mode: auto` —— 出现 MiniMessage 标签就整体按 MiniMessage 解析，其中的 `&a`/`§a`/`&l`
  会被自动展开成对应标签（并保持成对闭合）；没有标签则按传统代码解析。
- `text-mode: legacy` —— 只认 `&`/`§`，`<red>` 会当普通字符输出。
- `text-mode: minimessage` —— 强制 MiniMessage。
- 十六进制：传统写法 `&#RRGGBB`，或 MiniMessage 的 `<color:#RRGGBB>`。

### 4.7 占位符

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

提示模板（`message-format` 与 `center-title`）支持 `%biome%`、`%biome_id%`、`%player%`。

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
| 刷新频率 | 信息栏主线程任务默认 20 tick（**硬下限 10**）；罗盘独立任务默认 2 tick，仅读 yaw 零区块访问 |
| 游戏时间 | 启动时预生成 24000 条 `HH:MM` 文本表，运行期只做数组下标访问 |
| 世界级数值 | 游戏时间/时段**按世界**每轮算一次，同世界玩家复用 |
| 现实时间 / TPS | 每轮只算一次，与玩家数无关 |
| 群系查询 | 不在 HUD 任务里查区块：只在“玩家确实跨方块”（`PlayerMoveEvent#hasChangedBlock()`，O(1) 早退）+“每玩家查询冷却到期”时读一次，且限定已加载区块 |
| 罗盘（BossBar） | 标题按 5° 刻度（整圈 72 种缓存复用）、进度按 1° 判定，各自未变时零网络包；仅离线/关开关时关闭 BossBar |
| 组件解析 | 同轮内文本相同直接复用同一个不可变 `Component`；`Component` 不可变，复用安全 |
| 磁盘 IO | 偏好写盘防抖合并；`data.yml` 走异步线程写，主线程零阻塞 |
| 内存 | 玩家离线即清理群系缓存、群系记忆与 BossBar；只有个人偏好按 UUID 长期保留（每人两条布尔值） |

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
  biome/BiomeMemory.java                      群系记忆（内存：玩家+群系 冷却，防重复展示）
  compass/CompassService.java               每玩家独占 BossBar + 方位角缓存
  compass/CompassTask.java                    主线程定时刷新顶部罗盘
  bar/HealthBarService.java                   受击血条（伤害/死亡事件 + 玩家开关过滤）
  bar/HealthBarSweepTask.java                 主线程定时清扫到期血条（5 tick 一轮）
  util/CompassUtil.java                       罗盘刻度窗口与进度计算
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
顶部罗盘同理：看 `infohud.compass` 与 `/infohud toggle compass`。

**Q：打怪没有血条？**
A：三层开关任一没开都看不到：`bar.enabled`（服务端总闸）、`infohud.healthbar`（权限）、
`/infohud toggle healthbar`（个人开关，`/infohud status` 会显示是“个人设置”还是“跟随默认值”）。
另外确认伤害是**玩家造成的**（怪物互殴、摔落、火焰都不算），且该实体不在 `blacklist` 里。

**Q：顶部罗盘指针和文字哪个准？**
A：两者是同一朝向的两种读法：进度条位置是指针（北 0% / 东 25% / 南 50% / 西 75%），
标题正中那一格的高亮刻度是同方向的刻度读法。想让转屏更跟手，把 `compass.update-interval-ticks` 调到 1。

**Q：现实时间不对？**
A：改 `hud.timezone`，用 IANA 名称（`Asia/Shanghai`、`UTC`、`Asia/Tokyo`）。写错会回退系统时区，
控制台有告警。

**Q：HUD 里群系显示 `--`？**
A：玩家所在区块尚未加载（刚传送落地的一瞬间），下一轮刷新就会恢复正常。

**Q：改了 `min-move-distance` 还是刷屏？**
A：那是“真跨了两个群系”。把 `biome-notification.enabled` 设为 `false` 只保留 HUD，
或调大 `query-cooldown-ticks` / `min-move-distance`。

**Q：同一条群系提示不再出现？**
A：那是群系记忆在起作用：`memory-seconds`（默认 300 秒）内同一个群系只提示一次。
想每次进入都提示就把它设为 `0`；想看提示频繁程度可以调小到 `30` 之类。

**Q：Purpur 专属功能能用吗？**
A：可以。插件只用 Paper API，Purpur 完整兼容；同一 jar 在 Paper 与 Purpur 上都能跑。

**Q：支持 1.20.x 吗？**
A：不支持。`api-version: 1.21` + Java 21 字节码，换版本请按第六节重新编译。
