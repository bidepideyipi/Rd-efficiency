package com.mig.mapsql.errorcode;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 读取「厂商错误码映射」Sheet。
 */
public final class ErrorCodeExcelReader {

    public static final String SHEET_NAME = "厂商错误码映射";

    private static final DataFormatter FORMATTER = new DataFormatter();

    private ErrorCodeExcelReader() {
    }

    public static List<ErrorCodeEntry> read(InputStream in) throws IOException {
        try (Workbook wb = new XSSFWorkbook(in)) {
            Sheet sheet = wb.getSheet(SHEET_NAME);
            if (sheet == null) {
                if (wb.getNumberOfSheets() == 0) {
                    throw new IllegalArgumentException("Excel 无可用 Sheet");
                }
                sheet = wb.getSheetAt(0);
            }
            return readSheet(sheet);
        }
    }

    static List<ErrorCodeEntry> readSheet(Sheet sheet) {
        List<ErrorCodeEntry> rows = new ArrayList<ErrorCodeEntry>();
        if (sheet == null) {
            return rows;
        }
        int last = sheet.getLastRowNum();
        for (int r = 1; r <= last; r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            String fullCode = cell(row, 5);
            String message = cell(row, 6);
            if (isBlank(fullCode) && isBlank(message)) {
                continue;
            }
            ErrorCodeEntry e = new ErrorCodeEntry();
            e.setModule(cell(row, 0));
            e.setCategory(cell(row, 1));
            e.setVendorCode(cell(row, 2));
            e.setVendorConst(cell(row, 3));
            e.setSeqNo(parseInt(cell(row, 4)));
            e.setFullCode(fullCode);
            e.setMessage(message);
            rows.add(e);
        }
        return rows;
    }

    private static String cell(Row row, int col) {
        Cell c = row.getCell(col);
        if (c == null) {
            return "";
        }
        String v = FORMATTER.formatCellValue(c);
        return v == null ? "" : v.trim();
    }

    private static Integer parseInt(String s) {
        if (isBlank(s)) {
            return null;
        }
        try {
            if (s.contains(".")) {
                return (int) Double.parseDouble(s);
            }
            return Integer.parseInt(s);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
