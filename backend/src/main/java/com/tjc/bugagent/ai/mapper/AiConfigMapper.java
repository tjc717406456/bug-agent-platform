package com.tjc.bugagent.ai.mapper;

import com.tjc.bugagent.ai.AiConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * ai_provider_config 表的 MyBatis Mapper。SQL 见 resources/mapper/AiConfigMapper.xml。
 * 查询出的 api_key 仍是密文（列 api_key_cipher 别名为 api_key），解密由 Service 负责。
 */
@Mapper
public interface AiConfigMapper {

    /** 全部配置，按 id 升序 */
    List<AiConfig> listAll();

    /** 总条数 */
    int countAll();

    /** 按 id 计数，判断是否存在 */
    int countById(@Param("id") Long id);

    /** 新增一条，回填自增主键 */
    void insert(AiConfig config);

    /** 编辑配置但不动 api_key */
    void updateKeepKey(AiConfig config);

    /** 编辑配置并更新 api_key */
    void updateWithKey(AiConfig config);

    /** 启用的 EMBEDDING 配置，id 倒序取一条 */
    AiConfig findEnabledEmbedding();

    /** UTILITY 配置，id 倒序取一条 */
    AiConfig findUtility();

    /** 取某条的 role */
    String findRoleById(@Param("id") Long id);

    /** 翻转某条的启用位（embedding 独立开关用） */
    void toggleEnabled(@Param("id") Long id);

    /** 关停所有非 EMBEDDING 行的启用位 */
    void disableAllNonEmbedding();

    /** 启用某条 */
    void enableById(@Param("id") Long id);

    /** 删除某条 */
    void deleteById(@Param("id") Long id);

    /** 启用且非 EMBEDDING 的配置，id 倒序取一条 */
    AiConfig findEnabledConfig();

    /** 剩余最新一条的 id，无则 null */
    Long findLatestId();
}
