package com.mig.mapsql.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlRuleHeuristicsTest {

    @Test
    void qualifiesBareColumnsWithPrimaryAlias() {
        assertEquals("a.FLAG1",
                SqlRuleHeuristics.qualifyUnaliasedColumns("FLAG1", "a"));
        assertEquals("case when a.flag1='0' then '1' else '2' end",
                SqlRuleHeuristics.qualifyUnaliasedColumns("case when flag1='0' then '1' else '2' end", "a"));
        assertEquals("NVL(a.AMT,0)",
                SqlRuleHeuristics.qualifyUnaliasedColumns("NVL(AMT,0)", "a"));
    }

    @Test
    void keepsExistingAliasAndLiterals() {
        assertEquals("b.COL",
                SqlRuleHeuristics.qualifyUnaliasedColumns("b.COL", "a"));
        assertEquals("case when a.status='Y' then '是' else '否' end",
                SqlRuleHeuristics.qualifyUnaliasedColumns("case when status='Y' then '是' else '否' end", "a"));
        assertTrue(SqlRuleHeuristics.looksLikeSql("case when flag1='0' then '1' else '2' end"));
    }
}
