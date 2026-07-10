package com.tjc.bugagent.source;

import com.tjc.bugagent.auth.ProjectAccessGuard;
import com.tjc.bugagent.common.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.validation.Valid;

/**
 * Source import API.
 */
@RestController
@RequestMapping("/projects/{projectId}/sources")
public class SourceImportController {
    private final SourceImportService sourceImportService;
    private final ProjectAccessGuard guard;

    public SourceImportController(SourceImportService sourceImportService, ProjectAccessGuard guard) {
        this.sourceImportService = sourceImportService;
        this.guard = guard;
    }

    @PostMapping("/git")
    public ApiResponse<Long> importGit(@PathVariable Long projectId, @Valid @RequestBody GitImportRequest request) throws Exception {
        guard.assertOwned(projectId);
        return ApiResponse.ok(sourceImportService.importGit(projectId, request));
    }

    @PostMapping("/zip")
    public ApiResponse<Long> importZip(@PathVariable Long projectId, @RequestParam("file") MultipartFile file) throws Exception {
        guard.assertOwned(projectId);
        return ApiResponse.ok(sourceImportService.importZip(projectId, file));
    }
}

