package com.tjc.bugagent.project.mapper;

import com.tjc.bugagent.project.ProjectDatasource;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * project_datasource 表的 MyBatis Mapper。SQL 见 resources/mapper/ProjectDatasourceMapper.xml。
 * MyBatis 迁移试点：取代原 ProjectService 里的 JdbcTemplate + 手写 RowMapper。
 */
@Mapper
public interface ProjectDatasourceMapper {

    /** 按 项目+环境 计数，判断该数据源是否已存在 */
    int countByProjectEnv(@Param("projectId") Long projectId, @Param("env") String env);

    /** 按 项目+环境 更新 dbhub_key/白名单并启用 */
    void updateByProjectEnv(@Param("dbhubKey") String dbhubKey, @Param("whitelistTables") String whitelistTables,
                            @Param("projectId") Long projectId, @Param("env") String env);

    /** 新增数据源（默认启用） */
    void insert(@Param("projectId") Long projectId, @Param("env") String env,
                @Param("dbhubKey") String dbhubKey, @Param("whitelistTables") String whitelistTables);

    /** 项目下所有数据源，按 id 倒序 */
    List<ProjectDatasource> listByProject(@Param("projectId") Long projectId);

    /** 项目下首个启用的数据源，无则 null */
    ProjectDatasource findFirstEnabled(@Param("projectId") Long projectId);
}
