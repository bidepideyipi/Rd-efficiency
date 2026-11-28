package com.mig.mapsql.catalog;

import java.util.ArrayList;
import java.util.List;

public class ExcelDbDiffResult {

    private int sheetsCompared;
    private int sheetsSkippedNoTable;
    private int typeUpdated;
    private int excelOnlyMarked;
    private int dbOnlyAppended;
    private final List<String> details = new ArrayList<String>();
    private byte[] workbookBytes;

    public int getSheetsCompared() {
        return sheetsCompared;
    }

    public void setSheetsCompared(int sheetsCompared) {
        this.sheetsCompared = sheetsCompared;
    }

    public int getSheetsSkippedNoTable() {
        return sheetsSkippedNoTable;
    }

    public void setSheetsSkippedNoTable(int sheetsSkippedNoTable) {
        this.sheetsSkippedNoTable = sheetsSkippedNoTable;
    }

    public int getTypeUpdated() {
        return typeUpdated;
    }

    public void setTypeUpdated(int typeUpdated) {
        this.typeUpdated = typeUpdated;
    }

    public int getExcelOnlyMarked() {
        return excelOnlyMarked;
    }

    public void setExcelOnlyMarked(int excelOnlyMarked) {
        this.excelOnlyMarked = excelOnlyMarked;
    }

    public int getDbOnlyAppended() {
        return dbOnlyAppended;
    }

    public void setDbOnlyAppended(int dbOnlyAppended) {
        this.dbOnlyAppended = dbOnlyAppended;
    }

    public List<String> getDetails() {
        return details;
    }

    public byte[] getWorkbookBytes() {
        return workbookBytes;
    }

    public void setWorkbookBytes(byte[] workbookBytes) {
        this.workbookBytes = workbookBytes;
    }
}
