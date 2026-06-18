package com.tjc.bugagent.analysis;

import javax.validation.constraints.NotBlank;
import java.util.List;

/**
 * 对一条分析记录的人工反馈：结论对不对、真实根因是什么、正确答案该命中哪些关键词。
 */
public class AnalysisFeedbackRequest {
    // CORRECT / WRONG / PARTIAL
    @NotBlank
    private String verdict;
    private String actualRootCause;
    // 一个合格的结论必须出现的关键词，用来把这条 bug 沉淀成回归用例
    private List<String> expectKeywords;
    private String note;

    public String getVerdict() {
        return verdict;
    }

    public void setVerdict(String verdict) {
        this.verdict = verdict;
    }

    public String getActualRootCause() {
        return actualRootCause;
    }

    public void setActualRootCause(String actualRootCause) {
        this.actualRootCause = actualRootCause;
    }

    public List<String> getExpectKeywords() {
        return expectKeywords;
    }

    public void setExpectKeywords(List<String> expectKeywords) {
        this.expectKeywords = expectKeywords;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
