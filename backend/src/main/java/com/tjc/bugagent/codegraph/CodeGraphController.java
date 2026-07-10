package com.tjc.bugagent.codegraph;

import com.tjc.bugagent.auth.ProjectAccessGuard;
import com.tjc.bugagent.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 代码图谱查询接口。
 */
@RestController
@RequestMapping("/projects/{projectId}/versions/{versionId}/codegraph")
public class CodeGraphController {
    private final CodeGraphQueryService codeGraphQueryService;
    private final ProjectAccessGuard guard;

    public CodeGraphController(CodeGraphQueryService codeGraphQueryService, ProjectAccessGuard guard) {
        this.codeGraphQueryService = codeGraphQueryService;
        this.guard = guard;
    }

    /**
     * 查询版本里的接口路由。
     */
    @GetMapping("/routes")
    public ApiResponse<List<ApiRouteOption>> listRoutes(@PathVariable Long projectId,
                                                        @PathVariable Long versionId,
                                                        @RequestParam(required = false) String keyword) {
        guard.assertOwned(projectId);
        return ApiResponse.ok(codeGraphQueryService.listApiRoutes(projectId, versionId, keyword));
    }
}
