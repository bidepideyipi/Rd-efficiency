package com.mig.mapsql.catalog;

import com.mig.mapsql.core.util.SheetNames;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExcelDbDiffServiceTest {

    @Test
    void diffUsesSharedSheetNameResolver() {
        // 对比链路与生成/装载共用 SheetNames.resolveTableName
        assertEquals("HVPS_EXTPAYMENT", SheetNames.resolveTableName("HVPS_EXTPAYMENT1"));
        assertEquals("IBPS_MOBILEINFO", SheetNames.resolveTableName("IBPS_MOBILEINFO1"));
    }
}
