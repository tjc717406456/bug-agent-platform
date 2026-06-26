package com.tjc.bugagent.dbhub;

import com.tjc.bugagent.dbhub.mapper.DbhubDatasourceMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 管理后端内置的 dbhub 数据源配置。
 */
@Service
public class DbhubDatasourceService {
    private final DbhubDatasourceMapper dbhubDatasourceMapper;

    public DbhubDatasourceService(DbhubDatasourceMapper dbhubDatasourceMapper) {
        this.dbhubDatasourceMapper = dbhubDatasourceMapper;
    }

    /**
     * 查询全部数据源配置。
     */
    public List<DbhubDatasourceConfig> listDatasourceConfigs() {
        List<DbhubDatasourceConfig> configs = dbhubDatasourceMapper.listAll();
        for (DbhubDatasourceConfig config : configs) {
            maskPassword(config);
        }
        return configs;
    }

    /**
     * 根据 key 查询数据源配置。
     */
    public DbhubDatasourceConfig getDatasourceConfig(String key) {
        return dbhubDatasourceMapper.getByKey(key);
    }

    /**
     * 保存数据源配置。
     */
    public DbhubDatasourceConfig saveDatasourceConfig(DbhubDatasourceConfig config) {
        validateConfig(config);
        int count = dbhubDatasourceMapper.countByKey(config.getKey());
        if (count > 0) {
            DbhubDatasourceConfig oldConfig = getDatasourceConfig(config.getKey());
            String password = isBlank(config.getPassword()) && oldConfig != null ? oldConfig.getPassword() : config.getPassword();
            dbhubDatasourceMapper.updateByKey(
                    config.getHost(), config.getPort(), config.getUser(), safe(password), config.getDatabase(), config.getKey());
            DbhubDatasourceConfig saved = getDatasourceConfig(config.getKey());
            maskPassword(saved);
            return saved;
        }
        dbhubDatasourceMapper.insert(
                config.getKey(), config.getHost(), config.getPort(), config.getUser(), safe(config.getPassword()), config.getDatabase());
        DbhubDatasourceConfig saved = getDatasourceConfig(config.getKey());
        maskPassword(saved);
        return saved;
    }

    /**
     * 删除数据源配置。
     */
    public void deleteDatasourceConfig(String key) {
        dbhubDatasourceMapper.deleteByKey(key);
    }

    private void validateConfig(DbhubDatasourceConfig config) {
        if (config == null || isBlank(config.getKey()) || !config.getKey().matches("^[A-Za-z0-9_-]{1,64}$")) {
            throw new IllegalArgumentException("Invalid datasource key");
        }
        if (isBlank(config.getHost()) || config.getPort() == null || config.getPort() <= 0 || isBlank(config.getUser()) || isBlank(config.getDatabase())) {
            throw new IllegalArgumentException("Missing datasource config");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private void maskPassword(DbhubDatasourceConfig config) {
        if (config != null && !isBlank(config.getPassword())) {
            config.setPassword("******");
        }
    }
}
