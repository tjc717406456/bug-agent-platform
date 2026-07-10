package com.tjc.bugagent.auth;

import com.tjc.bugagent.analysis.mapper.AnalysisRecordMapper;
import com.tjc.bugagent.project.mapper.ProjectMapper;
import org.springframework.stereotype.Component;

/**
 * 资源归属校验的唯一入口。项目私有，只有所有者与管理员能碰；
 * 其余资源（版本、数据源、分析记录、代码图谱）都依附项目，一律换算成 projectId 后走同一道闸。
 */
@Component
public class ProjectAccessGuard {

    private final ProjectMapper projectMapper;
    private final AnalysisRecordMapper analysisRecordMapper;

    public ProjectAccessGuard(ProjectMapper projectMapper, AnalysisRecordMapper analysisRecordMapper) {
        this.projectMapper = projectMapper;
        this.analysisRecordMapper = analysisRecordMapper;
    }

    /**
     * 校验当前用户对该项目有权限。管理员直接放行。
     * 项目不存在也报 403 而非 404——否则可以靠状态码差异探测出别人项目的 id。
     */
    public void assertOwned(Long projectId) {
        if (projectId == null) {
            throw new ForbiddenException("缺少项目标识");
        }
        if (UserContext.isAdmin()) {
            return;
        }
        Long ownerId = projectMapper.findOwnerId(projectId);
        if (ownerId == null || !ownerId.equals(UserContext.currentUserId())) {
            throw new ForbiddenException("无权访问该项目");
        }
    }

    /** 分析记录按其所属项目判权。 */
    public void assertRecordOwned(Long recordId) {
        if (recordId == null) {
            throw new ForbiddenException("缺少记录标识");
        }
        if (UserContext.isAdmin()) {
            return;
        }
        Long projectId = analysisRecordMapper.findProjectId(recordId);
        if (projectId == null) {
            throw new ForbiddenException("无权访问该分析记录");
        }
        assertOwned(projectId);
    }

    /** 异步任务按提交时盖的章判权。 */
    public void assertTaskOwned(Long taskOwnerId) {
        if (UserContext.isAdmin()) {
            return;
        }
        if (taskOwnerId == null || !taskOwnerId.equals(UserContext.currentUserId())) {
            throw new ForbiddenException("无权访问该任务");
        }
    }

    public void assertAdmin() {
        if (!UserContext.isAdmin()) {
            throw new ForbiddenException("需要管理员权限");
        }
    }

    /**
     * 列表查询的归属过滤条件：管理员返回 null（不过滤，看全部），普通用户返回自己的 id。
     * 一处逻辑同时支撑两种角色，避免在每个 service 里写 if-admin 分支。
     */
    public Long ownerFilter() {
        return UserContext.isAdmin() ? null : UserContext.currentUserId();
    }
}
