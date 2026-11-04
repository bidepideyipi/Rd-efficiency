package com.mig.mapsql.core.util;

import com.mig.mapsql.core.model.LeftJoinSpec;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JoinConditionParserTest {

    @Test
    void classifiesWhereAndLeftJoinTogether() {
        JoinConditionParser.RelationParseResult r = JoinConditionParser.parseAll(Arrays.asList(
                "select * from a08thvtrx where cmtno!='hvps.142.001.01';",
                "left join TA0LTXNSSNINFO b on b.srcsysssn=a.srcsysssn"
        ), "s");

        assertFalse(r.isWhereConflict());
        assertFalse(r.isLeftJoinConflict());
        assertEquals("cmtno!='hvps.142.001.01'", r.getWherePredicate());
        assertEquals(1, r.getLeftJoins().size());
        LeftJoinSpec lj = r.getLeftJoins().get(0);
        assertEquals("TA0LTXNSSNINFO", lj.getTableName());
        assertEquals("b", lj.getAlias());
        assertEquals(LeftJoinSpec.TYPE_LEFT, lj.getJoinType());
        assertTrue(lj.getOnPredicate().contains("s.srcsysssn"));
        assertFalse(lj.getOnPredicate().toLowerCase().contains("a.srcsysssn"));
    }

    @Test
    void parsesSelectFromInnerJoinComposite() {
        String raw = "select * from a08thvtrx a\n"
                + "inner join A08TAppdWTSK b on b.syscd='HVPS' and b.businesstrace=a.businesstrace;";
        JoinConditionParser.RelationParseResult r = JoinConditionParser.parseAll(
                Arrays.asList(raw), "s");

        assertEquals(null, r.getWherePredicate());
        assertEquals(1, r.getLeftJoins().size());
        LeftJoinSpec j = r.getLeftJoins().get(0);
        assertEquals(LeftJoinSpec.TYPE_INNER, j.getJoinType());
        assertEquals("A08TAppdWTSK", j.getTableName());
        assertEquals("b", j.getAlias());
        assertTrue(j.getOnPredicate().contains("s.businesstrace"));
        assertEquals("INNER JOIN", j.sqlKeyword());
    }

    @Test
    void parsesMultipleInnerJoinsAndDedupesAliasB() {
        JoinConditionParser.RelationParseResult r = JoinConditionParser.parseAll(Arrays.asList(
                "select * from a08thvtrx a inner join A08TAPPDWTSK b on b.syscd='HVPS' and b.businesstrace=a.businesstrace;",
                "select * from a08thvtrx a inner join A08TAPPDTSCF b on b.syscd='HVPS' and b.businesstrace=a.businesstrace;",
                "select * from a08thvtrx a inner join A08TAPPDJSZZ b on b.syscd='HVPS' and b.businesstrace=a.businesstrace;"
        ), "s");

        assertEquals(3, r.getLeftJoins().size());
        Set<String> aliases = new HashSet<String>();
        for (LeftJoinSpec j : r.getLeftJoins()) {
            assertEquals(LeftJoinSpec.TYPE_INNER, j.getJoinType());
            aliases.add(j.getAlias());
        }
        assertEquals(3, aliases.size());
        assertTrue(aliases.contains("b"));
    }

    @Test
    void standaloneInnerJoinSupported() {
        JoinConditionParser.RelationParseResult r = JoinConditionParser.parseAll(Arrays.asList(
                "inner join FOO x on x.id = a.id"
        ), "s");
        assertEquals(1, r.getLeftJoins().size());
        assertEquals(LeftJoinSpec.TYPE_INNER, r.getLeftJoins().get(0).getJoinType());
        assertEquals("FOO", r.getLeftJoins().get(0).getTableName());
        assertEquals("x", r.getLeftJoins().get(0).getAlias());
    }
}
