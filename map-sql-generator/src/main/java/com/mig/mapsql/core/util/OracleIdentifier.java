package com.mig.mapsql.core.util;

import java.util.regex.Pattern;

public final class OracleIdentifier {

    private static final Pattern IDENT = Pattern.compile("^[A-Za-z][A-Za-z0-9_#$]*$");

    private OracleIdentifier() {
    }

    public static boolean isValid(String name) {
        return !Strings.isBlank(name) && IDENT.matcher(name.trim()).matches();
    }

    public static boolean isSafeFileName(String name) {
        if (Strings.isBlank(name)) {
            return false;
        }
        String n = name.trim();
        return !n.contains("/") && !n.contains("\\") && !n.contains("..")
                && !n.contains(":") && IDENT.matcher(n).matches();
    }
}
