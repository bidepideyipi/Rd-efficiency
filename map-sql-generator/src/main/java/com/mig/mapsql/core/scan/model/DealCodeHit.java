package com.mig.mapsql.core.scan.model;

public class DealCodeHit {

    private String serviceName;
    private String moduleName;
    private String dealcode;
    private String dealmsg;
    private String relativePath;

    public DealCodeHit() {
    }

    public DealCodeHit(String serviceName, String moduleName, String dealcode, String dealmsg, String relativePath) {
        this.serviceName = serviceName == null ? "" : serviceName;
        this.moduleName = moduleName == null ? "" : moduleName;
        this.dealcode = dealcode == null ? "" : dealcode;
        this.dealmsg = dealmsg == null ? "" : dealmsg;
        this.relativePath = relativePath == null ? "" : relativePath;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getModuleName() {
        return moduleName;
    }

    public void setModuleName(String moduleName) {
        this.moduleName = moduleName;
    }

    public String getDealcode() {
        return dealcode;
    }

    public void setDealcode(String dealcode) {
        this.dealcode = dealcode;
    }

    public String getDealmsg() {
        return dealmsg;
    }

    public void setDealmsg(String dealmsg) {
        this.dealmsg = dealmsg;
    }

    public String getRelativePath() {
        return relativePath;
    }

    public void setRelativePath(String relativePath) {
        this.relativePath = relativePath;
    }
}
