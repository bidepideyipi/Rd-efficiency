package com.mig.mapsql.core.model;

/**
 * V0.4 映射表列（0-based 与表头名），用于校验失败定位。
 */
public final class ExcelColumns {

    public static final int SEQ = 0;
    public static final int TARGET_TABLE = 1;
    public static final int TARGET_COLUMN = 2;
    public static final int TARGET_COMMENT = 3;
    public static final int NULLABLE = 4;
    public static final int TARGET_TYPE = 5;
    public static final int TARGET_LENGTH = 6;
    public static final int MAPPING_TYPE = 11;
    public static final int SOURCE_SYSTEM = 12;
    public static final int SOURCE_TABLE = 13;
    public static final int SOURCE_COLUMN = 14;
    public static final int SOURCE_TYPE = 16;
    public static final int SOURCE_LENGTH = 17;
    public static final int DEFAULT_VALUE = 18;
    public static final int RULE = 21;
    public static final int JOIN_CONDITION = 22;

    public static final String H_TARGET_COLUMN = "栏位";
    public static final String H_MAPPING_TYPE = "映射类型";
    public static final String H_SOURCE_TABLE = "源表表名";
    public static final String H_SOURCE_COLUMN = "源表栏位";
    public static final String H_DEFAULT_VALUE = "默认值";
    public static final String H_RULE = "规则说明";
    public static final String H_JOIN = "库表关联条件";
    public static final String H_TARGET_TYPE = "数据类型";

    private ExcelColumns() {
    }

    /** 0-based index → Excel 列字母（A, B, …, Z, AA…） */
    public static String letter(int index0) {
        if (index0 < 0) {
            return "?";
        }
        StringBuilder sb = new StringBuilder();
        int n = index0;
        while (n >= 0) {
            sb.insert(0, (char) ('A' + (n % 26)));
            n = n / 26 - 1;
        }
        return sb.toString();
    }
}
