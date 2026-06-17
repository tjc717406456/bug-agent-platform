package com.tjc.bugagent.dbhub;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 调用后端内置 dbhub 能力。
 */
@Service
public class DbhubClient {
    private final DbhubDatasourceService datasourceService;
    private final DbhubQueryService queryService;

    public DbhubClient(DbhubDatasourceService datasourceService, DbhubQueryService queryService) {
        this.datasourceService = datasourceService;
        this.queryService = queryService;
    }

    public String queryEvidence(String dbhubKey, List<String> tables) {
        return describeTables(dbhubKey, tables);
    }

    public String describeTables(String dbhubKey, List<String> tables) {
        return queryService.describeTables(dbhubKey, tables);
    }

    public String queryReadonly(String dbhubKey, String sql) {
        return queryService.queryReadonly(dbhubKey, sql);
    }

    /**
     * 查询 bridge 数据源配置列表。
     */
    public List<DbhubDatasourceConfig> listDatasourceConfigs() {
        return datasourceService.listDatasourceConfigs();
    }

    /**
     * 保存 bridge 数据源配置。
     */
    public DbhubDatasourceConfig saveDatasourceConfig(DbhubDatasourceConfig config) {
        DbhubDatasourceConfig saved = datasourceService.saveDatasourceConfig(config);
        queryService.evictDatasource(saved.getKey());
        return saved;
    }

    /**
     * 测试 bridge 数据源配置。
     */
    public String testDatasourceConfig(DbhubDatasourceConfig config) {
        return queryService.testDatasourceConfig(config);
    }

    /**
     * 删除 bridge 数据源配置。
     */
    public void deleteDatasourceConfig(String key) {
        datasourceService.deleteDatasourceConfig(key);
        queryService.evictDatasource(key);
    }
}
