package com.mig.mapsql.standard;

/**
 * 数据标准规则。
 * <ul>
 *   <li>TYPE：按字段类型族匹配，如 CHAR / CLOB / NUMBER</li>
 *   <li>COLUMN：按字段名精确匹配（忽略大小写）</li>
 * </ul>
 */
public class StandardRule {

    private Long id;
    /** TYPE | COLUMN */
    private String ruleType;
    /**
     * TYPE：CHAR / CLOB / NUMBER（可带长度提示，匹配时取类型族）
     * COLUMN：字段名，如 AMOUNT
     */
    private String matchExpr;
    /**
     * 目标类型模板，支持占位符 {n} {p} {s}
     * 例：VARCHAR2({n})、VARCHAR2(400)、NUMBER(22,2)
     */
    private String targetType;
    /**
     * 1=四步重建（ADD/COMMENT/UPDATE/DROP+RENAME）；0=直接 ALTER MODIFY
     * CHAR、CLOB、NUMBER 等不能直接转换时必须为 1
     */
    private boolean rebuild;
    private boolean enabled;
    private int priority;
    private String remark;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRuleType() {
        return ruleType;
    }

    public void setRuleType(String ruleType) {
        this.ruleType = ruleType;
    }

    public String getMatchExpr() {
        return matchExpr;
    }

    public void setMatchExpr(String matchExpr) {
        this.matchExpr = matchExpr;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public boolean isRebuild() {
        return rebuild;
    }

    public void setRebuild(boolean rebuild) {
        this.rebuild = rebuild;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
