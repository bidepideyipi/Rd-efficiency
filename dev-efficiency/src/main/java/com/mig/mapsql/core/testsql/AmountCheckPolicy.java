package com.mig.mapsql.core.testsql;

import com.mig.mapsql.core.model.FieldMapping;
import com.mig.mapsql.core.model.MappingType;
import com.mig.mapsql.core.model.TableMapping;
import com.mig.mapsql.core.util.SheetNames;
import com.mig.mapsql.core.util.Strings;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * T4 金额校验触发：物理表名匹配 *PAYMENT*|*HOST* 且存在目标栏位 AMOUNT。
 */
public final class AmountCheckPolicy {

    private static final Pattern TABLE_HINT = Pattern.compile(
            ".*(PAYMENT|HOST).*", Pattern.CASE_INSENSITIVE);

    private AmountCheckPolicy() {
    }

    public static boolean enabled(TableMapping table) {
        String physical = physicalTable(table);
        if (!TABLE_HINT.matcher(physical).matches()) {
            return false;
        }
        return findAmountField(table) != null;
    }

    public static FieldMapping findAmountField(TableMapping table) {
        for (FieldMapping f : table.getFields()) {
            if (f.getMappingType() == MappingType.SKIP) {
                continue;
            }
            String col = Strings.trimToEmpty(f.getTargetColumn());
            if ("AMOUNT".equalsIgnoreCase(col)) {
                return f;
            }
        }
        return null;
    }

    public static String physicalTable(TableMapping table) {
        if (!Strings.isBlank(table.getTargetTable())) {
            return table.getTargetTable().trim().toUpperCase(Locale.ROOT);
        }
        return SheetNames.resolveTableName(table.getSheetName()).toUpperCase(Locale.ROOT);
    }
}
