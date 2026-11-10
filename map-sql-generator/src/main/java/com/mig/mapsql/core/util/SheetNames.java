package com.mig.mapsql.core.util;

/**
 * Sheet 名与物理目标表名的约定。
 * <p>
 * 多数据来源时 Sheet 带末位编号（如 {@code HVPS_EXTPAYMENT1}），库表名为去掉末位数字后的名字。
 * 当前规则与历史对比逻辑一致：只去掉末尾<strong>一位</strong>数字（暂不支持两位数后缀）。
 * <ul>
 *   <li>导出单元 / 文件名：保留完整 Sheet 名（{@code HVPS_EXTPAYMENT1.sql}），避免多源互相覆盖</li>
 *   <li>物理表名 / sqlldr INTO / Excel↔库对比：{@link #resolveTableName(String)}</li>
 * </ul>
 */
public final class SheetNames {

    private SheetNames() {
    }

    /**
     * Sheet 名 → 库表名。末字符为数字时去掉（多数据来源编号，暂不支持两位数）。
     * 例：{@code HVPS_EXTPAYMENT1} → {@code HVPS_EXTPAYMENT}；无后缀则原样返回。
     */
    public static String resolveTableName(String sheetName) {
        if (sheetName == null) {
            return "";
        }
        String n = sheetName.trim();
        if (n.isEmpty()) {
            return n;
        }
        char last = n.charAt(n.length() - 1);
        if (last >= '0' && last <= '9' && n.length() > 1) {
            return n.substring(0, n.length() - 1);
        }
        return n;
    }
}
