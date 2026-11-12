package com.mig.mapsql.core.util;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class SqlRuleHeuristics {

    private static final Pattern SQL_HINT = Pattern.compile(
            "(?i)(case\\s+when|substr\\s*\\(|decode\\s*\\(|nvl\\s*\\(|to_char\\s*\\(|to_number\\s*\\(|to_date\\s*\\(|\\|\\||\\()");

    /** SQL 关键字 / 常用函数名：不加表别名前缀。 */
    private static final Set<String> SQL_WORDS = new HashSet<String>(Arrays.asList(
            "case", "when", "then", "else", "end", "and", "or", "not", "null", "is", "in", "like",
            "between", "exists", "as", "on", "select", "from", "where", "group", "order", "by",
            "having", "union", "all", "distinct", "join", "left", "right", "inner", "outer", "cross",
            "decode", "nvl", "nvl2", "coalesce", "nullif", "substr", "substring", "length", "trim",
            "ltrim", "rtrim", "upper", "lower", "to_char", "to_number", "to_date", "to_timestamp",
            "cast", "convert", "round", "floor", "ceil", "abs", "mod", "replace", "instr", "lpad",
            "rpad", "chr", "ascii", "greatest", "least", "sign", "trunc", "add_months", "months_between",
            "sysdate", "systimestamp", "current_date", "current_timestamp", "user", "rownum", "rowid",
            "dual", "count", "sum", "avg", "max", "min", "over", "partition", "row_number", "rank",
            "dense_rank", "true", "false", "asc", "desc", "with", "values"
    ));

    private SqlRuleHeuristics() {
    }

    public static boolean looksLikeSql(String rule) {
        if (Strings.isBlank(rule)) {
            return false;
        }
        String r = rule.trim();
        if (SQL_HINT.matcher(r).find()) {
            return true;
        }
        // pure identifier
        if (OracleIdentifier.isValid(r)) {
            return true;
        }
        // mostly Chinese without SQL keywords → not SQL
        int chinese = 0;
        for (int i = 0; i < r.length(); ) {
            int cp = r.codePointAt(i);
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) {
                chinese++;
            }
            i += Character.charCount(cp);
        }
        if (chinese > r.length() / 2) {
            return false;
        }
        String lower = r.toLowerCase(Locale.ROOT);
        return lower.contains("=") || lower.contains(" when ") || lower.contains(" then ");
    }

    public static String normalizeRule(String rule) {
        String r = Strings.trimToEmpty(rule);
        while (r.endsWith(";")) {
            r = r.substring(0, r.length() - 1).trim();
        }
        return r;
    }

    /**
     * 给规则 SQL 中未带别名的列名补上 {@code alias.}，避免主从表同名字段歧义。
     * 已有 {@code x.col}、函数名、SQL 关键字、字符串字面量不处理。
     */
    public static String qualifyUnaliasedColumns(String sql, String alias) {
        if (Strings.isBlank(sql) || Strings.isBlank(alias)) {
            return sql;
        }
        String a = alias.trim();
        StringBuilder out = new StringBuilder(sql.length() + 16);
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\'') {
                out.append(c);
                i++;
                while (i < n) {
                    char q = sql.charAt(i);
                    out.append(q);
                    i++;
                    if (q == '\'') {
                        if (i < n && sql.charAt(i) == '\'') {
                            out.append('\'');
                            i++;
                        } else {
                            break;
                        }
                    }
                }
                continue;
            }
            if (c == '"') {
                out.append(c);
                i++;
                while (i < n) {
                    char q = sql.charAt(i);
                    out.append(q);
                    i++;
                    if (q == '"') {
                        break;
                    }
                }
                continue;
            }
            if (isIdentStart(c)) {
                int start = i;
                i++;
                while (i < n && isIdentPart(sql.charAt(i))) {
                    i++;
                }
                String ident = sql.substring(start, i);
                int prev = previousNonWs(sql, start - 1);
                int next = nextNonWs(sql, i);
                boolean afterDot = prev >= 0 && sql.charAt(prev) == '.';
                boolean beforeDot = next < n && sql.charAt(next) == '.';
                boolean beforeParen = next < n && sql.charAt(next) == '(';
                boolean keyword = SQL_WORDS.contains(ident.toLowerCase(Locale.ROOT));
                if (!afterDot && !beforeDot && !beforeParen && !keyword) {
                    out.append(a).append('.');
                }
                out.append(ident);
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static boolean isIdentStart(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    private static boolean isIdentPart(char c) {
        return isIdentStart(c) || (c >= '0' && c <= '9') || c == '_' || c == '#' || c == '$';
    }

    private static int previousNonWs(String s, int from) {
        for (int i = from; i >= 0; i--) {
            if (!Character.isWhitespace(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static int nextNonWs(String s, int from) {
        int n = s.length();
        for (int i = from; i < n; i++) {
            if (!Character.isWhitespace(s.charAt(i))) {
                return i;
            }
        }
        return n;
    }
}
