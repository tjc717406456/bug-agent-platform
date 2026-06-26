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
        List<CodeNode> routes = codeGraphRepository.findRouteNodes(projectId, versionId, apiPath, 20);
        // LIKE 命中不了时，按模板匹配兜底：运行时真实路径 /user/123 也能对上索引的 /user/{id}
        if (routes.isEmpty()) {
            routes = matchByTemplate(projectId, versionId, apiPath);
        }
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
        // 下拉要列全项目接口，大工程路由轻松上几百条，上限放到 1000 兜底全量；配合关键词远程搜索按需收窄
        List<CodeNode> routes = codeGraphRepository.findRouteNodes(
                projectId, versionId, keyword == null ? "" : keyword.trim(), 1000);
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

    /** 反向查某节点的上游调用者，用于从某方法往上回溯根因。 */
    public List<CodeNode> findCallers(Long nodeId) {
        return codeGraphRepository.findInboundNodes(nodeId);
    }

    /** 搜索 SQL 节点，用于 Agent 根据表名、字段名或 Mapper 方法继续追证据。 */
    public List<CodeNode> searchSqlNodes(Long projectId, Long versionId, String keyword) {
        return codeGraphRepository.searchNodesByMetadata(projectId, versionId, keyword, SQL_LIKE_TYPES, 20);
    }

    public List<CodeNode> searchNodesByClassName(Long projectId, Long versionId, String className) {
        return codeGraphRepository.searchByClassName(projectId, versionId, className);
    }

    /**
     * 把运行时真实路径按路由模板匹配，解决 /user/123 对不上索引的 /user/{id}。
     * 只比带占位符的模板，无占位符的已由 LIKE 覆盖。
     */
    private List<CodeNode> matchByTemplate(Long projectId, Long versionId, String apiPath) {
        String path = stripQuery(apiPath);
        // 传空串配合 XML 的 concat('%', ?, '%') = '%%'，捞全部路由再按模板逐一匹配
        List<CodeNode> all = codeGraphRepository.findRouteNodes(projectId, versionId, "", 500);
        List<CodeNode> matched = new ArrayList<CodeNode>();
        for (CodeNode route : all) {
            if (templateMatches(route.getName(), path)) {
                matched.add(route);
            }
        }
        return matched;
    }

    private boolean templateMatches(String template, String path) {
        if (template == null || template.indexOf('{') < 0) {
            return false;
        }
        // /user/{id} → ^/user/[^/]+/?$，占位符吃掉一个路径段
        String regex = "^" + template.replaceAll("\\{[^/}]+}", "[^/]+") + "/?$";
        try {
            return path.matches(regex);
        } catch (Exception exception) {
            return false;
        }
    }

    private String stripQuery(String apiPath) {
        if (apiPath == null) {
            return "";
        }
        int queryIndex = apiPath.indexOf('?');
        return queryIndex >= 0 ? apiPath.substring(0, queryIndex) : apiPath;
    }
}
