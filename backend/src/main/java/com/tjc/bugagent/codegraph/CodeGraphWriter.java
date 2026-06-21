package com.tjc.bugagent.codegraph;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * 代码图谱的写入入口，统一收口节点和边的落库，名称/路径超长在这里截断。
 * Java、XML 两条索引链路都通过它写图，避免各自直连仓库。
 */
@Component
public class CodeGraphWriter {

    private final CodeGraphRepository codeGraphRepository;

    public CodeGraphWriter(CodeGraphRepository codeGraphRepository) {
        this.codeGraphRepository = codeGraphRepository;
    }

    /** 插入一个图谱节点，返回自增主键。 */
    public Long addNode(Long projectId, Long versionId, String type, String name, String qualifiedName,
                        Path file, Integer lineNo, String metadataJson) {
        return codeGraphRepository.insertNode(new CodeGraphRepository.NodeInsert(
                projectId, versionId, type, CodeGraphText.trim(name, 250), CodeGraphText.trim(qualifiedName, 760),
                file == null ? null : CodeGraphText.trim(file.toString(), 760), lineNo, metadataJson));
    }

    /** 插入单条边。 */
    public void addEdge(Long projectId, Long versionId, Long fromNodeId, Long toNodeId, String edgeType, String metadataJson) {
        codeGraphRepository.batchInsertEdges(Collections.singletonList(
                new CodeGraphRepository.EdgeInsert(projectId, versionId, fromNodeId, toNodeId, edgeType, metadataJson)));
    }

    /** 批量插入边，建调用链/Mapper 关联时一次性落库。 */
    public void batchInsertEdges(List<CodeGraphRepository.EdgeInsert> edges) {
        codeGraphRepository.batchInsertEdges(edges);
    }
}
