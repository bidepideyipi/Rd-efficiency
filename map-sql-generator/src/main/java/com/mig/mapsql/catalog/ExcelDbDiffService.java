package com.mig.mapsql.catalog;

import com.mig.mapsql.config.GeneratorProperties;
import com.mig.mapsql.core.model.ExcelColumns;
import com.mig.mapsql.core.util.Strings;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 以库结构为准，回写映射 Excel 左侧目标表定义：
 * <ul>
 *   <li>类型/长度不同 → 改写并标黄</li>
 *   <li>库有 Excel 无 → 追加行</li>
 *   <li>Excel 有库无 → 标红，提醒人工删除</li>
 * </ul>
 */
@Service
public class ExcelDbDiffService {

    private static final Set<String> SKIP_SHEETS = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList("封面", "文档说明", "目录", "修订记录", "_DB_EXCEL_DIFF")));

    static {
        ZipSecureFile.setMinInflateRatio(0.001d);
    }

    private final GeneratorProperties properties;
    private final ObSchemaMetadataClient metadataClient;
    private final DataFormatter formatter = new DataFormatter();

    public ExcelDbDiffService(GeneratorProperties properties, ObSchemaMetadataClient metadataClient) {
        this.properties = properties;
        this.metadataClient = metadataClient;
    }

    public ExcelDbDiffResult compareAndPatch(String datasourceId, InputStream excelIn) throws Exception {
        ExcelDbDiffResult result = new ExcelDbDiffResult();
        Workbook wb = new XSSFWorkbook(excelIn);
        try {
            CellStyle yellow = createFill(wb, IndexedColors.YELLOW);
            CellStyle red = createFill(wb, IndexedColors.ROSE);

            int headerRowIdx = properties.getHeaderRow() - 1;
            for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                Sheet sheet = wb.getSheetAt(s);
                String sheetName = sheet.getSheetName();
                if (shouldSkip(sheetName)) {
                    continue;
                }
                // 多数据来源时 Sheet 名可能带编号后缀（如 IBPS_XXX1），末位数字去掉后才是库表名
                String tableName = resolveTableName(sheetName);
                if (!metadataClient.tableExists(datasourceId, tableName)) {
                    result.setSheetsSkippedNoTable(result.getSheetsSkippedNoTable() + 1);
                    String tip = sheetName.equals(tableName) ? sheetName : sheetName + " → " + tableName;
                    result.getDetails().add("SKIP 无库表: " + tip);
                    continue;
                }
                result.setSheetsCompared(result.getSheetsCompared() + 1);
                if (!sheetName.equals(tableName)) {
                    result.getDetails().add("SHEET " + sheetName + " 按表 " + tableName + " 对比");
                }
                patchSheet(sheet, tableName, datasourceId, headerRowIdx, yellow, red, result);
            }

            writeSummarySheet(wb, datasourceId, result);

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            result.setWorkbookBytes(bos.toByteArray());
            return result;
        } finally {
            wb.close();
        }
    }

    private void patchSheet(Sheet sheet, String tableName, String datasourceId, int headerRowIdx,
                            CellStyle yellow, CellStyle red, ExcelDbDiffResult result) {
        Map<String, DbColumnMeta> dbCols = metadataClient.describeColumns(datasourceId, tableName);
        Map<String, Integer> excelColRows = new LinkedHashMap<String, Integer>();

        int lastDataRow = headerRowIdx;
        int maxSeq = 0;
        for (int r = headerRowIdx + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            String seq = cellText(row, ExcelColumns.SEQ);
            String colName = cellText(row, ExcelColumns.TARGET_COLUMN);
            if (Strings.isBlank(seq) && Strings.isBlank(colName)) {
                continue;
            }
            lastDataRow = r;
            if (!Strings.isBlank(seq)) {
                try {
                    maxSeq = Math.max(maxSeq, Integer.parseInt(seq.trim()));
                } catch (NumberFormatException ignored) {
                    // ignore
                }
            }
            if (Strings.isBlank(colName)) {
                continue;
            }
            String key = colName.trim().toUpperCase(Locale.ROOT);
            excelColRows.put(key, r);

            DbColumnMeta db = dbCols.get(key);
            if (db == null) {
                // Excel 有、库无 → 红
                highlightField(row, red);
                appendRemark(row, "【库中不存在，请人工确认删除】");
                result.setExcelOnlyMarked(result.getExcelOnlyMarked() + 1);
                result.getDetails().add(tableName + "." + colName + " EXCEL_ONLY");
                continue;
            }

            String excelType = cellText(row, ExcelColumns.TARGET_TYPE);
            String excelLen = cellText(row, ExcelColumns.TARGET_LENGTH);
            if (!OracleTypeFormatter.typeSignatureEquals(excelType, excelLen, db.getExcelType(), db.getExcelLength())) {
                setCell(row, ExcelColumns.TARGET_TYPE, db.getExcelType());
                setCell(row, ExcelColumns.TARGET_LENGTH, db.getExcelLength());
                Cell t = row.getCell(ExcelColumns.TARGET_TYPE);
                Cell l = row.getCell(ExcelColumns.TARGET_LENGTH);
                if (t != null) {
                    t.setCellStyle(yellow);
                }
                if (l != null) {
                    l.setCellStyle(yellow);
                }
                result.setTypeUpdated(result.getTypeUpdated() + 1);
                result.getDetails().add(tableName + "." + colName + " TYPE "
                        + excelType + "(" + excelLen + ") -> "
                        + db.getExcelType() + "(" + db.getExcelLength() + ")");
            }
        }

        // 库有、Excel 无 → 追加
        for (Map.Entry<String, DbColumnMeta> e : dbCols.entrySet()) {
            if (excelColRows.containsKey(e.getKey())) {
                continue;
            }
            DbColumnMeta db = e.getValue();
            lastDataRow++;
            maxSeq++;
            Row row = sheet.createRow(lastDataRow);
            setCell(row, ExcelColumns.SEQ, String.valueOf(maxSeq));
            setCell(row, ExcelColumns.TARGET_TABLE, tableName);
            setCell(row, ExcelColumns.TARGET_COLUMN, db.getColumnName());
            setCell(row, ExcelColumns.TARGET_COMMENT, db.getComment() == null ? "" : db.getComment());
            setCell(row, ExcelColumns.NULLABLE, "Y".equalsIgnoreCase(db.getNullable()) ? "Y" : "N");
            setCell(row, ExcelColumns.TARGET_TYPE, db.getExcelType());
            setCell(row, ExcelColumns.TARGET_LENGTH, db.getExcelLength());
            setCell(row, ExcelColumns.MAPPING_TYPE, "01-直接迁移");
            setCell(row, 10, "【DB新增】"); // 目标侧「备注」
            result.setDbOnlyAppended(result.getDbOnlyAppended() + 1);
            result.getDetails().add(tableName + "." + db.getColumnName() + " DB_ONLY_APPENDED");
        }
    }

    private void writeSummarySheet(Workbook wb, String datasourceId, ExcelDbDiffResult result) {
        Sheet summary = wb.getSheet("_DB_EXCEL_DIFF");
        if (summary != null) {
            int idx = wb.getSheetIndex(summary);
            wb.removeSheetAt(idx);
        }
        summary = wb.createSheet("_DB_EXCEL_DIFF");
        int r = 0;
        row(summary, r++, "datasourceId", datasourceId);
        row(summary, r++, "sheetsCompared", String.valueOf(result.getSheetsCompared()));
        row(summary, r++, "sheetsSkippedNoTable", String.valueOf(result.getSheetsSkippedNoTable()));
        row(summary, r++, "typeUpdated(yellow)", String.valueOf(result.getTypeUpdated()));
        row(summary, r++, "excelOnly(red)", String.valueOf(result.getExcelOnlyMarked()));
        row(summary, r++, "dbOnlyAppended", String.valueOf(result.getDbOnlyAppended()));
        r++;
        row(summary, r++, "detail", "action");
        for (String d : result.getDetails()) {
            Row row = summary.createRow(r++);
            setCell(row, 0, d);
        }
    }

    private static void row(Sheet sheet, int r, String a, String b) {
        Row row = sheet.createRow(r);
        setCell(row, 0, a);
        setCell(row, 1, b);
    }

    private void highlightField(Row row, CellStyle style) {
        for (int c : new int[]{ExcelColumns.TARGET_COLUMN, ExcelColumns.TARGET_TYPE, ExcelColumns.TARGET_LENGTH}) {
            Cell cell = row.getCell(c);
            if (cell == null) {
                cell = row.createCell(c);
            }
            cell.setCellStyle(style);
        }
    }

    private void appendRemark(Row row, String note) {
        // 目标侧备注：表头第 11 列（0-based 10）
        String old = cellText(row, 10);
        if (old != null && old.contains(note)) {
            return;
        }
        setCell(row, 10, Strings.isBlank(old) ? note : old + " " + note);
    }

    private String cellText(Row row, int idx) {
        Cell cell = row.getCell(idx);
        if (cell == null) {
            return "";
        }
        return formatter.formatCellValue(cell).trim();
    }

    private static void setCell(Row row, int idx, String value) {
        Cell cell = row.getCell(idx);
        if (cell == null) {
            cell = row.createCell(idx);
        }
        cell.setCellValue(value == null ? "" : value);
    }

    private static CellStyle createFill(Workbook wb, IndexedColors color) {
        CellStyle style = wb.createCellStyle();
        style.setFillForegroundColor(color.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private static boolean shouldSkip(String sheetName) {
        if (sheetName == null) {
            return true;
        }
        String n = sheetName.trim();
        if (SKIP_SHEETS.contains(n)) {
            return true;
        }
        return n.contains("说明");
    }

    /**
     * Sheet 名 → 库表名。末字符为数字时去掉（多数据来源编号，暂不支持两位数）。
     * 例：IBPS_MOBILEINFO1 → IBPS_MOBILEINFO；IBPS_MOBILEINFO → 原样。
     */
    static String resolveTableName(String sheetName) {
        if (sheetName == null) {
            return "";
        }
        String n = sheetName.trim();
        if (n.isEmpty()) {
            return n;
        }
        char last = n.charAt(n.length() - 1);
        if (last >= '0' && last <= '9' && n.length() > 1) {
            return n.substring(0, n.length() - 1);
        }
        return n;
    }
}
