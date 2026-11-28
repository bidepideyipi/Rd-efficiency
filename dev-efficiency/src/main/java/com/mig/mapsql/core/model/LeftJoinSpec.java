package com.mig.mapsql.core.model;

/**
 * 副表 JOIN 规格（LEFT / INNER）。类名沿用 LeftJoinSpec 以减少改动面。
 */
public class LeftJoinSpec {

    public static final String TYPE_LEFT = "LEFT";
    public static final String TYPE_INNER = "INNER";

    private String tableName;
    private String alias;
    private String onPredicate;
    private String raw;
    /** LEFT or INNER */
    private String joinType = TYPE_LEFT;

    public LeftJoinSpec() {
    }

    public LeftJoinSpec(String tableName, String alias, String onPredicate, String raw) {
        this(tableName, alias, onPredicate, raw, TYPE_LEFT);
    }

    public LeftJoinSpec(String tableName, String alias, String onPredicate, String raw, String joinType) {
        this.tableName = tableName;
        this.alias = alias;
        this.onPredicate = onPredicate;
        this.raw = raw;
        this.joinType = joinType == null || joinType.trim().isEmpty() ? TYPE_LEFT : joinType.trim().toUpperCase();
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    public String getOnPredicate() {
        return onPredicate;
    }

    public void setOnPredicate(String onPredicate) {
        this.onPredicate = onPredicate;
    }

    public String getRaw() {
        return raw;
    }

    public void setRaw(String raw) {
        this.raw = raw;
    }

    public String getJoinType() {
        return joinType;
    }

    public void setJoinType(String joinType) {
        this.joinType = joinType;
    }

    /** SQL 关键字，如 {@code LEFT JOIN} / {@code INNER JOIN} */
    public String sqlKeyword() {
        if (TYPE_INNER.equalsIgnoreCase(joinType)) {
            return "INNER JOIN";
        }
        return "LEFT JOIN";
    }
}
