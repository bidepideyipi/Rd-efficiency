package com.mig.mapsql.core.scan.model;

public class DealCodeHit {

    private String serviceName;
    private String moduleName;
    private String dealcode;
    private String dealmsg;
    private String relativePath;
    private String fileName;
    private int lineNumber;

    public DealCodeHit() {
    }

    public DealCodeHit(String serviceName, String moduleName, String dealcode, String dealmsg, String relativePath) {
        this(serviceName, moduleName, dealcode, dealmsg, relativePath, fileNameOf(relativePath), 0);
    }

    public DealCodeHit(String serviceName, String moduleName, String dealcode, String dealmsg,
                       String relativePath, String fileName, int lineNumber) {
        this.serviceName = serviceName == null ? "" : serviceName;
        this.moduleName = moduleName == null ? "" : moduleName;
        this.dealcode = dealcode == null ? "" : dealcode;
        this.dealmsg = dealmsg == null ? "" : dealmsg;
        this.relativePath = relativePath == null ? "" : relativePath;
        this.fileName = fileName == null ? "" : fileName;
        this.lineNumber = lineNumber < 0 ? 0 : lineNumber;
    }

    private static String fileNameOf(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) {
            return "";
        }
        int slash = Math.max(relativePath.lastIndexOf('/'), relativePath.lastIndexOf('\\'));
        return slash < 0 ? relativePath : relativePath.substring(slash + 1);
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

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public void setLineNumber(int lineNumber) {
        this.lineNumber = lineNumber;
    }
}
