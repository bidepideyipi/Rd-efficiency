package com.mig.mapsql.core.util;

import java.util.Locale;
import java.util.regex.Pattern;

public final class SqlRuleHeuristics {

    private static final Pattern SQL_HINT = Pattern.compile(
            "(?i)(case\\s+when|substr\\s*\\(|decode\\s*\\(|nvl\\s*\\(|to_char\\s*\\(|to_number\\s*\\(|to_date\\s*\\(|\\|\\||\\()");

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
}
