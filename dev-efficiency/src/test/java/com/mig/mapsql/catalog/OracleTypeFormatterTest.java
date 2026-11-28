package com.mig.mapsql.catalog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OracleTypeFormatterTest {

    @Test
    void formatsVarchar2WithCharLength() {
        DbColumnMeta m = new DbColumnMeta();
        OracleTypeFormatter.applyExcelTypeAndLength(m, "VARCHAR2", 22, null, null, 22);
        assertEquals("VARCHAR2", m.getExcelType());
        assertEquals("22", m.getExcelLength());
    }

    @Test
    void formatsNumberWithScale() {
        DbColumnMeta m = new DbColumnMeta();
        OracleTypeFormatter.applyExcelTypeAndLength(m, "NUMBER", 22, 18, 2, null);
        assertEquals("NUMBER", m.getExcelType());
        assertEquals("18,2", m.getExcelLength());
    }

    @Test
    void comparesSignatures() {
        assertTrue(OracleTypeFormatter.typeSignatureEquals("varchar2", "22", "VARCHAR2", "22"));
        assertFalse(OracleTypeFormatter.typeSignatureEquals("VARCHAR2", "22", "VARCHAR2", "32"));
    }
}
