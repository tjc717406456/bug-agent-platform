package com.tjc.bugagent.analysis;

import java.util.List;

/**
 * 分析历史分页结果：当前页数据 + 总条数。
 */
public class AnalysisRecordPage {
    private List<AnalysisRecord> records;
    private long total;

    public AnalysisRecordPage(List<AnalysisRecord> records, long total) {
        this.records = records;
        this.total = total;
    }

    public List<AnalysisRecord> getRecords() {
        return records;
    }

    public void setRecords(List<AnalysisRecord> records) {
        this.records = records;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }
}
