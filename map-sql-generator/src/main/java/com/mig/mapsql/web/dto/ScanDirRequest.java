package com.mig.mapsql.web.dto;

public class ScanDirRequest {

    private String path;
    private String projectDir;

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getProjectDir() {
        return projectDir;
    }

    public void setProjectDir(String projectDir) {
        this.projectDir = projectDir;
    }
}
