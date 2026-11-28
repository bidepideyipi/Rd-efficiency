package com.mig.mapsql.core.model;

public class ValidationMessage {

    public enum Level {
        ERROR, WARN, INFO
    }

    private final Level level;
    private final String table;
    private final Integer excelRow;
    /** Excel 表头名，如「规则说明」 */
    private final String excelColumnHeader;
    /** Excel 列字母，如 V */
    private final String excelColumnLetter;
    /** 目标栏位名（业务字段） */
    private final String targetField;
    private final String message;

    public ValidationMessage(Level level, String table, Integer excelRow, String targetField, String message) {
        this(level, table, excelRow, null, null, targetField, message);
    }

    public ValidationMessage(Level level, String table, Integer excelRow,
                             String excelColumnHeader, String excelColumnLetter,
                             String targetField, String message) {
        this.level = level;
        this.table = table;
        this.excelRow = excelRow;
        this.excelColumnHeader = excelColumnHeader;
        this.excelColumnLetter = excelColumnLetter;
        this.targetField = targetField;
        this.message = message;
    }

    public static ValidationMessage ofCell(Level level, String table, Integer excelRow,
                                           int excelColIndex, String excelColumnHeader,
                                           String targetField, String message) {
        return new ValidationMessage(level, table, excelRow, excelColumnHeader,
                ExcelColumns.letter(excelColIndex), targetField, message);
    }

    public Level getLevel() {
        return level;
    }

    public String getTable() {
        return table;
    }

    public Integer getExcelRow() {
        return excelRow;
    }

    public String getExcelColumnHeader() {
        return excelColumnHeader;
    }

    public String getExcelColumnLetter() {
        return excelColumnLetter;
    }

    public String getTargetField() {
        return targetField;
    }

    /** 兼容旧字段名：目标栏位 */
    public String getColumn() {
        return targetField;
    }

    public String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(level);
        if (table != null) {
            sb.append(" 表=").append(table);
        }
        if (excelRow != null || excelColumnLetter != null || excelColumnHeader != null) {
            sb.append(" Excel[");
            if (excelRow != null) {
                sb.append("行=").append(excelRow);
            }
            if (excelColumnLetter != null || excelColumnHeader != null) {
                if (excelRow != null) {
                    sb.append(", ");
                }
                sb.append("列=");
                if (excelColumnLetter != null) {
                    sb.append(excelColumnLetter);
                }
                if (excelColumnHeader != null) {
                    sb.append("(").append(excelColumnHeader).append(")");
                }
            }
            sb.append("]");
        }
        if (targetField != null && !targetField.isEmpty()) {
            sb.append(" 目标栏位=").append(targetField);
        }
        sb.append(": ").append(message);
        return sb.toString();
    }
}
