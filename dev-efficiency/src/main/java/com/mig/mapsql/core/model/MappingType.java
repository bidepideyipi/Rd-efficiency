package com.mig.mapsql.core.model;

import java.util.Locale;

public enum MappingType {
    SKIP("00-不迁移"),
    DIRECT("01-直接迁移"),
    DEFAULT_VALUE("02-默认值"),
    CODE_CONVERT("03-码值转换"),
    TYPE_CONVERT("04-类型转换"),
    TRANSFORM("05-加工映射"),
    UNKNOWN("");

    private final String label;

    MappingType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static MappingType fromLabel(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return UNKNOWN;
        }
        String t = raw.trim();
        for (MappingType type : values()) {
            if (type == UNKNOWN) {
                continue;
            }
            if (type.label.equals(t) || t.startsWith(type.label.substring(0, 2))) {
                return type;
            }
        }
        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.contains("不迁移")) {
            return SKIP;
        }
        if (lower.contains("直接")) {
            return DIRECT;
        }
        if (lower.contains("默认")) {
            return DEFAULT_VALUE;
        }
        if (lower.contains("码值")) {
            return CODE_CONVERT;
        }
        if (lower.contains("类型")) {
            return TYPE_CONVERT;
        }
        if (lower.contains("加工")) {
            return TRANSFORM;
        }
        return UNKNOWN;
    }
}
