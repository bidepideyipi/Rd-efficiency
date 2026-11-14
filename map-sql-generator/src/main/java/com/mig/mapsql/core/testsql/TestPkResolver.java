package com.mig.mapsql.core.testsql;

import com.mig.mapsql.core.model.FieldMapping;
import com.mig.mapsql.core.model.TableMapping;
import com.mig.mapsql.core.util.Strings;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析测试用主键列：优先 nullable=PK；回退 Sheet 标题「主键：…」。
 */
public final class TestPkResolver {

    private static final Pattern TITLE_PK = Pattern.compile(
            "主键[:：]\\s*(.+)$");

    private TestPkResolver() {
    }

    public static List<String> resolve(TableMapping table) {
        List<String> fromNullable = new ArrayList<String>();
        for (FieldMapping f : table.getFields()) {
            if (!"PK".equalsIgnoreCase(Strings.trimToEmpty(f.getNullable()))) {
                continue;
            }
            String col = Strings.trimToEmpty(f.getTargetColumn());
            if (!col.isEmpty()) {
                fromNullable.add(col.toUpperCase(Locale.ROOT));
            }
        }
        if (!fromNullable.isEmpty()) {
            return dedupe(fromNullable);
        }
        return dedupe(parseTitlePk(table.getSheetTitle()));
    }

    static List<String> parseTitlePk(String title) {
        List<String> out = new ArrayList<String>();
        if (Strings.isBlank(title)) {
            return out;
        }
        Matcher m = TITLE_PK.matcher(title.trim());
        if (!m.find()) {
            return out;
        }
        String raw = m.group(1).trim();
        for (String part : raw.split("[,，、]")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            // 去掉可能的说明后缀，如 "COL 主键" 已在正则外；保留标识符
            p = p.replaceAll("[\\s　]+", "");
            if (!p.isEmpty()) {
                out.add(p.toUpperCase(Locale.ROOT));
            }
        }
        return out;
    }

    private static List<String> dedupe(List<String> cols) {
        return new ArrayList<String>(new LinkedHashSet<String>(cols));
    }
}
