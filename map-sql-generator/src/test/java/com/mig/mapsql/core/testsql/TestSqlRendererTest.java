package com.mig.mapsql.core.testsql;

import com.mig.mapsql.config.GeneratorProperties;
import com.mig.mapsql.core.model.FieldMapping;
import com.mig.mapsql.core.model.MappingType;
import com.mig.mapsql.core.model.TableMapping;
import com.mig.mapsql.core.sql.SelectSqlRenderer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSqlRendererTest {

    @Test
    void rendersCountHashPkAndAmount() {
        GeneratorProperties props = new GeneratorProperties();
        SelectSqlRenderer select = new SelectSqlRenderer(props);
        TestSqlRenderer renderer = new TestSqlRenderer(props, select);

        TableMapping table = new TableMapping();
        table.setSheetName("IBPS_PAYMENTINIT");
        table.setTargetTable("IBPS_PAYMENTINIT");
        table.setSourceTable("A10TIBPSDETAILLOG");
        table.setSheetTitle("表名：x 主键：AGENTSERIALNO");
        table.setJoinPredicate("FUNCTION2 IN ('ibps.101.001.01')");

        FieldMapping pk = field("AGENTSERIALNO", MappingType.DIRECT, "TRACE", "VARCHAR2", "16", "VARCHAR2", "22");
        pk.setNullable("PK");
        FieldMapping amount = field("AMOUNT", MappingType.TYPE_CONVERT, "AMOUNT", "VARCHAR2", "15", "NUMBER", "22,2");
        FieldMapping skip = field("WORKDATE", MappingType.SKIP, "TRANDATE", "VARCHAR2", "8", "VARCHAR2", "8");
        table.getFields().add(pk);
        table.getFields().add(amount);
        table.getFields().add(skip);

        TestSqlBundle bundle = renderer.render(table);
        assertTrue(bundle.isAmountCheck());
        assertTrue(bundle.isTimeWindow());
        assertTrue(bundle.isPkResolved());
        assertTrue(bundle.getFiles().containsKey("01_count_total_src.sql"));
        assertTrue(bundle.getFiles().containsKey("02_hash_src.sql"));
        assertTrue(bundle.getFiles().containsKey("05_pk_set_tgt.sql"));

        String countSrc = bundle.getFiles().get("01_count_total_src.sql");
        assertTrue(countSrc.contains("COUNT(*)"));
        assertTrue(countSrc.contains("SUM(("));
        assertTrue(countSrc.contains("TO_NUMBER(TRIM(s.AMOUNT))"));
        assertTrue(countSrc.contains("{{beginTm}}"));
        assertTrue(countSrc.contains("A10TIBPSDETAILLOG"));

        String countTgt = bundle.getFiles().get("01_count_total_tgt.sql");
        assertTrue(countTgt.contains("FROM IBPS_PAYMENTINIT t"));
        assertTrue(countTgt.contains("SUM(t.AMOUNT)"));
        assertFalse(countTgt.contains("FUNCTION2 IN"));

        String hashSrc = bundle.getFiles().get("02_hash_src.sql");
        assertTrue(hashSrc.contains("STANDARD_HASH"));
        assertTrue(hashSrc.contains("s.TRACE"));
        assertFalse(hashSrc.contains("WORKDATE"));

        String hashTgt = bundle.getFiles().get("02_hash_tgt.sql");
        assertTrue(hashTgt.contains("t.AGENTSERIALNO"));
        assertTrue(hashTgt.contains("t.AMOUNT"));
    }

    @Test
    void skipsHashWhenNoPk() {
        GeneratorProperties props = new GeneratorProperties();
        TestSqlRenderer renderer = new TestSqlRenderer(props, new SelectSqlRenderer(props));
        TableMapping table = new TableMapping();
        table.setSheetName("X");
        table.setTargetTable("X");
        table.setSourceTable("S");
        table.setJoinPredicate("1=1");
        table.getFields().add(field("C1", MappingType.DIRECT, "C1", "VARCHAR2", "10", "VARCHAR2", "10"));
        TestSqlBundle bundle = renderer.render(table);
        assertFalse(bundle.isPkResolved());
        assertTrue(bundle.getFiles().containsKey("01_count_total_src.sql"));
        assertFalse(bundle.getFiles().containsKey("02_hash_src.sql"));
    }

    private static FieldMapping field(String tgt, MappingType type, String src,
                                      String srcType, String srcLen, String tgtType, String tgtLen) {
        FieldMapping f = new FieldMapping();
        f.setTargetColumn(tgt);
        f.setMappingType(type);
        f.setSourceColumn(src);
        f.setSourceType(srcType);
        f.setSourceLength(srcLen);
        f.setTargetType(tgtType);
        f.setTargetLength(tgtLen);
        return f;
    }
}
