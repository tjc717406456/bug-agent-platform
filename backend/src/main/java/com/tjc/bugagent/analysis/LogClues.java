package com.tjc.bugagent.analysis;

import java.util.ArrayList;
import java.util.List;

/**
 * 从日志里抠出的关键线索。
 */
public class LogClues {
    private String traceId;
    private String requestTime;
    private String stackTrace;
    private List<String> sqlLines = new ArrayList<String>();
    private List<String> errorLines = new ArrayList<String>();

    public boolean isEmpty() {
        return isBlank(traceId) && isBlank(requestTime) && isBlank(stackTrace)
                && sqlLines.isEmpty() && errorLines.isEmpty();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getRequestTime() {
        return requestTime;
    }

    public void setRequestTime(String requestTime) {
        this.requestTime = requestTime;
    }

    public String getStackTrace() {
        return stackTrace;
    }

    public void setStackTrace(String stackTrace) {
        this.stackTrace = stackTrace;
    }

    public List<String> getSqlLines() {
        return sqlLines;
    }

    public void setSqlLines(List<String> sqlLines) {
        this.sqlLines = sqlLines;
    }

    public List<String> getErrorLines() {
        return errorLines;
    }

    public void setErrorLines(List<String> errorLines) {
        this.errorLines = errorLines;
    }
}
