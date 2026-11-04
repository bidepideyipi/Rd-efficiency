package com.mig.mapsql.core.model;

public class LeftJoinSpec {

    private String tableName;
    private String alias;
    private String onPredicate;
    private String raw;

    public LeftJoinSpec() {
    }

    public LeftJoinSpec(String tableName, String alias, String onPredicate, String raw) {
        this.tableName = tableName;
        this.alias = alias;
        this.onPredicate = onPredicate;
        this.raw = raw;
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
}
