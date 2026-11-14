package com.mig.mapsql.core.testsql;

import com.mig.mapsql.core.model.FieldMapping;
import com.mig.mapsql.core.model.MappingType;
import com.mig.mapsql.core.model.TableMapping;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AmountCheckPolicyTest {

    @Test
    void paymentWithAmountEnabled() {
        TableMapping t = table("IBPS_PAYMENTINIT", "AMOUNT", MappingType.TYPE_CONVERT);
        assertTrue(AmountCheckPolicy.enabled(t));
    }

    @Test
    void hostWithAmountEnabled() {
        TableMapping t = table("HVPS_HOSTBOOKINIT_HIS", "AMOUNT", MappingType.DIRECT);
        assertTrue(AmountCheckPolicy.enabled(t));
    }

    @Test
    void feeAmountNotExactMatch() {
        TableMapping t = table("IBPS_PAYMENTINIT", "FEEAMOUNT", MappingType.DIRECT);
        assertFalse(AmountCheckPolicy.enabled(t));
    }

    @Test
    void nonPaymentTableDisabled() {
        TableMapping t = table("IBPS_PROTOCOLINFO", "AMOUNT", MappingType.DIRECT);
        assertFalse(AmountCheckPolicy.enabled(t));
    }

    private static TableMapping table(String name, String col, MappingType type) {
        TableMapping t = new TableMapping();
        t.setSheetName(name);
        t.setTargetTable(name);
        FieldMapping f = new FieldMapping();
        f.setTargetColumn(col);
        f.setMappingType(type);
        t.getFields().add(f);
        return t;
    }
}
