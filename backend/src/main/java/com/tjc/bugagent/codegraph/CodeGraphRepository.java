package com.tjc.bugagent.codegraph;

import com.tjc.bugagent.codegraph.mapper.CodeGraphMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * code_node / code_edge 表的统一读写入口，批量写边、按需写节点并回拿自增 id。
 * 数据访问已迁到 MyBatis（CodeGraphMapper）。
 */
@Repository
public class CodeGraphRepository {
    /** 单批写入边数量，平衡内存和数据库往返次数。 */
    private static final int EDGE_BATCH_SIZE = 500;
    /** 调用链 BFS 允许的边类型，与索引写入保持一致。 */
    private static final String[] TRAVERSE_EDGE_TYPES = {
            "ROUTE_TO_METHOD", "METHOD_CALLS_METHOD", "METHOD_TO_MAPPER", "MAPPER_TO_SQL", "SQL_TO_TABLE"
    };

    private final CodeGraphMapper codeGraphMapper;

    public CodeGraphRepository(CodeGraphMapper codeGraphMapper) {
        this.codeGraphMapper = codeGraphMapper;
    }

    /** 返回指定版本的代码节点总数。 */
    public int countNodes(Long projectId, Long versionId) {
        return codeGraphMapper.countNodes(projectId, versionId);
    }

    /** 返回指定版本带源码定位的节点数。 */
    public int countLocatedNodes(Long projectId, Long versionId) {
        return codeGraphMapper.countLocatedNodes(projectId, versionId);
    }

    /** 清空某个版本的全部节点和边，重新索引前调用。 */
    public void clearVersion(Long projectId, Long versionId) {
        codeGraphMapper.deleteEdgesByVersion(projectId, versionId);
        codeGraphMapper.deleteNodesByVersion(projectId, versionId);
    }

    /**
     * 写入节点并回拿自增 id。节点 id 后续要建边，必须精确，故单条写入靠
     * MyBatis useGeneratedKeys 把自增主键回写到 NodeInsert.id；边统一走批量。
     */
    public Long insertNode(NodeInsert node) {
        codeGraphMapper.insertNode(node);
        return node.getId();
    }

    /** 批量写入边，无需回拿 id。按 EDGE_BATCH_SIZE 分批拼多 values insert。 */
    public void batchInsertEdges(List<EdgeInsert> edges) {
        if (edges.isEmpty()) {
            return;
        }
        for (int start = 0; start < edges.size(); start += EDGE_BATCH_SIZE) {
            int end = Math.min(start + EDGE_BATCH_SIZE, edges.size());
            codeGraphMapper.batchInsertEdges(edges.subList(start, end));
        }
    }

    /** 按 apiPath 模糊查接口路由节点，limit 指定返回条数。 */
    public List<CodeNode> findRouteNodes(Long projectId, Long versionId, String apiPath, int limit) {
        return codeGraphMapper.findRouteNodes(projectId, versionId, apiPath, limit);
    }

    /** 查询某个节点沿可遍历边类型指向的下游节点。 */
    public List<CodeNode> findOutboundNodes(Long fromNodeId) {
        return codeGraphMapper.findOutboundNodes(fromNodeId, TRAVERSE_EDGE_TYPES);
    }

    /** 反向查询沿可遍历边类型指向某节点的上游节点（调用者）。 */
    public List<CodeNode> findInboundNodes(Long toNodeId) {
        return codeGraphMapper.findInboundNodes(toNodeId, TRAVERSE_EDGE_TYPES);
    }

    /** 批量读取一批 SQL 节点的 metadata_json。 */
    public List<String> findSqlMetadata(List<Long> nodeIds) {
        if (nodeIds.isEmpty()) {
            return new java.util.ArrayList<String>();
        }
        return codeGraphMapper.findSqlMetadata(nodeIds);
    }

    /** 批量读取一批表节点去重后的表名。 */
    public List<String> findTableNames(List<Long> nodeIds) {
        if (nodeIds.isEmpty()) {
            return new java.util.ArrayList<String>();
        }
        return codeGraphMapper.findTableNames(nodeIds);
    }

    public CodeNode getNode(Long projectId, Long versionId, Long nodeId) {
        return codeGraphMapper.getNode(projectId, versionId, nodeId);
    }

    /**
     * 在指定节点类型上按关键字搜索 name / qualified_name，返回前 limit 条。
     */
    public List<CodeNode> searchNodes(Long projectId, Long versionId, String keyword, String[] nodeTypes, int limit) {
        return codeGraphMapper.searchNodes(projectId, versionId, keyword, nodeTypes, limit);
    }

    /** 在 metadata_json 上模糊搜索，用于按表名/字段找 SQL。 */
    public List<CodeNode> searchNodesByMetadata(Long projectId, Long versionId, String keyword, String[] nodeTypes, int limit) {
        return codeGraphMapper.searchNodesByMetadata(projectId, versionId, keyword, nodeTypes, limit);
    }

    public List<CodeNode> searchByClassName(Long projectId, Long versionId, String className) {
        return codeGraphMapper.searchByClassName(projectId, versionId, className);
    }

    /** 查指定版本下的全部 Mapper 方法节点，含 namespace.id 全限定名，用于把方法连到 Mapper。 */
    public List<MapperNodeRef> findMapperNodes(Long projectId, Long versionId) {
        return codeGraphMapper.findMapperNodes(projectId, versionId);
    }

    /** 按方法名查节点 id，符号解析失败时退回到按名字匹配。 */
    public List<Long> findMethodIdsByName(Long projectId, Long versionId, String methodName, int limit) {
        return codeGraphMapper.findMethodIdsByName(projectId, versionId, methodName, limit);
    }

    /** 按全限定方法名精确查 id，符号解析链接时优先用。 */
    public Long findMethodIdByQualifiedName(Long projectId, Long versionId, String qualifiedName) {
        List<Long> ids = codeGraphMapper.findMethodIdByQualifiedName(projectId, versionId, qualifiedName);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** 节点写入入参。自增 id 由 MyBatis 写入后回填。 */
    public static class NodeInsert {
        private Long id;
        private final Long projectId;
        private final Long versionId;
        private final String nodeType;
        private final String name;
        private final String qualifiedName;
        private final String filePath;
        private final Integer lineNo;
        private final String metadataJson;

        public NodeInsert(Long projectId, Long versionId, String nodeType, String name, String qualifiedName,
                          String filePath, Integer lineNo, String metadataJson) {
            this.projectId = projectId;
            this.versionId = versionId;
            this.nodeType = nodeType;
            this.name = name;
            this.qualifiedName = qualifiedName;
            this.filePath = filePath;
            this.lineNo = lineNo;
            this.metadataJson = metadataJson;
        }

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public Long getProjectId() { return projectId; }
        public Long getVersionId() { return versionId; }
        public String getNodeType() { return nodeType; }
        public String getName() { return name; }
        public String getQualifiedName() { return qualifiedName; }
        public String getFilePath() { return filePath; }
        public Integer getLineNo() { return lineNo; }
        public String getMetadataJson() { return metadataJson; }
    }

    /** 边写入入参。 */
    public static class EdgeInsert {
        private final Long projectId;
        private final Long versionId;
        private final Long fromNodeId;
        private final Long toNodeId;
        private final String edgeType;
        private final String metadataJson;

        public EdgeInsert(Long projectId, Long versionId, Long fromNodeId, Long toNodeId, String edgeType, String metadataJson) {
            this.projectId = projectId;
            this.versionId = versionId;
            this.fromNodeId = fromNodeId;
            this.toNodeId = toNodeId;
            this.edgeType = edgeType;
            this.metadataJson = metadataJson;
        }

        public Long getProjectId() { return projectId; }
        public Long getVersionId() { return versionId; }
        public Long getFromNodeId() { return fromNodeId; }
        public Long getToNodeId() { return toNodeId; }
        public String getEdgeType() { return edgeType; }
        public String getMetadataJson() { return metadataJson; }
    }

    /** Mapper 节点引用，含 id、方法名和 namespace.id 全限定名。 */
    public static class MapperNodeRef {
        private final Long id;
        private final String name;
        private final String qualifiedName;

        public MapperNodeRef(Long id, String name, String qualifiedName) {
            this.id = id;
            this.name = name;
            this.qualifiedName = qualifiedName;
        }

        public Long getId() { return id; }
        public String getName() { return name; }
        public String getQualifiedName() { return qualifiedName; }
    }
}
