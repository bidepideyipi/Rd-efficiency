package com.mig.mapsql.standard;

import com.mig.mapsql.catalog.DbColumnMeta;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlterSqlBuilderTest {

    @Test
    void rebuildEmitsFourLogicalSteps() {
        DbColumnMeta col = new DbColumnMeta();
        col.setColumnName("STATUS");
        col.setDataType("CHAR");
        col.setExcelLength("2");
        col.setComment("状态");

        AlterSqlBuilder.RebuildStep[] steps = AlterSqlBuilder.buildFourSteps(
                "T_ORDER", "STATUS", "状态", "CHAR(2)", "VARCHAR2(2)");
        assertEquals(4, steps.length);
        assertEquals(1, steps[0].index);
        assertTrue(steps[0].sqlStatements[0].contains("ADD (STATUS_MIGTMP VARCHAR2(2))"));
        assertTrue(steps[1].sqlStatements[0].startsWith("COMMENT ON COLUMN"));
        assertTrue(steps[1].sqlStatements[0].contains("'状态'"));
        assertTrue(steps[2].sqlStatements[0].contains("RTRIM(STATUS)"));
        assertEquals(2, steps[3].sqlStatements.length);
        assertTrue(steps[3].sqlStatements[0].contains("DROP COLUMN STATUS"));
        assertTrue(steps[3].sqlStatements[1].contains("RENAME COLUMN STATUS_MIGTMP TO STATUS"));

        List<String> script = AlterSqlBuilder.buildScriptLines(
                "T_ORDER", col, "CHAR(2)", "VARCHAR2(2)", true);
        String joined = String.join("\n", script);
        assertTrue(joined.contains("四步重建"));
        assertTrue(joined.contains("ADD (STATUS_MIGTMP"));
        assertTrue(joined.contains("DROP COLUMN STATUS"));
        assertTrue(joined.contains("RENAME COLUMN STATUS_MIGTMP TO STATUS"));
    }

    @Test
    void clobUsesDbmsLobSubstr() {
        AlterSqlBuilder.RebuildStep[] steps = AlterSqlBuilder.buildFourSteps(
                "T_DOC", "CONTENT", "正文", "CLOB", "VARCHAR2(400)");
        assertTrue(steps[2].sqlStatements[0].contains("DBMS_LOB.SUBSTR(CONTENT, 400, 1)"));
    }

    @Test
    void modifyPathWhenNotRebuild() {
        DbColumnMeta col = new DbColumnMeta();
        col.setColumnName("NAME");
        col.setDataType("VARCHAR2");
        col.setExcelLength("50");
        List<String> lines = AlterSqlBuilder.buildScriptLines(
                "T_USER", col, "VARCHAR2(50)", "VARCHAR2(100)", false);
        assertEquals(2, lines.size());
        assertTrue(lines.get(1).contains("MODIFY (NAME VARCHAR2(100))"));
    }

    @Test
    void needsRebuildForCharClobNumber() {
        assertTrue(StandardScanService.needsRebuild("CHAR(10)", "VARCHAR2(10)"));
        assertTrue(StandardScanService.needsRebuild("CLOB", "VARCHAR2(400)"));
        assertTrue(StandardScanService.needsRebuild("NUMBER(10,2)", "NUMBER(22,2)"));
    }
}
