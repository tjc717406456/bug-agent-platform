package com.tjc.bugagent.codegraph.mapper;

import com.tjc.bugagent.codegraph.CodeGraphRepository;
import com.tjc.bugagent.codegraph.CodeNode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * code_node / code_edge 表的 MyBatis Mapper。SQL 见 resources/mapper/CodeGraphMapper.xml。
 * 从 CodeGraphRepository 的 JdbcTemplate 迁移而来；DDL/information_schema 仍走 JdbcTemplate。
 */
@Mapper
public interface CodeGraphMapper {

    /** 删某版本全部边 */
    void deleteEdgesByVersion(@Param("projectId") Long projectId, @Param("versionId") Long versionId);

    /** 删某版本全部节点 */
    void deleteNodesByVersion(@Param("projectId") Long projectId, @Param("versionId") Long versionId);

    /** 写入单个节点，自增 id 回写到 node.id */
    void insertNode(CodeGraphRepository.NodeInsert node);

    /** 批量写入边（一条多 values 的 insert），分批由调用方控制 */
    void batchInsertEdges(@Param("list") List<CodeGraphRepository.EdgeInsert> edges);

    /** 按 apiPath 模糊查接口路由节点 */
    List<CodeNode> findRouteNodes(@Param("projectId") Long projectId, @Param("versionId") Long versionId,
                                  @Param("apiPath") String apiPath, @Param("limit") int limit);

    /** 沿可遍历边类型查下游节点 */
    List<CodeNode> findOutboundNodes(@Param("fromNodeId") Long fromNodeId, @Param("edgeTypes") String[] edgeTypes);

    /** 沿可遍历边类型反查上游节点 */
    List<CodeNode> findInboundNodes(@Param("toNodeId") Long toNodeId, @Param("edgeTypes") String[] edgeTypes);

    /** 批量读 SQL 节点 metadata_json */
    List<String> findSqlMetadata(@Param("nodeIds") List<Long> nodeIds);

    /** 批量读表节点去重表名 */
    List<String> findTableNames(@Param("nodeIds") List<Long> nodeIds);

    /** 按 id 取单个节点 */
    CodeNode getNode(@Param("projectId") Long projectId, @Param("versionId") Long versionId,
                     @Param("nodeId") Long nodeId);

    /** 按关键字搜 name / qualified_name */
    List<CodeNode> searchNodes(@Param("projectId") Long projectId, @Param("versionId") Long versionId,
                               @Param("keyword") String keyword, @Param("nodeTypes") String[] nodeTypes,
                               @Param("limit") int limit);

    /** 在 name / qualified_name / metadata_json 上模糊搜 */
    List<CodeNode> searchNodesByMetadata(@Param("projectId") Long projectId, @Param("versionId") Long versionId,
                                         @Param("keyword") String keyword, @Param("nodeTypes") String[] nodeTypes,
                                         @Param("limit") int limit);

    /** 按类名查类/方法/Mapper 节点 */
    List<CodeNode> searchByClassName(@Param("projectId") Long projectId, @Param("versionId") Long versionId,
                                     @Param("className") String className);

    /** 查全部 Mapper 方法节点 */
    List<CodeGraphRepository.MapperNodeRef> findMapperNodes(@Param("projectId") Long projectId,
                                                            @Param("versionId") Long versionId);

    /** 按方法名查节点 id */
    List<Long> findMethodIdsByName(@Param("projectId") Long projectId, @Param("versionId") Long versionId,
                                   @Param("methodName") String methodName, @Param("limit") int limit);

    /** 按全限定方法名查 id */
    List<Long> findMethodIdByQualifiedName(@Param("projectId") Long projectId, @Param("versionId") Long versionId,
                                           @Param("qualifiedName") String qualifiedName);
}
