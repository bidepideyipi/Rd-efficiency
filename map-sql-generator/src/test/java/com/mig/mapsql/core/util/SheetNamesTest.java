package com.mig.mapsql.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SheetNamesTest {

    @Test
    void resolveTableNameStripsTrailingDigit() {
        assertEquals("HVPS_EXTPAYMENT", SheetNames.resolveTableName("HVPS_EXTPAYMENT1"));
        assertEquals("IBPS_MOBILEINFO", SheetNames.resolveTableName("IBPS_MOBILEINFO1"));
        assertEquals("IBPS_PAYMENTINIT", SheetNames.resolveTableName("IBPS_PAYMENTINIT2"));
        assertEquals("IBPS_MOBILEINFO", SheetNames.resolveTableName("IBPS_MOBILEINFO"));
        assertEquals("T", SheetNames.resolveTableName("T9"));
        assertEquals("9", SheetNames.resolveTableName("9"));
        assertEquals("", SheetNames.resolveTableName(null));
        assertEquals("", SheetNames.resolveTableName("  "));
    }
}
