package com.tjc.bugagent.analysis;

import com.tjc.bugagent.analysis.mapper.AnalysisRecordMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 查询分析历史记录，支撑历史页和延迟标注。
 */
@Service
public class AnalysisRecordService {
    private final AnalysisRecordMapper analysisRecordMapper;

    public AnalysisRecordService(AnalysisRecordMapper analysisRecordMapper) {
        this.analysisRecordMapper = analysisRecordMapper;
    }

    /**
     * 按项目、接口、类型筛分析历史，分页返回精简字段（不带大体积证据）+ 总条数。
     * ownerId 非空时只出该用户名下项目的记录，管理员传 null 看全部。
     */
    public AnalysisRecordPage list(Long projectId, String apiPath, String recordType, Long ownerId, int page, int size) {
        int limit = size <= 0 || size > 200 ? 20 : size;
        int offset = Math.max(0, page) * limit;
        long total = analysisRecordMapper.countList(projectId, apiPath, recordType, ownerId);
        List<AnalysisRecord> records = analysisRecordMapper.selectSummaryList(projectId, apiPath, recordType, ownerId, limit, offset);
        return new AnalysisRecordPage(records, total);
    }

    /**
     * 批量删除分析记录。
     */
    public void deleteByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        analysisRecordMapper.deleteByIds(ids);
    }

    /**
     * 取单条完整记录（含证据和已有标注），给详情和延迟标注用。
     */
    public AnalysisRecord get(Long id) {
        return analysisRecordMapper.selectDetail(id);
    }
}
