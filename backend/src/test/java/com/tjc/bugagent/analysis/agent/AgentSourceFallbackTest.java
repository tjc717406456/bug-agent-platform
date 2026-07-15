package com.tjc.bugagent.analysis.agent;

import com.tjc.bugagent.codegraph.CodeGraphQueryService;
import com.tjc.bugagent.project.ProjectService;
import com.tjc.bugagent.project.ProjectVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 代码节点未命中后的源码全文降级测试。
 */
class AgentSourceFallbackTest {

    @TempDir
    Path sourceRoot;

    @Test
    void searchCodeFallsBackToStableSourceReference() throws Exception {
        Path source = sourceRoot.resolve("src/main/java/demo/SampleService.java");
        Files.createDirectories(source.getParent());
        Files.write(source, Collections.singletonList("public void targetMethod() {}"), StandardCharsets.UTF_8);
        ProjectService projectService = mock(ProjectService.class);
        ProjectVersion version = new ProjectVersion();
        version.setId(2L);
        version.setSourcePath(sourceRoot.toString());
        when(projectService.getVersion(2L)).thenReturn(version);
        CodeGraphQueryService graph = mock(CodeGraphQueryService.class);
        when(graph.searchNodesByName(1L, 2L, "targetMethod")).thenReturn(Collections.emptyList());
        SourceReader sourceReader = new SourceReader(graph, projectService);
        AgentToolExecutor executor = new AgentToolExecutor(graph, null, projectService, sourceReader,
                new AgentToolRegistry());
        AgentToolCall call = new AgentToolCall();
        call.setAction("search_code");
        call.setArguments(Collections.<String, Object>singletonMap("keyword", "targetMethod"));

        AgentToolResult result = executor.execute(call,
                new AgentToolExecutor.AgentToolContext(1L, 2L, "/sample", null));

        assertTrue(result.isOk());
        assertTrue(result.getEvidence().contains("sourceRef={\"filePath\":\"src/main/java/demo/SampleService.java\",\"line\":1}"),
                result.getEvidence());
    }
}
