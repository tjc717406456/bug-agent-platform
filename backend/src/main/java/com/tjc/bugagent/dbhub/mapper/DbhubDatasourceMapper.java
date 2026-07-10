package com.tjc.bugagent.dbhub.mapper;

import com.tjc.bugagent.dbhub.DbhubDatasourceConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * dbhub_datasource_config 表的 MyBatis Mapper。SQL 见 resources/mapper/DbhubDatasourceMapper.xml。
 * 实体字段 key/user/database 与列名不同名，XML 里用 select 别名对齐，故无需 resultMap。
 */
@Mapper
public interface DbhubDatasourceMapper {

    /** 全部数据源配置，按 id 倒序 */
    List<DbhubDatasourceConfig> listAll();

    /** 仅数据源 key 与库名：给普通用户挑选绑定项，连 host/账号/密码列都不 select */
    List<DbhubDatasourceConfig> listKeys();

    /** 按 key 查单个配置，无则 null */
    DbhubDatasourceConfig getByKey(@Param("key") String key);

    /** 按 key 计数，判断是否已存在 */
    int countByKey(@Param("key") String key);

    /** 按 key 更新配置 */
    void updateByKey(@Param("host") String host, @Param("port") Integer port, @Param("user") String user,
                     @Param("password") String password, @Param("database") String database, @Param("key") String key);

    /** 新增配置 */
    void insert(@Param("key") String key, @Param("host") String host, @Param("port") Integer port,
                @Param("user") String user, @Param("password") String password, @Param("database") String database);

    /** 按 key 删除 */
    void deleteByKey(@Param("key") String key);
}
