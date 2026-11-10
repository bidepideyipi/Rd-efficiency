package com.mig.mapsql.standard;

import com.mig.mapsql.catalog.DbColumnMeta;
import com.mig.mapsql.catalog.ObSchemaMetadataClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class StandardScanService {

    private final ObSchemaMetadataClient metadataClient;
    private final StandardRuleRepository ruleRepository;

    public StandardScanService(ObSchemaMetadataClient metadataClient,
                               StandardRuleRepository ruleRepository) {
        this.metadataClient = metadataClient;
        this.ruleRepository = ruleRepository;
    }

    public ScanResult scan(String datasourceId, List<String> tableFilter) {
        List<StandardRule> rules = ruleRepository.listEnabled();
        List<String> tables = metadataClient.listTables(datasourceId);
        if (tableFilter != null && !tableFilter.isEmpty()) {
            List<String> upper = new ArrayList<String>();
            for (String t : tableFilter) {
                if (t != null && !t.trim().isEmpty()) {
                    upper.add(t.trim().toUpperCase(Locale.ROOT));
                }
            }
            List<String> filtered = new ArrayList<String>();
            for (String t : tables) {
                if (upper.contains(t.toUpperCase(Locale.ROOT))) {
                    filtered.add(t);
                }
            }
            tables = filtered;
        }

        String owner = metadataClient.resolveOwner(datasourceId);
        List<ColumnHit> hits = new ArrayList<ColumnHit>();
        List<String> script = new ArrayList<String>();
        script.add("-- data standard scan");
        script.add("-- datasource=" + datasourceId + " owner=" + owner);
        script.add("-- tables=" + tables.size() + " rules=" + rules.size());
        script.add("");

        for (String table : tables) {
            Map<String, DbColumnMeta> cols = metadataClient.describeColumns(datasourceId, table);
            boolean tableHeader = false;
            for (DbColumnMeta col : cols.values()) {
                Match match = matchRule(col, rules);
                if (match == null) {
                    continue;
                }
                String current = AlterSqlBuilder.typeDisplay(col);
                if (typesEqual(current, match.targetType)) {
                    continue;
                }
                boolean rebuild = match.rule.isRebuild() || needsRebuild(current, match.targetType);
                if (!tableHeader) {
                    script.add("-- ========== " + table + " ==========");
                    tableHeader = true;
                }
                List<String> lines = AlterSqlBuilder.buildScriptLines(
                        table, col, current, match.targetType, rebuild);
                script.addAll(lines);
                script.add("");

                ColumnHit hit = new ColumnHit();
                hit.tableName = table;
                hit.columnName = col.getColumnName();
                hit.currentType = current;
                hit.targetType = match.targetType;
                hit.rebuild = rebuild;
                hit.ruleId = match.rule.getId();
                hit.ruleType = match.rule.getRuleType();
                hit.matchExpr = match.rule.getMatchExpr();
                hit.comment = col.getComment();
                hit.sqlLines = lines;
                hits.add(hit);
            }
        }

        ScanResult result = new ScanResult();
        result.datasourceId = datasourceId;
        result.owner = owner;
        result.tablesScanned = tables.size();
        result.hitCount = hits.size();
        result.hits = hits;
        result.sqlScript = join(script);
        return result;
    }

    private static Match matchRule(DbColumnMeta col, List<StandardRule> rules) {
        String colName = col.getColumnName() == null ? "" : col.getColumnName().toUpperCase(Locale.ROOT);
        String current = AlterSqlBuilder.typeDisplay(col);
        ColumnTypeParser.ParsedType parsed = ColumnTypeParser.parse(current);

        // COLUMN 规则优先（列表已按 priority 排序，但仍先扫 COLUMN）
        for (StandardRule rule : rules) {
            if (!"COLUMN".equalsIgnoreCase(rule.getRuleType())) {
                continue;
            }
            if (colName.equals(rule.getMatchExpr().trim().toUpperCase(Locale.ROOT))) {
                String target = ColumnTypeParser.renderTarget(rule.getTargetType(), parsed);
                return new Match(rule, target);
            }
        }
        for (StandardRule rule : rules) {
            if (!"TYPE".equalsIgnoreCase(rule.getRuleType())) {
                continue;
            }
            ColumnTypeParser.ParsedType want = ColumnTypeParser.parse(rule.getMatchExpr());
            if (!parsed.family.equalsIgnoreCase(want.family)) {
                continue;
            }
            String target = ColumnTypeParser.renderTarget(rule.getTargetType(), parsed);
            return new Match(rule, target);
        }
        return null;
    }

    /**
     * CHAR / CLOB / NUMBER 族之间或向异构类型变更时，不能直接 MODIFY，必须重建。
     */
    static boolean needsRebuild(String currentType, String targetType) {
        ColumnTypeParser.ParsedType src = ColumnTypeParser.parse(currentType);
        ColumnTypeParser.ParsedType tgt = ColumnTypeParser.parse(targetType);
        if (src.family.equals(tgt.family) && "VARCHAR2".equals(src.family)) {
            // 同族 VARCHAR2 长度调整可尝试 MODIFY
            return false;
        }
        if ("CHAR".equals(src.family) || "CLOB".equals(src.family) || "NUMBER".equals(src.family)) {
            if (!src.family.equals(tgt.family)) {
                return true;
            }
            // NUMBER 精度变更、CHAR 长度变更：也走重建更稳妥
            return true;
        }
        if ("CHAR".equals(tgt.family) || "CLOB".equals(tgt.family) || "NUMBER".equals(tgt.family)) {
            if (!src.family.equals(tgt.family)) {
                return true;
            }
        }
        return !src.family.equals(tgt.family);
    }

    private static boolean typesEqual(String a, String b) {
        ColumnTypeParser.ParsedType pa = ColumnTypeParser.parse(a);
        ColumnTypeParser.ParsedType pb = ColumnTypeParser.parse(b);
        return pa.display().equalsIgnoreCase(pb.display());
    }

    private static String join(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    private static final class Match {
        final StandardRule rule;
        final String targetType;

        Match(StandardRule rule, String targetType) {
            this.rule = rule;
            this.targetType = targetType;
        }
    }

    public static class ScanResult {
        public String datasourceId;
        public String owner;
        public int tablesScanned;
        public int hitCount;
        public List<ColumnHit> hits;
        public String sqlScript;
    }

    public static class ColumnHit {
        public String tableName;
        public String columnName;
        public String currentType;
        public String targetType;
        public boolean rebuild;
        public Long ruleId;
        public String ruleType;
        public String matchExpr;
        public String comment;
        public List<String> sqlLines;

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("tableName", tableName);
            m.put("columnName", columnName);
            m.put("currentType", currentType);
            m.put("targetType", targetType);
            m.put("rebuild", rebuild);
            m.put("ruleId", ruleId);
            m.put("ruleType", ruleType);
            m.put("matchExpr", matchExpr);
            m.put("comment", comment);
            m.put("sqlLines", sqlLines);
            return m;
        }
    }
}
