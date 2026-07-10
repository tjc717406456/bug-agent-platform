package com.tjc.bugagent.auth;

import com.tjc.bugagent.analysis.mapper.AnalysisRecordMapper;
import com.tjc.bugagent.project.mapper.ProjectMapper;
import com.tjc.bugagent.project.mapper.ProjectMemberMapper;
import org.springframework.stereotype.Component;

/**
 * 资源访问校验的唯一入口。项目由管理员维护，普通用户凭 project_member 授权可见并可分析；
 * 其余资源（版本、数据源、分析记录、代码图谱）都依附项目，一律换算成 projectId 后走同一道闸。
 * 维护类动作（建/改/删项目、导源码、绑数据源）直接 assertAdmin。
 */
@Component
public class ProjectAccessGuard {

    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final AnalysisRecordMapper analysisRecordMapper;

    public ProjectAccessGuard(ProjectMapper projectMapper, ProjectMemberMapper projectMemberMapper,
                              AnalysisRecordMapper analysisRecordMapper) {
        this.projectMapper = projectMapper;
        this.projectMemberMapper = projectMemberMapper;
        this.analysisRecordMapper = analysisRecordMapper;
    }

    /**
     * 校验当前用户能访问该项目：管理员、项目所有者、被授权成员三者之一。
     * 项目不存在也报 403 而非 404——否则可以靠状态码差异探测出别人项目的 id。
     */
    public void assertCanAccess(Long projectId) {
        if (projectId == null) {
            throw new ForbiddenException("缺少项目标识");
        }
        if (UserContext.isAdmin()) {
            return;
        }
        Long userId = UserContext.currentUserId();
        Long ownerId = projectMapper.findOwnerId(projectId);
        if (ownerId != null && ownerId.equals(userId)) {
            return;
        }
        if (projectMemberMapper.countMember(projectId, userId) > 0) {
            return;
        }
        throw new ForbiddenException("无权访问该项目");
    }

    /** 分析记录按其所属项目判权：项目成员共享历史。 */
    public void assertRecordAccessible(Long recordId) {
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
        assertCanAccess(projectId);
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
     * 列表查询的可见性过滤条件：管理员返回 null（不过滤，看全部），普通用户返回自己的 id，
     * mapper 里按「自有项目 ∪ 被授权项目」过滤。一处逻辑同时支撑两种角色。
     */
    public Long ownerFilter() {
        return UserContext.isAdmin() ? null : UserContext.currentUserId();
    }
}
