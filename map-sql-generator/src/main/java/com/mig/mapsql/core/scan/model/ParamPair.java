package com.mig.mapsql.core.scan.model;

public class ParamPair {

    private final String dealcode;
    private final String dealmsg;
    private final boolean incomplete;

    public ParamPair(String dealcode, String dealmsg, boolean incomplete) {
        this.dealcode = dealcode == null ? "" : dealcode;
        this.dealmsg = dealmsg == null ? "" : dealmsg;
        this.incomplete = incomplete;
    }

    public String getDealcode() {
        return dealcode;
    }

    public String getDealmsg() {
        return dealmsg;
    }

    public boolean isIncomplete() {
        return incomplete;
    }
}
