package com.mig.mapsql.core.excel;

import com.mig.mapsql.config.GeneratorProperties;
import com.mig.mapsql.core.model.FieldMapping;
import com.mig.mapsql.core.model.LeftJoinSpec;
import com.mig.mapsql.core.model.MappingType;
import com.mig.mapsql.core.model.TableMapping;
import com.mig.mapsql.core.util.JoinConditionParser;
import com.mig.mapsql.core.util.SheetNames;
import com.mig.mapsql.core.util.Strings;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class ExcelMappingReader {

    private static final Set<String> SKIP_SHEETS = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList("封面", "文档说明", "目录", "修订记录")));

    static {
        // 部分映射表的 xl/styles.xml 压缩比约 0.01，刚好低于 POI 默认阈值 0.01，会被当成 zip bomb。
        ZipSecureFile.setMinInflateRatio(0.001d);
    }

    private final GeneratorProperties properties;
    private final DataFormatter formatter = new DataFormatter();

    public ExcelMappingReader(GeneratorProperties properties) {
        this.properties = properties;
    }

    public List<String> listBusinessSheets(Path excelPath) throws IOException {
        try (InputStream in = Files.newInputStream(excelPath); Workbook wb = new XSSFWorkbook(in)) {
            List<String> names = new ArrayList<String>();
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                String name = wb.getSheetName(i);
                if (isBusinessSheet(name)) {
                    names.add(name);
                }
            }
            return names;
        }
    }

    public boolean isBusinessSheet(String name) {
        if (Strings.isBlank(name)) {
            return false;
        }
        String n = name.trim();
        if (SKIP_SHEETS.contains(n)) {
            return false;
        }
        if (n.contains("说明")) {
            return false;
        }
        return true;
    }

    public List<TableMapping> readTables(Path excelPath, List<String> tableFilter) throws IOException {
        try (InputStream in = Files.newInputStream(excelPath); Workbook wb = new XSSFWorkbook(in)) {
            List<TableMapping> result = new ArrayList<TableMapping>();
            Set<String> filter = tableFilter == null || tableFilter.isEmpty()
                    ? null
                    : new LinkedHashSet<String>(tableFilter);
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                Sheet sheet = wb.getSheetAt(i);
                String name = sheet.getSheetName();
                if (!isBusinessSheet(name)) {
                    continue;
                }
                if (filter != null && !filter.contains(name)) {
                    continue;
                }
                result.add(readSheet(sheet));
            }
            return result;
        }
    }

    public TableMapping readSheet(Sheet sheet) {
        TableMapping table = new TableMapping();
        table.setSheetName(sheet.getSheetName());
        // 物理表名：优先 Excel 列 B；否则 Sheet 名去末位数字（与对比/装载一致）
        table.setTargetTable(null);

        int headerRowIdx = properties.getHeaderRow() - 1;
        Map<String, String> joinCandidates = new LinkedHashMap<String, String>();

        for (int r = headerRowIdx + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            String seq = cell(row, 0);
            if (Strings.isBlank(seq)) {
                continue;
            }
            FieldMapping field = new FieldMapping();
            field.setExcelRow(r + 1);
            field.setSeq(seq);
            field.setTargetTable(cell(row, 1));
            field.setTargetColumn(cell(row, 2));
            field.setTargetComment(cell(row, 3));
            field.setNullable(cell(row, 4));
            field.setTargetType(cell(row, 5));
            field.setTargetLength(cell(row, 6));
            field.setMappingTypeRaw(cell(row, 11));
            field.setMappingType(MappingType.fromLabel(field.getMappingTypeRaw()));
            field.setSourceSystem(cell(row, 12));
            field.setSourceTable(cell(row, 13));
            field.setSourceColumn(cell(row, 14));
            field.setSourceType(cell(row, 16));
            field.setSourceLength(cell(row, 17));
            field.setDefaultValue(cell(row, 18));
            field.setRuleExpression(cell(row, 21));
            field.setJoinCondition(cell(row, 22));
            field.setRemark(cell(row, 23));
            table.getFields().add(field);

            if (!Strings.isBlank(field.getJoinCondition())) {
                joinCandidates.put(JoinConditionParser.normalizeKey(field.getJoinCondition()),
                        field.getJoinCondition().trim());
            }
            if (Strings.isBlank(table.getSourceSystem()) && !Strings.isBlank(field.getSourceSystem())) {
                table.setSourceSystem(field.getSourceSystem().trim());
            }
            if (Strings.isBlank(table.getTargetTable()) && !Strings.isBlank(field.getTargetTable())) {
                table.setTargetTable(field.getTargetTable().trim());
            }
        }

        if (Strings.isBlank(table.getTargetTable())) {
            table.setTargetTable(SheetNames.resolveTableName(table.getSheetName()));
        }

        finalizeRelations(table, new ArrayList<String>(joinCandidates.values()));
        return table;
    }

    void finalizeRelations(TableMapping table, List<String> joinRaws) {
        String primaryAlias = properties.getSourceAlias();

        if (joinRaws != null && !joinRaws.isEmpty()) {
            table.setJoinConditionRaw(String.join(" | ", joinRaws));
        }

        JoinConditionParser.RelationParseResult parsed =
                JoinConditionParser.parseAll(joinRaws, primaryAlias);

        // 优先采用「select * from X」中的主表；否则按字段出现频次
        String primary = preferFromTableAsPrimary(table, parsed);
        if (Strings.isBlank(primary)) {
            primary = detectPrimarySourceTable(table);
        }
        table.setSourceTable(primary);

        if (!Strings.isBlank(primary)) {
            table.getTableAliases().put(primary.toUpperCase(Locale.ROOT), primaryAlias);
        }

        table.setJoinPredicate(parsed.getWherePredicate());
        table.setWhereConflict(parsed.isWhereConflict());
        table.setLeftJoinConflict(parsed.isLeftJoinConflict());
        table.getLeftJoins().clear();
        table.getLeftJoins().addAll(parsed.getLeftJoins());
        table.getUnsupportedJoins().clear();
        table.getUnsupportedJoins().addAll(parsed.getUnsupportedJoins());
        table.getRelationNotes().clear();
        table.getRelationNotes().addAll(parsed.getNotes());

        for (LeftJoinSpec lj : table.getLeftJoins()) {
            // 同表多别名时保留首次别名作回退；字段渲染优先用本行关联条件中的别名
            String key = lj.getTableName().toUpperCase(Locale.ROOT);
            if (!table.getTableAliases().containsKey(key)) {
                table.getTableAliases().put(key, lj.getAlias());
            }
        }
    }

    /**
     * 若关联条件写了 select * from X，且 X 也出现在字段源表中，则 X 作为主表。
     */
    private String preferFromTableAsPrimary(TableMapping table,
                                            JoinConditionParser.RelationParseResult parsed) {
        if (parsed.getWhereSourceTables().isEmpty()) {
            return null;
        }
        Set<String> fieldTables = new HashSet<String>();
        Map<String, String> canonical = new HashMap<String, String>();
        for (FieldMapping f : table.getFields()) {
            if (f.getMappingType() == MappingType.SKIP || Strings.isBlank(f.getSourceTable())) {
                continue;
            }
            String raw = f.getSourceTable().trim();
            String key = raw.toUpperCase(Locale.ROOT);
            fieldTables.add(key);
            canonical.put(key, raw);
        }
        for (String fromTable : parsed.getWhereSourceTables()) {
            if (Strings.isBlank(fromTable)) {
                continue;
            }
            String key = fromTable.trim().toUpperCase(Locale.ROOT);
            if (fieldTables.contains(key)) {
                return canonical.get(key);
            }
        }
        // 字段里没有同名时，仍采用 select * from 的表名（原文大小写）
        return parsed.getWhereSourceTables().get(0);
    }

    private String detectPrimarySourceTable(TableMapping table) {
        Map<String, Integer> counts = new HashMap<String, Integer>();
        Map<String, String> canonical = new HashMap<String, String>();
        for (FieldMapping f : table.getFields()) {
            if (f.getMappingType() == MappingType.SKIP) {
                continue;
            }
            if (Strings.isBlank(f.getSourceTable())) {
                continue;
            }
            String raw = f.getSourceTable().trim();
            String key = raw.toUpperCase(Locale.ROOT);
            canonical.put(key, raw);
            Integer c = counts.get(key);
            counts.put(key, c == null ? 1 : c + 1);
        }
        String bestKey = null;
        int best = -1;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > best) {
                best = e.getValue();
                bestKey = e.getKey();
            }
        }
        return bestKey == null ? null : canonical.get(bestKey);
    }

    private String cell(Row row, int idx) {
        Cell cell = row.getCell(idx);
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() == CellType.FORMULA) {
            return Strings.trimToEmpty(formatter.formatCellValue(cell));
        }
        return Strings.trimToEmpty(formatter.formatCellValue(cell));
    }
}
