package com.mig.mapsql.standard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ColumnTypeParserTest {

    @Test
    void parseCharAndNumber() {
        ColumnTypeParser.ParsedType c = ColumnTypeParser.parse("CHAR(10)");
        assertEquals("CHAR", c.family);
        assertEquals(Integer.valueOf(10), c.length);

        ColumnTypeParser.ParsedType n = ColumnTypeParser.parse("NUMBER(22,2)");
        assertEquals("NUMBER", n.family);
        assertEquals(Integer.valueOf(22), n.precision);
        assertEquals(Integer.valueOf(2), n.scale);
    }

    @Test
    void renderPlaceholders() {
        ColumnTypeParser.ParsedType c = ColumnTypeParser.parse("CHAR(8)");
        assertEquals("VARCHAR2(8)", ColumnTypeParser.renderTarget("VARCHAR2({n})", c));
        ColumnTypeParser.ParsedType n = ColumnTypeParser.parse("NUMBER(10,2)");
        assertEquals("NUMBER(10,2)", ColumnTypeParser.renderTarget("NUMBER({p},{s})", n));
    }
}
