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
 * Project management API. 项目私有：列表按归属过滤，其余操作先过 assertOwned；管理员通吃。
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
        return ApiResponse.ok(projectService.createProject(request, UserContext.currentUserId()));
    }

    @PutMapping("/{projectId}")
    public ApiResponse<Project> updateProject(@PathVariable Long projectId, @Valid @RequestBody CreateProjectRequest request) {
        guard.assertOwned(projectId);
        return ApiResponse.ok(projectService.updateProject(projectId, request));
    }

    @DeleteMapping("/{projectId}")
    public ApiResponse<String> deleteProject(@PathVariable Long projectId) {
        guard.assertOwned(projectId);
        // 删项目会连带清掉图谱、分析记录与磁盘源码，不可逆，先留痕再动手
        Project project = projectService.getProject(projectId);
        auditService.log("PROJECT_DELETE", "PROJECT", projectId, project == null ? null : project.getCode());
        projectService.deleteProject(projectId);
        return ApiResponse.ok("ok");
    }

    @GetMapping("/{projectId}/versions")
    public ApiResponse<List<ProjectVersion>> listVersions(@PathVariable Long projectId) {
        guard.assertOwned(projectId);
        return ApiResponse.ok(projectService.listVersions(projectId));
    }

    @DeleteMapping("/{projectId}/versions/{versionId}")
    public ApiResponse<String> deleteVersion(@PathVariable Long projectId, @PathVariable Long versionId) {
        guard.assertOwned(projectId);
        projectService.deleteVersion(projectId, versionId);
        return ApiResponse.ok("ok");
    }

    @PostMapping("/{projectId}/datasources")
    public ApiResponse<Void> saveDatasource(@PathVariable Long projectId, @Valid @RequestBody SaveDatasourceRequest request) {
        guard.assertOwned(projectId);
        projectService.saveDatasource(projectId, request);
        return ApiResponse.ok(null);
    }

    @GetMapping("/{projectId}/datasources")
    public ApiResponse<List<ProjectDatasource>> listDatasources(@PathVariable Long projectId) {
        guard.assertOwned(projectId);
        return ApiResponse.ok(projectService.listDatasources(projectId));
    }
}
