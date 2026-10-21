package dev.infohud.time;

/**
 * 世界历法中的“某一天”：由世界已流逝天数换算出的年/月/日，以及该月对应的季节。
 *
 * <p>年/月/日按现实公历换算（闰年规则与现实一致），所以二月在闰年是 29 天。</p>
 *
 * <p>季节由月份唯一确定，并且只有“键名 + 显示名”两个概念，没有下标：</p>
 * <ul>
 *   <li>{@code season} —— 季节键名（配置里的英文键，如 {@code spring}），用于判断/对接；</li>
 *   <li>{@code seasonName} —— 该键名对应的显示名（如 {@code 春季}），由配置决定。</li>
 * </ul>
 *
 * @param year       年份（受配置的起始年份影响）
 * @param month      月份 1~12
 * @param day        当月第几天 1~31
 * @param season     季节键名（小写，如 {@code spring} / {@code summer}）
 * @param seasonName 季节显示名（对应 {@code season}，可能带颜色代码）
 */
public record WorldDate(int year, int month, int day, String season, String seasonName) {

    /**
     * 日期文本，格式 {@code yyyy-MM-dd}（年、月、日都补零，例如 {@code 0001-03-07}）。
     *
     * <p>年份至少补到 4 位；起始年份配成 12345 这种超过 4 位时按实际位数输出，不截断。
     * 负年份（起始年份设成负数时才可能出现）保留负号，负号后照样补到 4 位。</p>
     *
     * <p>HUD 每轮按世界只算一次，这里手工拼接而非 {@code String.format}，
     * 与 {@code TimeUtil} 的预生成表思路一致，避免主线程上的格式化开销。</p>
     */
    public String isoDate() {
        boolean negative = year < 0;
        String yearDigits = Integer.toString(negative ? -year : year);
        int yearWidth = Math.max(4, yearDigits.length());
        char[] buffer = new char[(negative ? 1 : 0) + yearWidth + 6];
        int cursor = 0;
        if (negative) {
            buffer[cursor++] = '-';
        }
        // 年份左侧补零到 yearWidth 位
        for (int i = yearDigits.length(); i < yearWidth; i++) {
            buffer[cursor++] = '0';
        }
        for (int i = 0; i < yearDigits.length(); i++) {
            buffer[cursor++] = yearDigits.charAt(i);
        }
        buffer[cursor++] = '-';
        buffer[cursor++] = (char) ('0' + month / 10);
        buffer[cursor++] = (char) ('0' + month % 10);
        buffer[cursor++] = '-';
        buffer[cursor++] = (char) ('0' + day / 10);
        buffer[cursor] = (char) ('0' + day % 10);
        return new String(buffer);
    }
}
