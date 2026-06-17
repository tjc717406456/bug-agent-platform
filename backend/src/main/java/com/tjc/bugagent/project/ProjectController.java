package com.tjc.bugagent.project;

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
 * Project management API.
 */
@RestController
@RequestMapping("/projects")
public class ProjectController {
    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @GetMapping
    public ApiResponse<List<Project>> listProjects(@RequestParam(required = false) String name,
                                                   @RequestParam(required = false) String code) {
        return ApiResponse.ok(projectService.listProjects(name, code));
    }

    @PostMapping
    public ApiResponse<Project> createProject(@Valid @RequestBody CreateProjectRequest request) {
        return ApiResponse.ok(projectService.createProject(request));
    }

    @PutMapping("/{projectId}")
    public ApiResponse<Project> updateProject(@PathVariable Long projectId, @Valid @RequestBody CreateProjectRequest request) {
        return ApiResponse.ok(projectService.updateProject(projectId, request));
    }

    @DeleteMapping("/{projectId}")
    public ApiResponse<String> deleteProject(@PathVariable Long projectId) {
        projectService.deleteProject(projectId);
        return ApiResponse.ok("ok");
    }

    @GetMapping("/{projectId}/versions")
    public ApiResponse<List<ProjectVersion>> listVersions(@PathVariable Long projectId) {
        return ApiResponse.ok(projectService.listVersions(projectId));
    }

    @PostMapping("/{projectId}/datasources")
    public ApiResponse<Void> saveDatasource(@PathVariable Long projectId, @Valid @RequestBody SaveDatasourceRequest request) {
        projectService.saveDatasource(projectId, request);
        return ApiResponse.ok(null);
    }

    @GetMapping("/{projectId}/datasources")
    public ApiResponse<List<ProjectDatasource>> listDatasources(@PathVariable Long projectId) {
        return ApiResponse.ok(projectService.listDatasources(projectId));
    }
}
