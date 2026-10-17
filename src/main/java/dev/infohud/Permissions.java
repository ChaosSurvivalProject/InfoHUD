package dev.infohud;

/**
 * 权限节点常量。集中定义，避免在业务代码里散落字符串。
 */
public final class Permissions {

    /** 接收 ActionBar HUD，以及开关自己的 HUD。 */
    public static final String USE = "infohud.use";

    /** 接收顶部 BossBar 罗盘（方向指示），与底部信息栏独立开关。 */
    public static final String COMPASS = "infohud.compass";

    /** 接收受击生物血条，与信息栏/罗盘独立开关。 */
    public static final String HEALTH_BAR = "infohud.healthbar";

    /** 进入新群系时接收提示（屏幕中央大字或聊天栏，取决于 biome-notification.mode）。 */
    public static final String NOTIFY = "infohud.notify";

    /** 重载配置、代管其他玩家的 HUD 状态。 */
    public static final String ADMIN = "infohud.admin";

    private Permissions() {
    }
}
