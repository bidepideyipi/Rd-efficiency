package com.mig.mapsql.core.util;

import com.mig.mapsql.core.model.LeftJoinSpec;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

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
        assertTrue(lj.getOnPredicate().contains("s.srcsysssn"));
        assertFalse(lj.getOnPredicate().toLowerCase().contains("a.srcsysssn"));
    }
}
