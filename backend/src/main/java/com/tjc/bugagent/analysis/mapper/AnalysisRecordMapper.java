package com.tjc.bugagent.analysis.mapper;

import com.tjc.bugagent.analysis.AnalysisRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * analysis_record 表共享 Mapper：Repository 写入、Service 历史查询与删除、FeedbackService 标注、SimilarCaseRetriever 召回共用。
 * SQL 见 resources/mapper/AnalysisRecordMapper.xml。列名下划线靠全局 map-underscore-to-camel-case 自动转驼峰。
 */
@Mapper
public interface AnalysisRecordMapper {

    /** 写入一次分析记录，自增主键回填到 record.id（useGeneratedKeys） */
    void insert(AnalysisRecordInsert record);

    /** 历史列表总条数，projectId/apiPath 可选 */
    long countList(@Param("projectId") Long projectId, @Param("apiPath") String apiPath);

    /** 历史列表精简字段，按 id 倒序分页 */
    List<AnalysisRecord> selectSummaryList(@Param("projectId") Long projectId, @Param("apiPath") String apiPath,
                                           @Param("limit") int limit, @Param("offset") int offset);

    /** 批量删除 */
    void deleteByIds(@Param("ids") List<Long> ids);

    /** 取单条完整记录 */
    AnalysisRecord selectDetail(@Param("id") Long id);

    /** 标注反馈，返回受影响行数（0 表示记录不存在） */
    int updateFeedback(@Param("recordId") Long recordId, @Param("verdict") String verdict,
                       @Param("actualRootCause") String actualRootCause, @Param("expectKeywords") String expectKeywords,
                       @Param("note") String note);

    /** 召回候选：含 embedding/stack_trace/keywords(coalesce 别名) 等非实体字段，按 Map 返回交给 retriever 自行组装 */
    List<Map<String, Object>> selectCandidates(@Param("projectId") Long projectId);

    /**
     * 评估飞轮用例源：圈出靠得住的记录（人工标注过 或 机器验证 CONFIRMED），
     * 带分析入参 + expect_keywords(coalesce 别名 JSON 文本)，按 Map 返回交给 EvalService 组装成 EvalCase。
     */
    List<Map<String, Object>> selectFeedbackCases();

    /** 回写候选向量缓存 */
    void updateEmbedding(@Param("id") long id, @Param("embedding") String embedding);
}
