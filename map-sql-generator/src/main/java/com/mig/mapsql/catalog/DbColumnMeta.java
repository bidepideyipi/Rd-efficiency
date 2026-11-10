package com.mig.mapsql.catalog;

/**
 * OceanBase Oracle 模式基表列元数据（对比用）。
 */
public class DbColumnMeta {

    private String tableName;
    private String columnName;
    private String dataType;
    private String excelType;
    private String excelLength;
    private String nullable;
    private String comment;
    private int columnId;

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getColumnName() {
        return columnName;
    }

    public void setColumnName(String columnName) {
        this.columnName = columnName;
    }

    public String getDataType() {
        return dataType;
    }

    public void setDataType(String dataType) {
        this.dataType = dataType;
    }

    public String getExcelType() {
        return excelType;
    }

    public void setExcelType(String excelType) {
        this.excelType = excelType;
    }

    public String getExcelLength() {
        return excelLength;
    }

    public void setExcelLength(String excelLength) {
        this.excelLength = excelLength;
    }

    public String getNullable() {
        return nullable;
    }

    public void setNullable(String nullable) {
        this.nullable = nullable;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public int getColumnId() {
        return columnId;
    }

    public void setColumnId(int columnId) {
        this.columnId = columnId;
    }
}
