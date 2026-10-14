package dev.infohud;

/**
 * 权限节点常量。集中定义，避免在业务代码里散落字符串。
 */
public final class Permissions {

    /** 接收 ActionBar HUD，以及开关自己的 HUD。 */
    public static final String USE = "infohud.use";

    /** 进入新群系时接收聊天栏提示。 */
    public static final String NOTIFY = "infohud.notify";

    /** 重载配置、代管其他玩家的 HUD 状态。 */
    public static final String ADMIN = "infohud.admin";

    private Permissions() {
    }
}
