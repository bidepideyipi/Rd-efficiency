package com.mig.mapsql.core.scan;

import com.mig.mapsql.core.scan.model.ParamPair;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts dealcode/dealmsg pairs from .fc content.
 * Prefers &lt;Key&gt;paramList&lt;/Key&gt; + inner &lt;Arg&gt; blocks; falls back to whole-file pairing.
 */
public final class ParamListExtractor {

    private static final Pattern PARAM_LIST_BLOCK = Pattern.compile(
            "<Key>\\s*paramList\\s*</Key>\\s*<Arg>([\\s\\S]*?)</Arg>",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern DEALCODE = Pattern.compile(
            "\\[\\s*[\"']dealcode[\"']\\s*,\\s*[\"']([^\"']*)[\"']\\s*\\]",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern DEALMSG = Pattern.compile(
            "\\[\\s*[\"']dealmsg[\"']\\s*,\\s*[\"']([^\"']*)[\"']\\s*\\]",
            Pattern.CASE_INSENSITIVE);

    private ParamListExtractor() {
    }

    public static List<ParamPair> extract(String content) {
        if (content == null || content.isEmpty()) {
            return new ArrayList<ParamPair>();
        }
        List<ParamPair> fromBlocks = extractFromParamListBlocks(content);
        if (!fromBlocks.isEmpty()) {
            return fromBlocks;
        }
        return extractFallbackPairs(content);
    }

    private static List<ParamPair> extractFromParamListBlocks(String content) {
        List<ParamPair> pairs = new ArrayList<ParamPair>();
        Matcher blockMatcher = PARAM_LIST_BLOCK.matcher(content);
        while (blockMatcher.find()) {
            String block = blockMatcher.group(1);
            pairs.add(pairFromBlock(block));
        }
        return pairs;
    }

    static ParamPair pairFromBlock(String block) {
        String code = firstGroup(DEALCODE, block);
        String msg = firstGroup(DEALMSG, block);
        boolean incomplete = (code == null || code.isEmpty()) || (msg == null || msg.isEmpty());
        return new ParamPair(code, msg, incomplete);
    }

    /**
     * Whole-file fallback: each dealcode paired with the next dealmsg after it.
     */
    private static List<ParamPair> extractFallbackPairs(String content) {
        List<ParamPair> pairs = new ArrayList<ParamPair>();
        Matcher codeMatcher = DEALCODE.matcher(content);
        while (codeMatcher.find()) {
            String code = codeMatcher.group(1);
            int from = codeMatcher.end();
            Matcher msgMatcher = DEALMSG.matcher(content);
            String msg = null;
            if (msgMatcher.find(from)) {
                msg = msgMatcher.group(1);
            }
            boolean incomplete = code == null || code.isEmpty() || msg == null || msg.isEmpty();
            pairs.add(new ParamPair(code, msg, incomplete));
        }
        return pairs;
    }

    private static String firstGroup(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }
}
