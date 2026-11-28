package com.mig.mapsql.core.testsql;

import com.mig.mapsql.core.model.FieldMapping;
import com.mig.mapsql.core.model.TableMapping;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestPkResolverTest {

    @Test
    void prefersNullablePk() {
        TableMapping table = new TableMapping();
        table.setSheetTitle("表名：x 主键：TITLE_PK");
        FieldMapping a = new FieldMapping();
        a.setTargetColumn("COL_A");
        a.setNullable("PK");
        FieldMapping b = new FieldMapping();
        b.setTargetColumn("COL_B");
        b.setNullable("N");
        table.getFields().add(a);
        table.getFields().add(b);
        assertEquals(Arrays.asList("COL_A"), TestPkResolver.resolve(table));
    }

    @Test
    void fallsBackToTitle() {
        TableMapping table = new TableMapping();
        table.setSheetTitle("表名：支付发起 IBPS_PAYMENTINIT 主键：AGENTSERIALNO、WORKDATE");
        assertEquals(Arrays.asList("AGENTSERIALNO", "WORKDATE"), TestPkResolver.resolve(table));
    }
}
