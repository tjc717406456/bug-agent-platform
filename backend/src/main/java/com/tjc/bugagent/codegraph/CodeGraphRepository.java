package com.tjc.bugagent.codegraph;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * code_node / code_edge 表的统一读写入口，批量写边、按需写节点并回拿自增 id。
 */
@Repository
public class CodeGraphRepository {
    /** 单批写入边数量，平衡内存和数据库往返次数。 */
    private static final int EDGE_BATCH_SIZE = 500;
    /** 调用链 BFS 允许的边类型，与索引写入保持一致。 */
    private static final String[] TRAVERSE_EDGE_TYPES = {
            "ROUTE_TO_METHOD", "METHOD_CALLS_METHOD", "METHOD_TO_MAPPER", "MAPPER_TO_SQL", "SQL_TO_TABLE"
    };

    private final JdbcTemplate jdbcTemplate;

    public CodeGraphRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 清空某个版本的全部节点和边，重新索引前调用。 */
    public void clearVersion(Long projectId, Long versionId) {
        jdbcTemplate.update("delete from code_edge where project_id = ? and version_id = ?", projectId, versionId);
        jdbcTemplate.update("delete from code_node where project_id = ? and version_id = ?", projectId, versionId);
    }

    /**
     * 写入节点并回拿自增 id。Spring batchUpdate 不返回逐条 generated key，
     * 而节点 id 后续要建边，必须精确，故用 KeyHolder 单条写入；边统一走批量。
     */
    public Long insertNode(NodeInsert node) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "insert into code_node(project_id, version_id, node_type, name, qualified_name, file_path, line_no, metadata_json) "
                            + "values (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, node.getProjectId());
            ps.setLong(2, node.getVersionId());
            ps.setString(3, node.getNodeType());
            ps.setString(4, node.getName());
            ps.setString(5, node.getQualifiedName());
            ps.setString(6, node.getFilePath());
            if (node.getLineNo() == null) {
                ps.setNull(7, java.sql.Types.INTEGER);
            } else {
                ps.setInt(7, node.getLineNo());
            }
            ps.setString(8, node.getMetadataJson());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? null : key.longValue();
    }

    /** 批量写入边，无需回拿 id。 */
    public void batchInsertEdges(List<EdgeInsert> edges) {
        if (edges.isEmpty()) {
            return;
        }
        String sql = "insert into code_edge(project_id, version_id, from_node_id, to_node_id, edge_type, metadata_json) "
                + "values (?, ?, ?, ?, ?, ?)";
        jdbcTemplate.batchUpdate(sql, edges, EDGE_BATCH_SIZE, (ps, edge) -> {
            ps.setLong(1, edge.getProjectId());
            ps.setLong(2, edge.getVersionId());
            ps.setLong(3, edge.getFromNodeId());
            ps.setLong(4, edge.getToNodeId());
            ps.setString(5, edge.getEdgeType());
            ps.setString(6, edge.getMetadataJson());
        });
    }

    /** 按 apiPath 模糊查接口路由节点，默认前 20 条，给分析链路用。 */
    public List<CodeNode> findRouteNodes(Long projectId, Long versionId, String apiPathLike) {
        return findRouteNodes(projectId, versionId, apiPathLike, 20);
    }

    /** 按 apiPath 模糊查接口路由节点，可指定返回条数。 */
    public List<CodeNode> findRouteNodes(Long projectId, Long versionId, String apiPathLike, int limit) {
        return jdbcTemplate.query(
                "select id, node_type, name, qualified_name, file_path, line_no, metadata_json from code_node "
                        + "where project_id = ? and version_id = ? and node_type = 'API_ROUTE' and name like ? limit " + limit,
                CodeNodeMapper.INSTANCE, projectId, versionId, apiPathLike);
    }

    /** 查询某个节点沿可遍历边类型指向的下游节点。 */
    public List<CodeNode> findOutboundNodes(Long fromNodeId) {
        String placeholders = placeholders(TRAVERSE_EDGE_TYPES.length);
        return jdbcTemplate.query(
                "select n.id, n.node_type, n.name, n.qualified_name, n.file_path, n.line_no, n.metadata_json "
                        + "from code_edge e join code_node n on e.to_node_id = n.id "
                        + "where e.from_node_id = ? and e.edge_type in (" + placeholders + ") limit 80",
                CodeNodeMapper.INSTANCE, buildOutboundParams(fromNodeId));
    }

    /** 批量读取一批 SQL 节点的 metadata_json。 */
    public List<String> findSqlMetadata(List<Long> nodeIds) {
        if (nodeIds.isEmpty()) {
            return new ArrayList<String>();
        }
        return jdbcTemplate.queryForList(
                "select metadata_json from code_node where node_type = 'SQL' and id in (" + placeholders(nodeIds.size()) + ") limit 30",
                nodeIds.toArray(), String.class);
    }

    /** 批量读取一批表节点去重后的表名。 */
    public List<String> findTableNames(List<Long> nodeIds) {
        if (nodeIds.isEmpty()) {
            return new ArrayList<String>();
        }
        return jdbcTemplate.queryForList(
                "select distinct name from code_node where node_type = 'DB_TABLE' and id in (" + placeholders(nodeIds.size()) + ") limit 50",
                nodeIds.toArray(), String.class);
    }

    public CodeNode getNode(Long projectId, Long versionId, Long nodeId) {
        List<CodeNode> nodes = jdbcTemplate.query(
                "select id, node_type, name, qualified_name, file_path, line_no, metadata_json "
                        + "from code_node where project_id = ? and version_id = ? and id = ? limit 1",
                CodeNodeMapper.INSTANCE, projectId, versionId, nodeId);
        return nodes.isEmpty() ? null : nodes.get(0);
    }

    /**
     * 在指定节点类型上按关键字搜索 name / qualified_name，返回前 limit 条。
     */
    public List<CodeNode> searchNodes(Long projectId, Long versionId, String keyword, String[] nodeTypes, int limit) {
        String like = "%" + keyword + "%";
        String typeClause = placeholders(nodeTypes.length);
        // 每个 like 条件都要独立的参数，不能复用同一个 String
        Object[] params = new Object[nodeTypes.length + 4];
        params[0] = projectId;
        params[1] = versionId;
        for (int i = 0; i < nodeTypes.length; i++) {
            params[2 + i] = nodeTypes[i];
        }
        params[params.length - 2] = like;
        params[params.length - 1] = like;
        return jdbcTemplate.query(
                "select id, node_type, name, qualified_name, file_path, line_no, metadata_json from code_node "
                        + "where project_id = ? and version_id = ? and node_type in (" + typeClause + ") "
                        + "and (name like ? or qualified_name like ?) limit " + limit,
                CodeNodeMapper.INSTANCE, params);
    }

    /** 在 metadata_json 上模糊搜索，用于按表名/字段找 SQL。 */
    public List<CodeNode> searchNodesByMetadata(Long projectId, Long versionId, String keyword, String[] nodeTypes, int limit) {
        String like = "%" + keyword + "%";
        String typeClause = placeholders(nodeTypes.length);
        Object[] params = new Object[nodeTypes.length + 5];
        params[0] = projectId;
        params[1] = versionId;
        for (int i = 0; i < nodeTypes.length; i++) {
            params[2 + i] = nodeTypes[i];
        }
        // 三个 like 各自独立参数
        params[params.length - 3] = like;
        params[params.length - 2] = like;
        params[params.length - 1] = like;
        return jdbcTemplate.query(
                "select id, node_type, name, qualified_name, file_path, line_no, metadata_json from code_node "
                        + "where project_id = ? and version_id = ? and node_type in (" + typeClause + ") "
                        + "and (name like ? or qualified_name like ? or metadata_json like ?) limit " + limit,
                CodeNodeMapper.INSTANCE, params);
    }

    public List<CodeNode> searchByClassName(Long projectId, Long versionId, String className) {
        return jdbcTemplate.query(
                "select id, node_type, name, qualified_name, file_path, line_no, metadata_json from code_node "
                        + "where project_id = ? and version_id = ? and (qualified_name = ? or qualified_name like ?) "
                        + "and node_type in ('CLASS','METHOD','MAPPER','MAPPER_METHOD') "
                        + "order by case when node_type = 'CLASS' then 0 else 1 end, line_no limit 30",
                CodeNodeMapper.INSTANCE, projectId, versionId, className, className + ".%");
    }

    /** 查指定版本下的全部 Mapper 方法节点，含 namespace.id 全限定名，用于把方法连到 Mapper。 */
    public List<MapperNodeRef> findMapperNodes(Long projectId, Long versionId) {
        return jdbcTemplate.query(
                "select id, name, qualified_name from code_node where project_id = ? and version_id = ? and node_type = 'MAPPER_METHOD'",
                (rs, rowNum) -> new MapperNodeRef(rs.getLong("id"), rs.getString("name"), rs.getString("qualified_name")),
                projectId, versionId);
    }

    /** 按方法名查节点 id，符号解析失败时退回到按名字匹配。 */
    public List<Long> findMethodIdsByName(Long projectId, Long versionId, String methodName, int limit) {
        return jdbcTemplate.queryForList(
                "select id from code_node where project_id = ? and version_id = ? and node_type = 'METHOD' and name = ? limit " + limit,
                Long.class, projectId, versionId, methodName);
    }

    /** 按全限定方法名精确查 id，符号解析链接时优先用。 */
    public Long findMethodIdByQualifiedName(Long projectId, Long versionId, String qualifiedName) {
        List<Long> ids = jdbcTemplate.queryForList(
                "select id from code_node where project_id = ? and version_id = ? and node_type = 'METHOD' and qualified_name = ? limit 1",
                Long.class, projectId, versionId, qualifiedName);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private Object[] buildOutboundParams(Long fromNodeId) {
        Object[] params = new Object[1 + TRAVERSE_EDGE_TYPES.length];
        params[0] = fromNodeId;
        for (int i = 0; i < TRAVERSE_EDGE_TYPES.length; i++) {
            params[1 + i] = TRAVERSE_EDGE_TYPES[i];
        }
        return params;
    }

    private String placeholders(int size) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < size; index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append('?');
        }
        return builder.toString();
    }

    private enum CodeNodeMapper implements RowMapper<CodeNode> {
        INSTANCE;

        @Override
        public CodeNode mapRow(ResultSet rs, int rowNum) throws SQLException {
            CodeNode node = new CodeNode();
            node.setId(rs.getLong("id"));
            node.setNodeType(rs.getString("node_type"));
            node.setName(rs.getString("name"));
            node.setQualifiedName(rs.getString("qualified_name"));
            node.setFilePath(rs.getString("file_path"));
            int lineNo = rs.getInt("line_no");
            node.setLineNo(rs.wasNull() ? null : lineNo);
            node.setMetadataJson(rs.getString("metadata_json"));
            return node;
        }
    }

    /** 节点写入入参。 */
    public static class NodeInsert {
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
