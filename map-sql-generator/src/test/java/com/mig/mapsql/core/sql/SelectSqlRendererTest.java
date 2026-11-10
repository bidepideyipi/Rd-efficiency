package com.mig.mapsql.core.sql;

import com.mig.mapsql.config.GeneratorProperties;
import com.mig.mapsql.core.model.FieldMapping;
import com.mig.mapsql.core.model.MappingType;
import com.mig.mapsql.core.model.TableMapping;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectSqlRendererTest {

    @Test
    void rendersRuleTypeLengthAndJoin() {
        GeneratorProperties props = new GeneratorProperties();
        SelectSqlRenderer renderer = new SelectSqlRenderer(props);

        TableMapping table = new TableMapping();
        table.setSheetName("IBPS_PAYMENTINIT");
        table.setTargetTable("IBPS_PAYMENTINIT");
        table.setSourceTable("A10TIBPSDETAILLOG");
        table.setJoinPredicate("FUNCTION2 IN ('ibps.101.001.01')");

        FieldMapping direct = field("AGENTSERIALNO", MappingType.DIRECT, "TRACE", "VARCHAR2", "16", "VARCHAR2", "22", null);
        FieldMapping rule = field("MBFLAG", MappingType.CODE_CONVERT, "FLAG1", "VARCHAR2", "1", "VARCHAR2", "1",
                "case when flag1='0' then '1' else '2' end");
        FieldMapping overflow = field("MSGTYPE", MappingType.DIRECT, "FUNCTION2", "VARCHAR2", "20", "VARCHAR2", "15", null);
        FieldMapping varcharToChar = field("FLAG", MappingType.DIRECT, "FLAG1", "VARCHAR2", "1", "CHAR", "1", null);
        FieldMapping amount = field("AMOUNT", MappingType.TYPE_CONVERT, "AMOUNT", "VARCHAR2", "15", "NUMBER", "22,2", null);
        FieldMapping def = field("CLEARBRNO", MappingType.DEFAULT_VALUE, null, null, null, "VARCHAR2", "6", null);
        def.setDefaultValue("800001");
        FieldMapping skip = field("WORKDATE", MappingType.SKIP, "TRANDATE", "VARCHAR2", "8", "VARCHAR2", "8", null);

        table.getFields().add(direct);
        table.getFields().add(rule);
        table.getFields().add(overflow);
        table.getFields().add(varcharToChar);
        table.getFields().add(amount);
        table.getFields().add(def);
        table.getFields().add(skip);

        String sql = renderer.render(table);
        assertTrue(sql.contains("(s.TRACE) AS AGENTSERIALNO"));
        assertTrue(sql.contains("case when flag1='0' then '1' else '2' end"));
        assertTrue(sql.contains("CASE WHEN LENGTH(s.FUNCTION2) > 15 THEN SUBSTR(s.FUNCTION2, 1, 15) ELSE s.FUNCTION2 END"));
        assertTrue(sql.contains("(s.FLAG1) AS FLAG"));
        assertTrue(!sql.contains("TO_CHAR(s.FLAG1)"));
        assertTrue(sql.contains("TO_NUMBER(TRIM(s.AMOUNT))"));
        assertTrue(sql.contains("('800001') AS CLEARBRNO"));
        assertTrue(!sql.contains("WORKDATE"));
        assertTrue(sql.contains("{{beginTm}}"));
        assertTrue(sql.contains("{{endTm}}"));
        assertTrue(sql.contains("{{tmColumn}}"));
        assertTrue(sql.contains("FUNCTION2 IN ('ibps.101.001.01')"));
    }

    @Test
    void noBusinessWhereOmitsTimeShardAndSelectAnd() {
        GeneratorProperties props = new GeneratorProperties();
        SelectSqlRenderer renderer = new SelectSqlRenderer(props);

        TableMapping table = new TableMapping();
        table.setSheetName("IBPS_MOBILEINFO");
        table.setTargetTable("IBPS_MOBILEINFO");
        table.setSourceTable("IBPS_MOBILEINFO");
        // 无业务 WHERE（对应 Excel 仅写 select * from …）
        table.setJoinPredicate(null);

        FieldMapping direct = field("ID", MappingType.DIRECT, "ID", "VARCHAR2", "32", "VARCHAR2", "32", null);
        table.getFields().add(direct);

        String sql = renderer.render(table);
        assertTrue(sql.contains("FROM IBPS_MOBILEINFO s"));
        assertTrue(!sql.contains("WHERE"));
        assertTrue(!sql.contains("{{tmColumn}}"));
        assertTrue(!sql.contains("{{beginTm}}"));
        assertTrue(!sql.toLowerCase().contains("select * from"));
    }

    private static FieldMapping field(String tgt, MappingType type, String src,
                                      String srcType, String srcLen, String tgtType, String tgtLen, String rule) {
        FieldMapping f = new FieldMapping();
        f.setTargetColumn(tgt);
        f.setMappingType(type);
        f.setMappingTypeRaw(type.getLabel());
        f.setSourceColumn(src);
        f.setSourceType(srcType);
        f.setSourceLength(srcLen);
        f.setTargetType(tgtType);
        f.setTargetLength(tgtLen);
        f.setRuleExpression(rule);
        f.setSeq("1");
        f.setTargetComment(tgt);
        return f;
    }
}
