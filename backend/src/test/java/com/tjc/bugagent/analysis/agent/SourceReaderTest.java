package com.tjc.bugagent.analysis.agent;

import com.tjc.bugagent.codegraph.CodeGraphQueryService;
import com.tjc.bugagent.project.ProjectService;
import com.tjc.bugagent.project.ProjectVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 按文件和行号读取源码的路径护栏测试。
 */
class SourceReaderTest {

    @TempDir
    Path sourceRoot;

    @Test
    void readsRequestedSourceWindowWithRealLineNumbers() throws Exception {
        Path source = sourceRoot.resolve("src/main/java/demo/Sample.java");
        Files.createDirectories(source.getParent());
        Files.write(source, Arrays.asList("line1", "line2", "line3", "line4", "line5"),
                StandardCharsets.UTF_8);
        SourceReader reader = readerFor(sourceRoot);

        String snippet = reader.readSourceRange("src/main/java/demo/Sample.java", 2L, 3, 1);

        assertTrue(snippet.contains("2: line2"), snippet);
        assertTrue(snippet.contains("3: line3"), snippet);
        assertTrue(snippet.contains("4: line4"), snippet);
        assertFalse(snippet.contains("5: line5"), snippet);
    }

    @Test
    void rejectsSourceOutsideVersionRoot() throws Exception {
        Path outside = Files.createTempFile("outside-source", ".java");
        try {
            String snippet = readerFor(sourceRoot).readSourceRange(outside.toString(), 2L, 1, 1);
            assertTrue(snippet.startsWith("读取源码失败:"), snippet);
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    private SourceReader readerFor(Path root) {
        ProjectService projectService = mock(ProjectService.class);
        ProjectVersion version = new ProjectVersion();
        version.setId(2L);
        version.setSourcePath(root.toString());
        when(projectService.getVersion(2L)).thenReturn(version);
        return new SourceReader(mock(CodeGraphQueryService.class), projectService);
    }
}
