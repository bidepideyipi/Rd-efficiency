package com.mig.mapsql.catalog;

import com.mig.mapsql.standard.StandardRule;
import com.mig.mapsql.standard.StandardScanService;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DbDbDiffServiceTest {

    @Test
    void addDropModifyAndComment() {
        StandardScanService standard = mock(StandardScanService.class);
        when(standard.resolveStandard(any(DbColumnMeta.class))).thenReturn(null);
        DbDbDiffService svc = new DbDbDiffService(mock(ObSchemaMetadataClient.class), standard);

        Map<String, DbColumnMeta> left = new LinkedHashMap<String, DbColumnMeta>();
        left.put("ID", col("ID", "VARCHAR2", "32", "N", "主键"));
        left.put("NAME", col("NAME", "VARCHAR2", "64", "Y", "名称"));
        left.put("WORKDATE", col("WORKDATE", "VARCHAR2", "8", "N", "平台日期"));

        Map<String, DbColumnMeta> right = new LinkedHashMap<String, DbColumnMeta>();
        right.put("ID", col("ID", "VARCHAR2", "32", "N", "主键"));
        right.put("WORKDATE", col("WORKDATE", "CHAR", "8", "N", "平台日期"));
        right.put("EXTRA", col("EXTRA", "VARCHAR2", "10", "Y", "仅右"));

        DbDbDiffService.TableDiff td = svc.diffColumns("DEMO", left, right);
        String sql = String.join("\n", td.lines);
        assertEquals(1, td.addCount);
        assertEquals(1, td.dropCount);
        assertEquals(1, td.modifyCount);
        assertTrue(sql.contains("ADD (NAME VARCHAR2(64)"));
        assertTrue(sql.contains("DROP COLUMN EXTRA"));
        assertTrue(sql.contains("basis=LEFT"));
        assertTrue(sql.contains("WORKDATE"));
    }

    @Test
    void typeDiffPrefersStandardOverLeft() {
        StandardScanService standard = mock(StandardScanService.class);
        StandardRule rule = new StandardRule();
        rule.setRuleType("TYPE");
        rule.setMatchExpr("CHAR");
        rule.setTargetType("VARCHAR2({n})");
        rule.setRebuild(false);
        when(standard.resolveStandard(any(DbColumnMeta.class)))
                .thenReturn(new StandardScanService.Match(rule, "VARCHAR2(8)"));

        DbDbDiffService svc = new DbDbDiffService(mock(ObSchemaMetadataClient.class), standard);

        Map<String, DbColumnMeta> left = new LinkedHashMap<String, DbColumnMeta>();
        left.put("WORKDATE", col("WORKDATE", "CHAR", "8", "N", "平台日期"));
        Map<String, DbColumnMeta> right = new LinkedHashMap<String, DbColumnMeta>();
        right.put("WORKDATE", col("WORKDATE", "CHAR", "10", "N", "平台日期"));

        DbDbDiffService.TableDiff td = svc.diffColumns("T", left, right);
        String sql = String.join("\n", td.lines);
        assertEquals(1, td.modifyCount);
        assertTrue(sql.contains("basis=STANDARD:TYPE/CHAR"));
        assertTrue(sql.contains("VARCHAR2(8)"));
    }

    @Test
    void sameTypeOnlyCommentUsesLeft() {
        StandardScanService standard = mock(StandardScanService.class);
        when(standard.resolveStandard(any(DbColumnMeta.class))).thenReturn(null);
        DbDbDiffService svc = new DbDbDiffService(mock(ObSchemaMetadataClient.class), standard);

        Map<String, DbColumnMeta> left = new LinkedHashMap<String, DbColumnMeta>();
        left.put("FLAG", col("FLAG", "VARCHAR2", "4", "Y", "左注释"));
        Map<String, DbColumnMeta> right = new LinkedHashMap<String, DbColumnMeta>();
        right.put("FLAG", col("FLAG", "VARCHAR2", "4", "Y", "右注释"));

        DbDbDiffService.TableDiff td = svc.diffColumns("T", left, right);
        assertEquals(0, td.modifyCount);
        assertEquals(1, td.commentCount);
        String sql = String.join("\n", td.lines);
        assertTrue(sql.contains("COMMENT ON COLUMN T.FLAG IS '左注释'"));
        assertFalse(sql.contains("MODIFY"));
    }

    @Test
    void createTableEmitsColumnsAndComments() {
        Map<String, DbColumnMeta> cols = new LinkedHashMap<String, DbColumnMeta>();
        cols.put("A", col("A", "VARCHAR2", "10", "N", "甲"));
        cols.put("B", col("B", "NUMBER", "22,2", "Y", null));
        List<String> lines = DbDbDiffService.buildCreateTable("T_NEW", cols);
        String sql = String.join("\n", lines);
        assertTrue(sql.contains("CREATE TABLE T_NEW"));
        assertTrue(sql.contains("A VARCHAR2(10) NOT NULL"));
        assertTrue(sql.contains("B NUMBER(22,2)"));
        assertTrue(sql.contains("COMMENT ON COLUMN T_NEW.A IS '甲'"));
    }

    private static DbColumnMeta col(String name, String type, String len, String nullable, String comment) {
        DbColumnMeta m = new DbColumnMeta();
        m.setColumnName(name);
        m.setDataType(type);
        m.setExcelType(type);
        m.setExcelLength(len);
        m.setNullable(nullable);
        m.setComment(comment);
        return m;
    }
}
