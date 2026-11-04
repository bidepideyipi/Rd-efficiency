package com.mig.mapsql.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mig")
public class GeneratorProperties {

    private String defaultExportDir = System.getProperty("user.home") + "/mig/sql/map";
    private String uploadTempDir = System.getProperty("java.io.tmpdir") + "/mapsql-upload";
    private int headerRow = 3;
    private String sourceAlias = "s";
    private String timePredicate = "string";
    private String tmFormat = "YYYYMMDDHH24MISS";
    private String dateFormat = "YYYYMMDDHH24MISS";
    /** substr | error | warn */
    private String lengthOverflow = "substr";
    private boolean includeHeaderComment = true;
    /** error | warn */
    private String joinConditionConflict = "error";
    private boolean requireSqlRuleWhenPresent = true;
    private boolean writeMeta = true;

    public String getDefaultExportDir() {
        return defaultExportDir;
    }

    public void setDefaultExportDir(String defaultExportDir) {
        this.defaultExportDir = defaultExportDir;
    }

    public String getUploadTempDir() {
        return uploadTempDir;
    }

    public void setUploadTempDir(String uploadTempDir) {
        this.uploadTempDir = uploadTempDir;
    }

    public int getHeaderRow() {
        return headerRow;
    }

    public void setHeaderRow(int headerRow) {
        this.headerRow = headerRow;
    }

    public String getSourceAlias() {
        return sourceAlias;
    }

    public void setSourceAlias(String sourceAlias) {
        this.sourceAlias = sourceAlias;
    }

    public String getTimePredicate() {
        return timePredicate;
    }

    public void setTimePredicate(String timePredicate) {
        this.timePredicate = timePredicate;
    }

    public String getTmFormat() {
        return tmFormat;
    }

    public void setTmFormat(String tmFormat) {
        this.tmFormat = tmFormat;
    }

    public String getDateFormat() {
        return dateFormat;
    }

    public void setDateFormat(String dateFormat) {
        this.dateFormat = dateFormat;
    }

    public String getLengthOverflow() {
        return lengthOverflow;
    }

    public void setLengthOverflow(String lengthOverflow) {
        this.lengthOverflow = lengthOverflow;
    }

    public boolean isIncludeHeaderComment() {
        return includeHeaderComment;
    }

    public void setIncludeHeaderComment(boolean includeHeaderComment) {
        this.includeHeaderComment = includeHeaderComment;
    }

    public String getJoinConditionConflict() {
        return joinConditionConflict;
    }

    public void setJoinConditionConflict(String joinConditionConflict) {
        this.joinConditionConflict = joinConditionConflict;
    }

    public boolean isRequireSqlRuleWhenPresent() {
        return requireSqlRuleWhenPresent;
    }

    public void setRequireSqlRuleWhenPresent(boolean requireSqlRuleWhenPresent) {
        this.requireSqlRuleWhenPresent = requireSqlRuleWhenPresent;
    }

    public boolean isWriteMeta() {
        return writeMeta;
    }

    public void setWriteMeta(boolean writeMeta) {
        this.writeMeta = writeMeta;
    }
}
