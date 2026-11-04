package com.mig.mapsql.core.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TableMapping {

    private String sheetName;
    private String targetTable;
    /** 主源表（出现次数最多） */
    private String sourceTable;
    private String sourceSystem;
    /** 主表 WHERE 谓词（不含时间窗） */
    private String joinPredicate;
    private String joinConditionRaw;
    private boolean whereConflict;
    private boolean leftJoinConflict;
    private final List<LeftJoinSpec> leftJoins = new ArrayList<LeftJoinSpec>();
    private final List<String> unsupportedJoins = new ArrayList<String>();
    private final List<String> relationNotes = new ArrayList<String>();
    /** sourceTable(upper) -> alias */
    private final Map<String, String> tableAliases = new LinkedHashMap<String, String>();
    private final List<FieldMapping> fields = new ArrayList<FieldMapping>();

    public String getSheetName() {
        return sheetName;
    }

    public void setSheetName(String sheetName) {
        this.sheetName = sheetName;
    }

    public String getTargetTable() {
        return targetTable;
    }

    public void setTargetTable(String targetTable) {
        this.targetTable = targetTable;
    }

    public String getSourceTable() {
        return sourceTable;
    }

    public void setSourceTable(String sourceTable) {
        this.sourceTable = sourceTable;
    }

    public String getPrimarySourceTable() {
        return sourceTable;
    }

    public String getSourceSystem() {
        return sourceSystem;
    }

    public void setSourceSystem(String sourceSystem) {
        this.sourceSystem = sourceSystem;
    }

    public String getJoinPredicate() {
        return joinPredicate;
    }

    public void setJoinPredicate(String joinPredicate) {
        this.joinPredicate = joinPredicate;
    }

    public String getWherePredicate() {
        return joinPredicate;
    }

    public String getJoinConditionRaw() {
        return joinConditionRaw;
    }

    public void setJoinConditionRaw(String joinConditionRaw) {
        this.joinConditionRaw = joinConditionRaw;
    }

    public boolean isWhereConflict() {
        return whereConflict;
    }

    public void setWhereConflict(boolean whereConflict) {
        this.whereConflict = whereConflict;
    }

    public boolean isLeftJoinConflict() {
        return leftJoinConflict;
    }

    public void setLeftJoinConflict(boolean leftJoinConflict) {
        this.leftJoinConflict = leftJoinConflict;
    }

    public List<LeftJoinSpec> getLeftJoins() {
        return leftJoins;
    }

    public List<String> getUnsupportedJoins() {
        return unsupportedJoins;
    }

    public List<String> getRelationNotes() {
        return relationNotes;
    }

    public Map<String, String> getTableAliases() {
        return tableAliases;
    }

    public List<FieldMapping> getFields() {
        return fields;
    }

    public String resolveAlias(String sourceTableName, String primaryAlias) {
        if (sourceTableName == null || sourceTableName.trim().isEmpty()) {
            return primaryAlias;
        }
        String key = sourceTableName.trim().toUpperCase();
        String alias = tableAliases.get(key);
        return alias != null ? alias : primaryAlias;
    }
}
