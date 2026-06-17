package com.tjc.bugagent.codegraph;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 从代码图谱读证据，供 Agent 和分析流程使用。
 */
@Service
public class CodeGraphQueryService {
    /** 调用链展开的节点类型白名单。 */
    private static final String[] METHOD_LIKE_TYPES = {"METHOD", "MAPPER", "MAPPER_METHOD"};
    private static final String[] SQL_LIKE_TYPES = {"SQL", "MAPPER_METHOD"};

    private final CodeGraphRepository codeGraphRepository;

    public CodeGraphQueryService(CodeGraphRepository codeGraphRepository) {
        this.codeGraphRepository = codeGraphRepository;
    }

    public CodeGraphQueryResult queryByApiPath(Long projectId, Long versionId, String apiPath) {
        CodeGraphQueryResult result = new CodeGraphQueryResult();
        List<CodeNode> routes = codeGraphRepository.findRouteNodes(projectId, versionId, "%" + apiPath + "%");
        result.setRouteNodes(routes);
        Set<Long> visitedNodeIds = new LinkedHashSet<Long>();
        List<CodeNode> relatedNodes = new ArrayList<CodeNode>();
        for (CodeNode route : routes) {
            expand(route.getId(), 0, visitedNodeIds, relatedNodes);
        }
        result.setRelatedNodes(relatedNodes);
        result.setSqlTexts(collectSqlTexts(visitedNodeIds));
        result.setTables(collectTables(visitedNodeIds));
        return result;
    }

    /**
     * 沿可遍历边类型做 BFS，限制深度和总节点数，避免图谱过大时拖垮查询。
     */
    private void expand(Long nodeId, int depth, Set<Long> visitedNodeIds, List<CodeNode> relatedNodes) {
        if (depth >= 5 || visitedNodeIds.contains(nodeId) || visitedNodeIds.size() > 120) {
            return;
        }
        visitedNodeIds.add(nodeId);
        List<CodeNode> nextNodes = codeGraphRepository.findOutboundNodes(nodeId);
        for (CodeNode nextNode : nextNodes) {
            if (!visitedNodeIds.contains(nextNode.getId())) {
                relatedNodes.add(nextNode);
            }
            expand(nextNode.getId(), depth + 1, visitedNodeIds, relatedNodes);
        }
    }

    private List<String> collectSqlTexts(Set<Long> nodeIds) {
        return codeGraphRepository.findSqlMetadata(new ArrayList<Long>(nodeIds));
    }

    private List<String> collectTables(Set<Long> nodeIds) {
        return codeGraphRepository.findTableNames(new ArrayList<Long>(nodeIds));
    }

    public List<CodeNode> searchNodesByName(Long projectId, Long versionId, String namePart) {
        return codeGraphRepository.searchNodes(projectId, versionId, namePart, METHOD_LIKE_TYPES, 10);
    }

    /** 查询指定版本已索引的接口路由。 */
    public List<ApiRouteOption> listApiRoutes(Long projectId, Long versionId, String keyword) {
        List<CodeNode> routes = codeGraphRepository.findRouteNodes(
                projectId, versionId, "%" + (keyword == null ? "" : keyword.trim()) + "%", 100);
        List<ApiRouteOption> options = new ArrayList<ApiRouteOption>(routes.size());
        for (CodeNode route : routes) {
            ApiRouteOption option = new ApiRouteOption();
            option.setId(route.getId());
            option.setPath(route.getName());
            option.setFilePath(route.getFilePath());
            option.setLineNo(route.getLineNo());
            options.add(option);
        }
        return options;
    }

    /** 按节点 ID 读取索引节点。 */
    public CodeNode getNode(Long projectId, Long versionId, Long nodeId) {
        return codeGraphRepository.getNode(projectId, versionId, nodeId);
    }

    /** 搜索 SQL 节点，用于 Agent 根据表名、字段名或 Mapper 方法继续追证据。 */
    public List<CodeNode> searchSqlNodes(Long projectId, Long versionId, String keyword) {
        return codeGraphRepository.searchNodesByMetadata(projectId, versionId, keyword, SQL_LIKE_TYPES, 20);
    }

    public List<CodeNode> searchNodesByClassName(Long projectId, Long versionId, String className) {
        return codeGraphRepository.searchByClassName(projectId, versionId, className);
    }
}
