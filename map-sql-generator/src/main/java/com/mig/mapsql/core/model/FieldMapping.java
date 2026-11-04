package com.mig.mapsql.core.model;

public class FieldMapping {

    private int excelRow;
    private String seq;
    private String targetTable;
    private String targetColumn;
    private String targetComment;
    private String nullable;
    private String targetType;
    private String targetLength;
    private MappingType mappingType = MappingType.UNKNOWN;
    private String mappingTypeRaw;
    private String sourceSystem;
    private String sourceTable;
    private String sourceColumn;
    private String sourceType;
    private String sourceLength;
    private String defaultValue;
    private String ruleExpression;
    private String joinCondition;
    private String remark;

    public int getExcelRow() {
        return excelRow;
    }

    public void setExcelRow(int excelRow) {
        this.excelRow = excelRow;
    }

    public String getSeq() {
        return seq;
    }

    public void setSeq(String seq) {
        this.seq = seq;
    }

    public String getTargetTable() {
        return targetTable;
    }

    public void setTargetTable(String targetTable) {
        this.targetTable = targetTable;
    }

    public String getTargetColumn() {
        return targetColumn;
    }

    public void setTargetColumn(String targetColumn) {
        this.targetColumn = targetColumn;
    }

    public String getTargetComment() {
        return targetComment;
    }

    public void setTargetComment(String targetComment) {
        this.targetComment = targetComment;
    }

    public String getNullable() {
        return nullable;
    }

    public void setNullable(String nullable) {
        this.nullable = nullable;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public String getTargetLength() {
        return targetLength;
    }

    public void setTargetLength(String targetLength) {
        this.targetLength = targetLength;
    }

    public MappingType getMappingType() {
        return mappingType;
    }

    public void setMappingType(MappingType mappingType) {
        this.mappingType = mappingType;
    }

    public String getMappingTypeRaw() {
        return mappingTypeRaw;
    }

    public void setMappingTypeRaw(String mappingTypeRaw) {
        this.mappingTypeRaw = mappingTypeRaw;
    }

    public String getSourceSystem() {
        return sourceSystem;
    }

    public void setSourceSystem(String sourceSystem) {
        this.sourceSystem = sourceSystem;
    }

    public String getSourceTable() {
        return sourceTable;
    }

    public void setSourceTable(String sourceTable) {
        this.sourceTable = sourceTable;
    }

    public String getSourceColumn() {
        return sourceColumn;
    }

    public void setSourceColumn(String sourceColumn) {
        this.sourceColumn = sourceColumn;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getSourceLength() {
        return sourceLength;
    }

    public void setSourceLength(String sourceLength) {
        this.sourceLength = sourceLength;
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    public void setDefaultValue(String defaultValue) {
        this.defaultValue = defaultValue;
    }

    public String getRuleExpression() {
        return ruleExpression;
    }

    public void setRuleExpression(String ruleExpression) {
        this.ruleExpression = ruleExpression;
    }

    public String getJoinCondition() {
        return joinCondition;
    }

    public void setJoinCondition(String joinCondition) {
        this.joinCondition = joinCondition;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
