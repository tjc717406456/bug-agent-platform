package com.tjc.bugagent.project;

import com.tjc.bugagent.audit.AuditService;
import com.tjc.bugagent.auth.ProjectAccessGuard;
import com.tjc.bugagent.auth.UserContext;
import com.tjc.bugagent.common.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/**
 * Project management API. 项目由管理员维护（建/改/删/授权），普通用户凭授权可见：
 * 列表按「自有 ∪ 被授权」过滤，读接口过 assertCanAccess，写接口一律 assertAdmin。
 */
@RestController
@RequestMapping("/projects")
public class ProjectController {
    private final ProjectService projectService;
    private final ProjectAccessGuard guard;
    private final AuditService auditService;

    public ProjectController(ProjectService projectService, ProjectAccessGuard guard, AuditService auditService) {
        this.projectService = projectService;
        this.guard = guard;
        this.auditService = auditService;
    }

    @GetMapping
    public ApiResponse<List<Project>> listProjects(@RequestParam(required = false) String name,
                                                   @RequestParam(required = false) String code) {
        return ApiResponse.ok(projectService.listProjects(name, code, guard.ownerFilter()));
    }

    @PostMapping
    public ApiResponse<Project> createProject(@Valid @RequestBody CreateProjectRequest request) {
        guard.assertAdmin();
        return ApiResponse.ok(projectService.createProject(request, UserContext.currentUserId()));
    }

    @PutMapping("/{projectId}")
    public ApiResponse<Project> updateProject(@PathVariable Long projectId, @Valid @RequestBody CreateProjectRequest request) {
        guard.assertAdmin();
        return ApiResponse.ok(projectService.updateProject(projectId, request));
    }

    @DeleteMapping("/{projectId}")
    public ApiResponse<String> deleteProject(@PathVariable Long projectId) {
        guard.assertAdmin();
        // 删项目会连带清掉图谱、分析记录与磁盘源码，不可逆，先留痕再动手
        Project project = projectService.getProject(projectId);
        auditService.log("PROJECT_DELETE", "PROJECT", projectId, project == null ? null : project.getCode());
        projectService.deleteProject(projectId);
        return ApiResponse.ok("ok");
    }

    @GetMapping("/{projectId}/versions")
    public ApiResponse<List<ProjectVersion>> listVersions(@PathVariable Long projectId) {
        guard.assertCanAccess(projectId);
        return ApiResponse.ok(projectService.listVersions(projectId));
    }

    @DeleteMapping("/{projectId}/versions/{versionId}")
    public ApiResponse<String> deleteVersion(@PathVariable Long projectId, @PathVariable Long versionId) {
        guard.assertAdmin();
        projectService.deleteVersion(projectId, versionId);
        return ApiResponse.ok("ok");
    }

    @PostMapping("/{projectId}/datasources")
    public ApiResponse<Void> saveDatasource(@PathVariable Long projectId, @Valid @RequestBody SaveDatasourceRequest request) {
        guard.assertAdmin();
        projectService.saveDatasource(projectId, request);
        return ApiResponse.ok(null);
    }

    // 数据源绑定的「读」留给成员：分析台要展示当前项目绑的是哪个库（只回 key，不含凭据）
    @GetMapping("/{projectId}/datasources")
    public ApiResponse<List<ProjectDatasource>> listDatasources(@PathVariable Long projectId) {
        guard.assertCanAccess(projectId);
        return ApiResponse.ok(projectService.listDatasources(projectId));
    }

    /** 保存项目的跨环境表结构复用规则。 */
    @PutMapping("/{projectId}/datasource-policy")
    public ApiResponse<Project> saveDatasourcePolicy(@PathVariable Long projectId,
                                                      @Valid @RequestBody ProjectDatasourcePolicyRequest request) {
        guard.assertAdmin();
        projectService.saveDatasourcePolicy(projectId, request);
        return ApiResponse.ok(projectService.getProject(projectId));
    }

    /** 项目可见范围：已授权的用户 id 列表。 */
    @GetMapping("/{projectId}/members")
    public ApiResponse<List<Long>> listMembers(@PathVariable Long projectId) {
        guard.assertAdmin();
        return ApiResponse.ok(projectService.listMembers(projectId));
    }

    /** 全量替换项目可见范围。 */
    @PutMapping("/{projectId}/members")
    public ApiResponse<String> saveMembers(@PathVariable Long projectId, @RequestBody List<Long> userIds) {
        guard.assertAdmin();
        projectService.saveMembers(projectId, userIds);
        auditService.log("PROJECT_MEMBER_CHANGE", "PROJECT", projectId,
                userIds == null ? "[]" : String.valueOf(userIds));
        return ApiResponse.ok("ok");
    }
}
