package com.mig.mapsql.errorcode;

/**
 * 银行错误码标准条目（来自「厂商错误码映射银行规范错误码表」）。
 */
public class ErrorCodeEntry {

    private Long id;
    private String module;
    /** 业务类1 / 系统类2 / 结果未知类3 */
    private String category;
    private String vendorCode;
    private String vendorConst;
    private Integer seqNo;
    private String fullCode;
    private String message;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getModule() {
        return module;
    }

    public void setModule(String module) {
        this.module = module;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getVendorCode() {
        return vendorCode;
    }

    public void setVendorCode(String vendorCode) {
        this.vendorCode = vendorCode;
    }

    public String getVendorConst() {
        return vendorConst;
    }

    public void setVendorConst(String vendorConst) {
        this.vendorConst = vendorConst;
    }

    public Integer getSeqNo() {
        return seqNo;
    }

    public void setSeqNo(Integer seqNo) {
        this.seqNo = seqNo;
    }

    public String getFullCode() {
        return fullCode;
    }

    public void setFullCode(String fullCode) {
        this.fullCode = fullCode;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
