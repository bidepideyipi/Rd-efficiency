package com.mig.mapsql.core.excel;

import com.mig.mapsql.config.GeneratorProperties;
import com.mig.mapsql.core.model.TableMapping;
import com.mig.mapsql.core.model.ValidationMessage;
import com.mig.mapsql.core.sql.SelectSqlRenderer;
import com.mig.mapsql.core.validate.MappingValidator;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExcelMappingReaderIT {

    @Test
    void generateIbpsPaymentInitFromRealExcel() throws Exception {
        Path excel = Paths.get("..").resolve("数据迁移中间表映射关系（跨行支付平台）V0.4.xlsx").toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.exists(excel), "标准 Excel 不在仓库根目录，跳过");

        GeneratorProperties props = new GeneratorProperties();
        props.setRequireSqlRuleWhenPresent(false);
        ExcelMappingReader reader = new ExcelMappingReader(props);
        MappingValidator validator = new MappingValidator(props);
        SelectSqlRenderer renderer = new SelectSqlRenderer(props);

        List<TableMapping> tables = reader.readTables(excel, Collections.singletonList("IBPS_PAYMENTINIT"));
        assertFalse(tables.isEmpty());
        TableMapping table = tables.get(0);
        List<ValidationMessage> messages = validator.validate(table);
        assertFalse(validator.hasError(messages), () -> messages.toString());

        String sql = renderer.render(table);
        assertTrue(sql.contains("FROM A10TIBPSDETAILLOG s"));
        assertTrue(sql.contains("{{tmColumn}}"));
        assertTrue(sql.contains("FUNCTION2 IN"));
        assertTrue(sql.contains("AS AMOUNT") || sql.contains("TO_NUMBER"));
    }

    @Test
    void generateHvpsPaymentInitWithLeftJoin() throws Exception {
        Path excel = Paths.get("..").resolve("数据迁移中间表映射关系（跨行支付平台）V0.4.xlsx").toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.exists(excel), "标准 Excel 不在仓库根目录，跳过");

        GeneratorProperties props = new GeneratorProperties();
        props.setRequireSqlRuleWhenPresent(false);
        ExcelMappingReader reader = new ExcelMappingReader(props);
        MappingValidator validator = new MappingValidator(props);
        SelectSqlRenderer renderer = new SelectSqlRenderer(props);

        List<TableMapping> tables = reader.readTables(excel, Collections.singletonList("HVPS_PAYMENTINIT"));
        assertFalse(tables.isEmpty());
        TableMapping table = tables.get(0);
        List<ValidationMessage> messages = validator.validate(table);
        assertFalse(validator.hasError(messages), () -> messages.toString());

        String sql = renderer.render(table);
        assertTrue(sql.toUpperCase().contains("FROM A08THVTRX"));
        assertTrue(sql.toUpperCase().contains("LEFT JOIN TA0LTXNSSNINFO"));
        assertTrue(sql.contains("ON "));
        assertTrue(sql.contains("cmtno") || sql.contains("CMTNO"));
        assertTrue(sql.contains("{{tmColumn}}"));
    }

    @Test
    void generateHvpsExtPaymentWithInnerJoin() throws Exception {
        Path excel = Paths.get("..").resolve("数据迁移中间表映射关系（跨行支付平台）V0.4.xlsx").toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.exists(excel), "标准 Excel 不在仓库根目录，跳过");

        GeneratorProperties props = new GeneratorProperties();
        props.setRequireSqlRuleWhenPresent(false);
        ExcelMappingReader reader = new ExcelMappingReader(props);
        SelectSqlRenderer renderer = new SelectSqlRenderer(props);

        List<TableMapping> tables = reader.readTables(excel, Collections.singletonList("HVPS_EXTPAYMENT"));
        assertFalse(tables.isEmpty());
        TableMapping table = tables.get(0);

        assertTrue("A08THVTRX".equalsIgnoreCase(table.getSourceTable()),
                "主表应为 select * from 中的 A08THVTRX，实际=" + table.getSourceTable());
        assertFalse(table.getLeftJoins().isEmpty(), "应解析到 JOIN");
        int innerCount = 0;
        java.util.Set<String> aliases = new java.util.HashSet<String>();
        for (com.mig.mapsql.core.model.LeftJoinSpec j : table.getLeftJoins()) {
            if ("INNER".equalsIgnoreCase(j.getJoinType())) {
                innerCount++;
            }
            aliases.add(j.getAlias());
            assertTrue(j.getOnPredicate().toLowerCase().contains("businesstrace"));
        }
        assertTrue(innerCount >= 3, "应有多条 INNER JOIN，实际=" + innerCount);
        assertEquals(table.getLeftJoins().size(), aliases.size(), "JOIN 别名应去重");

        // 用最小表验证 renderer 输出 INNER JOIN 关键字
        TableMapping mini = new TableMapping();
        mini.setSheetName("HVPS_EXTPAYMENT");
        mini.setTargetTable("HVPS_EXTPAYMENT");
        mini.setSourceTable(table.getSourceTable());
        mini.getLeftJoins().addAll(table.getLeftJoins());
        com.mig.mapsql.core.model.FieldMapping f = new com.mig.mapsql.core.model.FieldMapping();
        f.setTargetColumn("X");
        f.setSourceTable(table.getSourceTable());
        f.setSourceColumn("MAINSEQ");
        f.setMappingType(com.mig.mapsql.core.model.MappingType.DIRECT);
        mini.getFields().add(f);
        mini.getTableAliases().putAll(table.getTableAliases());

        String sql = renderer.render(mini);
        assertTrue(sql.toUpperCase().contains("FROM A08THVTRX"));
        assertTrue(sql.toUpperCase().contains("INNER JOIN"));
        assertFalse(sql.toUpperCase().contains("LEFT JOIN A08TAPPD"), "扩展表应为 INNER 而非 LEFT");
    }
}
