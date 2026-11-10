package com.mig.mapsql.standard;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 解析 Oracle/OB 列类型字符串，提取类型族与长度/精度。 */
public final class ColumnTypeParser {

    private static final Pattern WITH_PS = Pattern.compile(
            "^([A-Z0-9_]+)\\s*\\(\\s*(\\d+)\\s*(?:,\\s*(\\d+)\\s*)?\\)\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SIMPLE = Pattern.compile("^([A-Z0-9_]+)\\s*$", Pattern.CASE_INSENSITIVE);

    private ColumnTypeParser() {
    }

    public static ParsedType parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return new ParsedType("UNKNOWN", null, null, null);
        }
        String s = raw.trim().toUpperCase(Locale.ROOT);
        Matcher m = WITH_PS.matcher(s);
        if (m.matches()) {
            String family = normalizeFamily(m.group(1));
            Integer first = Integer.valueOf(m.group(2));
            Integer second = m.group(3) == null ? null : Integer.valueOf(m.group(3));
            if ("NUMBER".equals(family) || "FLOAT".equals(family)) {
                return new ParsedType(family, null, first, second);
            }
            // CHAR / VARCHAR2 等：第一参数为长度
            return new ParsedType(family, first, first, null);
        }
        Matcher s2 = SIMPLE.matcher(s);
        if (s2.matches()) {
            return new ParsedType(normalizeFamily(s2.group(1)), null, null, null);
        }
        // 兜底：取第一个词
        String family = normalizeFamily(s.replaceAll("[^A-Z0-9_].*$", ""));
        return new ParsedType(family, null, null, null);
    }

    private static String normalizeFamily(String name) {
        String u = name.toUpperCase(Locale.ROOT);
        if ("CHARACTER".equals(u) || "NCHAR".equals(u)) {
            return "CHAR";
        }
        if ("VARCHAR".equals(u) || "NVARCHAR2".equals(u) || "VARCHAR2".equals(u)) {
            return "VARCHAR2";
        }
        if ("NUMERIC".equals(u) || "DECIMAL".equals(u) || "NUMBER".equals(u)) {
            return "NUMBER";
        }
        return u;
    }

    /**
     * 用当前列解析结果填充目标模板中的 {n}/{p}/{s}。
     */
    public static String renderTarget(String template, ParsedType src) {
        String t = template.trim().toUpperCase(Locale.ROOT);
        String n = src.length != null ? String.valueOf(src.length)
                : (src.precision != null ? String.valueOf(src.precision) : "0");
        String p = src.precision != null ? String.valueOf(src.precision) : n;
        String s = src.scale != null ? String.valueOf(src.scale) : "0";
        return t.replace("{N}", n).replace("{P}", p).replace("{S}", s);
    }

    public static final class ParsedType {
        public final String family;
        /** CHAR/VARCHAR 长度 */
        public final Integer length;
        public final Integer precision;
        public final Integer scale;

        public ParsedType(String family, Integer length, Integer precision, Integer scale) {
            this.family = family;
            this.length = length;
            this.precision = precision;
            this.scale = scale;
        }

        public String display() {
            if (length != null && scale == null) {
                return family + "(" + length + ")";
            }
            if (precision != null && scale != null) {
                return family + "(" + precision + "," + scale + ")";
            }
            if (precision != null) {
                return family + "(" + precision + ")";
            }
            return family;
        }
    }
}
