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
        if (fromBlocks != null) {
            return fromBlocks;
        }
        return extractFallbackPairs(content);
    }

    /**
     * @return pairs from paramList Arg blocks, or null when the file has no such block.
     *         An Arg without dealcode is skipped and does not fall through to the whole-file scan.
     */
    private static List<ParamPair> extractFromParamListBlocks(String content) {
        List<ParamPair> pairs = new ArrayList<ParamPair>();
        Matcher blockMatcher = PARAM_LIST_BLOCK.matcher(content);
        boolean sawBlock = false;
        while (blockMatcher.find()) {
            sawBlock = true;
            ParamPair pair = pairFromBlock(content, blockMatcher.group(1), blockMatcher.start(1));
            if (pair != null) {
                pairs.add(pair);
            }
        }
        return sawBlock ? pairs : null;
    }

    static ParamPair pairFromBlock(String block) {
        return pairFromBlock(block, block, 0);
    }

    private static ParamPair pairFromBlock(String content, String block, int blockStart) {
        String code = null;
        String msg = null;
        int line = lineNumberAt(content, blockStart);
        Matcher codeMatcher = DEALCODE.matcher(block);
        if (codeMatcher.find()) {
            code = codeMatcher.group(1);
            line = lineNumberAt(content, blockStart + codeMatcher.start());
        }
        Matcher msgMatcher = DEALMSG.matcher(block);
        if (msgMatcher.find()) {
            msg = msgMatcher.group(1);
            if (code == null) {
                line = lineNumberAt(content, blockStart + msgMatcher.start());
            }
        }
        if (code == null || code.isEmpty()) {
            return null;
        }
        boolean incomplete = msg == null || msg.isEmpty();
        return new ParamPair(code, msg, incomplete, line);
    }

    /**
     * Whole-file fallback: each dealcode paired with the next dealmsg after it.
     */
    private static List<ParamPair> extractFallbackPairs(String content) {
        List<ParamPair> pairs = new ArrayList<ParamPair>();
        Matcher codeMatcher = DEALCODE.matcher(content);
        while (codeMatcher.find()) {
            String code = codeMatcher.group(1);
            int line = lineNumberAt(content, codeMatcher.start());
            int from = codeMatcher.end();
            Matcher msgMatcher = DEALMSG.matcher(content);
            String msg = null;
            if (msgMatcher.find(from)) {
                msg = msgMatcher.group(1);
            }
            if (code == null || code.isEmpty()) {
                continue;
            }
            boolean incomplete = msg == null || msg.isEmpty();
            pairs.add(new ParamPair(code, msg, incomplete, line));
        }
        return pairs;
    }

    /** 1-based line number of {@code index} in {@code content}. */
    static int lineNumberAt(String content, int index) {
        if (content == null || content.isEmpty()) {
            return 1;
        }
        int line = 1;
        int end = Math.min(Math.max(index, 0), content.length());
        for (int i = 0; i < end; i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

}
