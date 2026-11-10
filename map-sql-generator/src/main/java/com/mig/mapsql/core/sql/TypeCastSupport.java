package com.mig.mapsql.core.sql;

import com.mig.mapsql.core.util.Strings;

public final class TypeCastSupport {

    private TypeCastSupport() {
    }

    public static String normalizeType(String type) {
        String t = Strings.upper(type);
        if (t.startsWith("VARCHAR")) {
            return "VARCHAR2";
        }
        if (t.equals("CHAR") || t.equals("NCHAR") || t.equals("NVARCHAR2")) {
            return t.equals("NVARCHAR2") ? "NVARCHAR2" : (t.equals("NCHAR") ? "NCHAR" : "CHAR");
        }
        if (t.startsWith("NUMBER") || t.equals("NUMERIC") || t.equals("DECIMAL")) {
            return "NUMBER";
        }
        if (t.startsWith("DATE") || t.equals("TIMESTAMP")) {
            return t.startsWith("TIMESTAMP") ? "TIMESTAMP" : "DATE";
        }
        return t;
    }

    public static boolean isCharType(String type) {
        String t = normalizeType(type);
        return t.equals("VARCHAR2") || t.equals("CHAR") || t.equals("NVARCHAR2") || t.equals("NCHAR");
    }

    public static boolean isNumberType(String type) {
        return "NUMBER".equals(normalizeType(type));
    }

    public static boolean isDateType(String type) {
        String t = normalizeType(type);
        return t.equals("DATE") || t.equals("TIMESTAMP");
    }

    public static boolean typeDiffers(String sourceType, String targetType) {
        if (Strings.isBlank(sourceType) || Strings.isBlank(targetType)) {
            return false;
        }
        String s = normalizeType(sourceType);
        String t = normalizeType(targetType);
        if (s.equals(t)) {
            return false;
        }
        // VARCHAR2 → CHAR（及字符型互转）按 VARCHAR2 直迁，不做 CAST
        if (isCharType(s) && isCharType(t)) {
            return false;
        }
        return true;
    }

    public static boolean canAutoCast(String sourceType, String targetType) {
        if (!typeDiffers(sourceType, targetType)) {
            return true;
        }
        String s = normalizeType(sourceType);
        String t = normalizeType(targetType);
        if (isCharType(s) && isNumberType(t)) {
            return true;
        }
        if (isNumberType(s) && isCharType(t)) {
            return true;
        }
        if (isDateType(s) && isCharType(t)) {
            return true;
        }
        if (isCharType(s) && isDateType(t)) {
            return true;
        }
        return false;
    }

    public static String applyTypeCast(String baseExpr, String sourceType, String targetType, String dateFormat) {
        if (!typeDiffers(sourceType, targetType)) {
            return baseExpr;
        }
        String s = normalizeType(sourceType);
        String t = normalizeType(targetType);
        if (isCharType(s) && isNumberType(t)) {
            // Oracle 中 '' 即为 NULL，NULLIF(..., '') 无效；TRIM 空白串已得 NULL
            return "TO_NUMBER(TRIM(" + baseExpr + "))";
        }
        if (isNumberType(s) && isCharType(t)) {
            return "TO_CHAR(" + baseExpr + ")";
        }
        if (isDateType(s) && isCharType(t)) {
            return "TO_CHAR(" + baseExpr + ", '" + dateFormat + "')";
        }
        if (isCharType(s) && isDateType(t)) {
            return "TO_DATE(" + baseExpr + ", '" + dateFormat + "')";
        }
        throw new IllegalArgumentException("Unsupported cast " + s + " -> " + t);
    }

    /** Parse character length; returns null for blank or precision like 22,2 */
    public static Integer charLength(String lengthRaw) {
        if (Strings.isBlank(lengthRaw)) {
            return null;
        }
        String l = lengthRaw.trim();
        if (l.contains(",")) {
            return null;
        }
        try {
            return Integer.parseInt(l);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
