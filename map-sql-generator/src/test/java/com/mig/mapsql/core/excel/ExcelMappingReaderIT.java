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
}
