package com.mig.mapsql.core.validate;

import com.mig.mapsql.config.GeneratorProperties;
import com.mig.mapsql.core.model.ExcelColumns;
import com.mig.mapsql.core.model.FieldMapping;
import com.mig.mapsql.core.model.LeftJoinSpec;
import com.mig.mapsql.core.model.MappingType;
import com.mig.mapsql.core.model.TableMapping;
import com.mig.mapsql.core.model.ValidationMessage;
import com.mig.mapsql.core.model.ValidationMessage.Level;
import com.mig.mapsql.core.sql.TypeCastSupport;
import com.mig.mapsql.core.util.OracleIdentifier;
import com.mig.mapsql.core.util.SqlRuleHeuristics;
import com.mig.mapsql.core.util.Strings;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class MappingValidator {

    private final GeneratorProperties properties;

    public MappingValidator(GeneratorProperties properties) {
        this.properties = properties;
    }

    public List<ValidationMessage> validate(TableMapping table) {
        List<ValidationMessage> messages = new ArrayList<ValidationMessage>();
        String tableName = table.getTargetTable() != null ? table.getTargetTable() : table.getSheetName();

        if (!OracleIdentifier.isSafeFileName(table.getSheetName())) {
            messages.add(tableErr(tableName, "Sheet 名不能作为安全文件名: " + table.getSheetName()));
        }

        Set<String> sourceTables = new HashSet<String>();
        int selectable = 0;

        for (FieldMapping f : table.getFields()) {
            Integer row = f.getExcelRow();
            String field = f.getTargetColumn();

            if (Strings.isBlank(f.getTargetColumn()) && f.getMappingType() != MappingType.SKIP) {
                messages.add(cellErr(tableName, row, ExcelColumns.TARGET_COLUMN, ExcelColumns.H_TARGET_COLUMN,
                        field, "目标栏位为空"));
            }
            if (f.getMappingType() == MappingType.UNKNOWN) {
                messages.add(cellErr(tableName, row, ExcelColumns.MAPPING_TYPE, ExcelColumns.H_MAPPING_TYPE,
                        field, "未知映射类型: " + f.getMappingTypeRaw()));
            }
            if (!Strings.isBlank(f.getSourceTable())) {
                sourceTables.add(f.getSourceTable().trim().toUpperCase(Locale.ROOT));
            }
            if (f.getMappingType() == MappingType.SKIP) {
                if ("PK".equalsIgnoreCase(Strings.trimToEmpty(f.getNullable()))) {
                    messages.add(cellWarn(tableName, row, ExcelColumns.MAPPING_TYPE, ExcelColumns.H_MAPPING_TYPE,
                            field, "PK 字段为不迁移"));
                }
                continue;
            }
            selectable++;

            if (!Strings.isBlank(f.getTargetColumn()) && !OracleIdentifier.isValid(f.getTargetColumn())) {
                messages.add(cellErr(tableName, row, ExcelColumns.TARGET_COLUMN, ExcelColumns.H_TARGET_COLUMN,
                        field, "非法目标列名"));
            }

            boolean hasRule = !Strings.isBlank(f.getRuleExpression());
            if (hasRule) {
                boolean sqlLike = SqlRuleHeuristics.looksLikeSql(f.getRuleExpression());
                if (!sqlLike) {
                    if (f.getMappingType() == MappingType.CODE_CONVERT || properties.isRequireSqlRuleWhenPresent()) {
                        messages.add(cellErr(tableName, row, ExcelColumns.RULE, ExcelColumns.H_RULE,
                                field, "规则说明非空但不是可执行 SQL 表达式: " + abbreviate(f.getRuleExpression())));
                        continue;
                    }
                    messages.add(cellWarn(tableName, row, ExcelColumns.RULE, ExcelColumns.H_RULE,
                            field, "规则说明非 SQL，已忽略并回退源列逻辑: " + abbreviate(f.getRuleExpression())));
                } else {
                    continue;
                }
            }

            if (f.getMappingType() == MappingType.DEFAULT_VALUE) {
                if (Strings.isBlank(f.getDefaultValue())) {
                    messages.add(cellErr(tableName, row, ExcelColumns.DEFAULT_VALUE, ExcelColumns.H_DEFAULT_VALUE,
                            field, "02-默认值缺少默认值"));
                }
                continue;
            }

            if (f.getMappingType() == MappingType.CODE_CONVERT) {
                messages.add(cellErr(tableName, row, ExcelColumns.RULE, ExcelColumns.H_RULE,
                        field, "03-码值转换必须填写规则说明"));
                continue;
            }

            if (Strings.isBlank(f.getSourceColumn())) {
                if (f.getMappingType() == MappingType.TRANSFORM) {
                    messages.add(cellWarn(tableName, row, ExcelColumns.RULE, ExcelColumns.H_RULE,
                            field, "05-加工映射无规则且无源列，无法生成"));
                    messages.add(cellErr(tableName, row, ExcelColumns.SOURCE_COLUMN, ExcelColumns.H_SOURCE_COLUMN,
                            field, "缺少源表栏位或规则说明"));
                } else if (f.getMappingType() == MappingType.DIRECT || f.getMappingType() == MappingType.TYPE_CONVERT) {
                    messages.add(cellErr(tableName, row, ExcelColumns.SOURCE_COLUMN, ExcelColumns.H_SOURCE_COLUMN,
                            field, "缺少源表栏位"));
                }
                continue;
            }

            if (!OracleIdentifier.isValid(f.getSourceColumn())) {
                messages.add(cellErr(tableName, row, ExcelColumns.SOURCE_COLUMN, ExcelColumns.H_SOURCE_COLUMN,
                        field, "非法源列名: " + f.getSourceColumn()));
            }

            if (f.getMappingType() == MappingType.TRANSFORM) {
                messages.add(cellWarn(tableName, row, ExcelColumns.RULE, ExcelColumns.H_RULE,
                        field, "05-加工映射规则为空，已降级为源列+类型/长度处理"));
            }

            boolean typeDiff = TypeCastSupport.typeDiffers(f.getSourceType(), f.getTargetType());
            if (typeDiff && !TypeCastSupport.canAutoCast(f.getSourceType(), f.getTargetType())) {
                messages.add(cellErr(tableName, row, ExcelColumns.TARGET_TYPE, ExcelColumns.H_TARGET_TYPE,
                        field, "无法自动转换类型 " + f.getSourceType() + " → " + f.getTargetType() + "，请补规则说明"));
            }

            Integer srcLen = TypeCastSupport.charLength(f.getSourceLength());
            Integer tgtLen = TypeCastSupport.charLength(f.getTargetLength());
            if (srcLen != null && tgtLen != null && srcLen < tgtLen
                    && TypeCastSupport.isCharType(f.getSourceType())
                    && TypeCastSupport.isCharType(f.getTargetType())) {
                messages.add(cellInfo(tableName, row, ExcelColumns.TARGET_LENGTH, "长度",
                        field, "源长度 < 目标长度，直迁不补齐"));
            }
            if (srcLen != null && tgtLen != null && srcLen > tgtLen
                    && TypeCastSupport.isCharType(f.getSourceType())
                    && TypeCastSupport.isCharType(f.getTargetType())
                    && "error".equalsIgnoreCase(properties.getLengthOverflow())) {
                messages.add(cellErr(tableName, row, ExcelColumns.TARGET_LENGTH, "长度",
                        field, "源长度 > 目标长度且 lengthOverflow=error"));
            }
        }

        if (Strings.isBlank(table.getSourceTable())) {
            messages.add(cellErr(tableName, null, ExcelColumns.SOURCE_TABLE, ExcelColumns.H_SOURCE_TABLE,
                    null, "无法判定主源表（无有效源表表名）"));
        }
        if (selectable == 0) {
            messages.add(tableErr(tableName, "SELECT 列表为空（全部为不迁移或无有效字段）"));
        }

        String primaryKey = Strings.isBlank(table.getSourceTable())
                ? null
                : table.getSourceTable().trim().toUpperCase(Locale.ROOT);
        Set<String> joined = new HashSet<String>();
        for (LeftJoinSpec lj : table.getLeftJoins()) {
            joined.add(lj.getTableName().toUpperCase(Locale.ROOT));
        }
        for (String st : sourceTables) {
            if (primaryKey != null && st.equals(primaryKey)) {
                continue;
            }
            if (!joined.contains(st)) {
                messages.add(cellErr(tableName, null, ExcelColumns.JOIN_CONDITION, ExcelColumns.H_JOIN,
                        null, "副源表 " + st + " 未在「库表关联条件」中配置 JOIN（LEFT/INNER）"));
            }
        }

        if (table.isWhereConflict()) {
            String msg = "存在多条不一致的 WHERE 类库表关联条件";
            if ("error".equalsIgnoreCase(properties.getJoinConditionConflict())) {
                messages.add(cellErr(tableName, null, ExcelColumns.JOIN_CONDITION, ExcelColumns.H_JOIN, null, msg));
            } else {
                messages.add(cellWarn(tableName, null, ExcelColumns.JOIN_CONDITION, ExcelColumns.H_JOIN, null, msg + "，已忽略"));
            }
        }
        if (table.isLeftJoinConflict()) {
            String msg = "JOIN 关联条件冲突（通常不应再出现；同别名不同 ON/表会自动改别名）";
            if ("error".equalsIgnoreCase(properties.getJoinConditionConflict())) {
                messages.add(cellErr(tableName, null, ExcelColumns.JOIN_CONDITION, ExcelColumns.H_JOIN, null, msg));
            } else {
                messages.add(cellWarn(tableName, null, ExcelColumns.JOIN_CONDITION, ExcelColumns.H_JOIN, null, msg));
            }
        }
        for (String uj : table.getUnsupportedJoins()) {
            messages.add(cellWarn(tableName, null, ExcelColumns.JOIN_CONDITION, ExcelColumns.H_JOIN,
                    null, "暂不支持的关联写法（支持 LEFT JOIN / INNER JOIN）: " + abbreviate(uj)));
        }
        for (String note : table.getRelationNotes()) {
            messages.add(cellWarn(tableName, null, ExcelColumns.JOIN_CONDITION, ExcelColumns.H_JOIN, null, note));
        }

        return messages;
    }

    public boolean hasError(List<ValidationMessage> messages) {
        for (ValidationMessage m : messages) {
            if (m.getLevel() == Level.ERROR) {
                return true;
            }
        }
        return false;
    }

    private static ValidationMessage cellErr(String table, Integer row, int colIdx, String header,
                                             String targetField, String msg) {
        return ValidationMessage.ofCell(Level.ERROR, table, row, colIdx, header, targetField, msg);
    }

    private static ValidationMessage cellWarn(String table, Integer row, int colIdx, String header,
                                              String targetField, String msg) {
        return ValidationMessage.ofCell(Level.WARN, table, row, colIdx, header, targetField, msg);
    }

    private static ValidationMessage cellInfo(String table, Integer row, int colIdx, String header,
                                              String targetField, String msg) {
        return ValidationMessage.ofCell(Level.INFO, table, row, colIdx, header, targetField, msg);
    }

    private static ValidationMessage tableErr(String table, String msg) {
        return new ValidationMessage(Level.ERROR, table, null, null, null, null, msg);
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 80 ? s : s.substring(0, 80) + "...";
    }
}
