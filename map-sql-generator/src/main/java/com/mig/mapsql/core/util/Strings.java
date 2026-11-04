package com.mig.mapsql.core.util;

public final class Strings {

    private Strings() {
    }

    public static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    public static String trimToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    public static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    public static String upper(String s) {
        return s == null ? "" : s.trim().toUpperCase();
    }
}
