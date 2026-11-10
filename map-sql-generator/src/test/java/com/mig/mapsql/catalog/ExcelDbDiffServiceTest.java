package com.mig.mapsql.catalog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExcelDbDiffServiceTest {

    @Test
    void resolveTableNameStripsTrailingDigit() {
        assertEquals("IBPS_MOBILEINFO", ExcelDbDiffService.resolveTableName("IBPS_MOBILEINFO1"));
        assertEquals("IBPS_PAYMENTINIT", ExcelDbDiffService.resolveTableName("IBPS_PAYMENTINIT2"));
        assertEquals("IBPS_MOBILEINFO", ExcelDbDiffService.resolveTableName("IBPS_MOBILEINFO"));
        assertEquals("T", ExcelDbDiffService.resolveTableName("T9"));
        assertEquals("9", ExcelDbDiffService.resolveTableName("9"));
    }
}
