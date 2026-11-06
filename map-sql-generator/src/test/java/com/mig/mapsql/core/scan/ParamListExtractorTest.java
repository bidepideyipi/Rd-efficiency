package com.mig.mapsql.core.scan;

import com.mig.mapsql.core.scan.model.ParamPair;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParamListExtractorTest {

    @Test
    void extractsFromParamListBlocks() {
        String content = ""
                + "<InArgs>\n"
                + "  <Arg>\n"
                + "    <Key>paramList</Key>\n"
                + "    <Arg>[\n"
                + "      [\"dealcode\", \"A000000\"],\n"
                + "      [\"dealmsg\", \"对账受理成功\"]\n"
                + "    ]</Arg>\n"
                + "  </Arg>\n"
                + "  <Arg>\n"
                + "    <Key>paramList</Key>\n"
                + "    <Arg>[\n"
                + "      [\"dealcode\", \"A00000\"],\n"
                + "      [\"dealmsg\", \"对账完成\"]\n"
                + "    ]</Arg>\n"
                + "  </Arg>\n"
                + "</InArgs>";

        List<ParamPair> pairs = ParamListExtractor.extract(content);
        assertEquals(2, pairs.size());
        assertEquals("A000000", pairs.get(0).getDealcode());
        assertEquals("对账受理成功", pairs.get(0).getDealmsg());
        assertEquals(5, pairs.get(0).getLineNumber());
        assertFalse(pairs.get(0).isIncomplete());
        assertEquals("A00000", pairs.get(1).getDealcode());
        assertEquals("对账完成", pairs.get(1).getDealmsg());
        assertEquals(12, pairs.get(1).getLineNumber());
    }

    @Test
    void toleratesMalformedUuidAndWhitespace() {
        String content = ""
                + "<UUID>CAB</UIUID>\n"
                + "<Key>paramList</Key>\n"
                + "<Arg>[\n"
                + "    [\"dealcode\",\"FIBC009\"],\n"
                + "    [\"dealmsg\",  \"仍存在待补发数据\"]\n"
                + "]</Arg>";

        List<ParamPair> pairs = ParamListExtractor.extract(content);
        assertEquals(1, pairs.size());
        assertEquals("FIBC009", pairs.get(0).getDealcode());
        assertEquals("仍存在待补发数据", pairs.get(0).getDealmsg());
    }

    @Test
    void skipsArgWithoutDealcode() {
        String content = ""
                + "<Key>paramList</Key>\n"
                + "<Arg>[\n"
                + "  [\"productcode\", \"P1\"]\n"
                + "]</Arg>\n"
                + "<Key>paramList</Key>\n"
                + "<Arg>[\n"
                + "  [\"dealcode\", \"A1\"],\n"
                + "  [\"dealmsg\", \"ok\"]\n"
                + "]</Arg>\n"
                + "[\"dealcode\", \"OUTSIDE\"]";

        List<ParamPair> pairs = ParamListExtractor.extract(content);
        assertEquals(1, pairs.size());
        assertEquals("A1", pairs.get(0).getDealcode());
        assertEquals("ok", pairs.get(0).getDealmsg());
    }

    @Test
    void marksIncompleteWhenDealmsgMissing() {
        String content = "<Key>paramList</Key><Arg>[[\"dealcode\", \"X1\"]]</Arg>";
        List<ParamPair> pairs = ParamListExtractor.extract(content);
        assertEquals(1, pairs.size());
        assertEquals("X1", pairs.get(0).getDealcode());
        assertEquals("", pairs.get(0).getDealmsg());
        assertTrue(pairs.get(0).isIncomplete());
    }

    @Test
    void fallbackPairsDealcodeWithFollowingDealmsg() {
        String content = ""
                + "[[\"dealcode\", \"C1\"],[\"dealmsg\", \"m1\"]]\n"
                + "[[\"dealcode\", \"C2\"],[\"dealmsg\", \"m2\"]]";
        List<ParamPair> pairs = ParamListExtractor.extract(content);
        assertEquals(2, pairs.size());
        assertEquals("C1", pairs.get(0).getDealcode());
        assertEquals("m1", pairs.get(0).getDealmsg());
        assertEquals("C2", pairs.get(1).getDealcode());
        assertEquals("m2", pairs.get(1).getDealmsg());
        assertEquals(1, pairs.get(0).getLineNumber());
        assertEquals(2, pairs.get(1).getLineNumber());
    }
}
