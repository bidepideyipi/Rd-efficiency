package com.mig.mapsql.catalog;

import java.util.Locale;

/**
 * 将 ALL_TAB_COLUMNS 字段格式化为 Excel「数据类型 / 长度」列写法。
 */
public final class OracleTypeFormatter {

    private OracleTypeFormatter() {
    }

    public static void applyExcelTypeAndLength(DbColumnMeta meta,
                                               String dataType,
                                               Integer dataLength,
                                               Integer dataPrecision,
                                               Integer dataScale,
                                               Integer charLength) {
        String dt = dataType == null ? "" : dataType.trim().toUpperCase(Locale.ROOT);
        meta.setDataType(dt);
        meta.setExcelType(dt);

        if (dt.contains("NUMBER") || "FLOAT".equals(dt) || "BINARY_FLOAT".equals(dt) || "BINARY_DOUBLE".equals(dt)) {
            if (dataPrecision != null && dataPrecision > 0) {
                if (dataScale != null && dataScale > 0) {
                    meta.setExcelLength(dataPrecision + "," + dataScale);
                } else {
                    meta.setExcelLength(String.valueOf(dataPrecision));
                }
            } else {
                meta.setExcelLength("");
            }
            return;
        }
        if (dt.contains("TIMESTAMP") || "DATE".equals(dt) || "CLOB".equals(dt) || "BLOB".equals(dt)
                || "NCLOB".equals(dt) || "LONG".equals(dt) || "LONG RAW".equals(dt)) {
            meta.setExcelLength("");
            return;
        }
        if (charLength != null && charLength > 0
                && (dt.contains("CHAR") || dt.contains("VARCHAR"))) {
            meta.setExcelLength(String.valueOf(charLength));
            return;
        }
        if (dataLength != null && dataLength > 0) {
            meta.setExcelLength(String.valueOf(dataLength));
        } else {
            meta.setExcelLength("");
        }
    }

    public static String normalizeType(String type) {
        return type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
    }

    public static String normalizeLength(String length) {
        if (length == null) {
            return "";
        }
        return length.trim().replace(" ", "");
    }

    public static boolean typeSignatureEquals(String excelType, String excelLength,
                                              String dbExcelType, String dbExcelLength) {
        return normalizeType(excelType).equals(normalizeType(dbExcelType))
                && normalizeLength(excelLength).equals(normalizeLength(dbExcelLength));
    }
}
