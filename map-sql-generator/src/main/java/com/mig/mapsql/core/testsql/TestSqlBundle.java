package com.mig.mapsql.core.testsql;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 单 Sheet 测试 SQL 渲染结果。 */
public class TestSqlBundle {

    private String sheet;
    private String targetTable;
    private String sourceTable;
    private List<String> primaryKeys;
    private List<String> hashColumns;
    private boolean amountCheck;
    private boolean timeWindow;
    private boolean pkResolved;
    private String pkError;
    private final Map<String, String> files = new LinkedHashMap<String, String>();

    public String getSheet() {
        return sheet;
    }

    public void setSheet(String sheet) {
        this.sheet = sheet;
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

    public List<String> getPrimaryKeys() {
        return primaryKeys;
    }

    public void setPrimaryKeys(List<String> primaryKeys) {
        this.primaryKeys = primaryKeys;
    }

    public List<String> getHashColumns() {
        return hashColumns;
    }

    public void setHashColumns(List<String> hashColumns) {
        this.hashColumns = hashColumns;
    }

    public boolean isAmountCheck() {
        return amountCheck;
    }

    public void setAmountCheck(boolean amountCheck) {
        this.amountCheck = amountCheck;
    }

    public boolean isTimeWindow() {
        return timeWindow;
    }

    public void setTimeWindow(boolean timeWindow) {
        this.timeWindow = timeWindow;
    }

    public boolean isPkResolved() {
        return pkResolved;
    }

    public void setPkResolved(boolean pkResolved) {
        this.pkResolved = pkResolved;
    }

    public String getPkError() {
        return pkError;
    }

    public void setPkError(String pkError) {
        this.pkError = pkError;
    }

    public Map<String, String> getFiles() {
        return files;
    }
}
