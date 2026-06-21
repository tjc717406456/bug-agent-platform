package com.tjc.bugagent.analysis.agent;

/**
 * 一条命中的历史相似案例，喂回 prompt 当方向参考用。只带摘要信息，不带完整结论防照抄。
 */
public class SimilarCase {

    private final String apiPath;
    private final String rootCause;
    private final String keywords;
    private final String confidence;
    private final int score;

    public SimilarCase(String apiPath, String rootCause, String keywords, String confidence, int score) {
        this.apiPath = apiPath;
        this.rootCause = rootCause;
        this.keywords = keywords;
        this.confidence = confidence;
        this.score = score;
    }

    public String getApiPath() {
        return apiPath;
    }

    public String getRootCause() {
        return rootCause;
    }

    public String getKeywords() {
        return keywords;
    }

    public String getConfidence() {
        return confidence;
    }

    public int getScore() {
        return score;
    }
}
