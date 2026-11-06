package com.mig.mapsql.core.scan.model;

public class ParamPair {

    private final String dealcode;
    private final String dealmsg;
    private final boolean incomplete;
    /** 1-based line of dealcode in the source file; 0 if unknown. */
    private final int lineNumber;

    public ParamPair(String dealcode, String dealmsg, boolean incomplete) {
        this(dealcode, dealmsg, incomplete, 0);
    }

    public ParamPair(String dealcode, String dealmsg, boolean incomplete, int lineNumber) {
        this.dealcode = dealcode == null ? "" : dealcode;
        this.dealmsg = dealmsg == null ? "" : dealmsg;
        this.incomplete = incomplete;
        this.lineNumber = lineNumber < 0 ? 0 : lineNumber;
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

    public int getLineNumber() {
        return lineNumber;
    }
}
