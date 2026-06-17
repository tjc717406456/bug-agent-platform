package com.tjc.bugagent.dbhub;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * 管理后端内置的 dbhub 数据源配置。
 */
@Service
public class DbhubDatasourceService {
    private final JdbcTemplate jdbcTemplate;

    public DbhubDatasourceService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 查询全部数据源配置。
     */
    public List<DbhubDatasourceConfig> listDatasourceConfigs() {
        List<DbhubDatasourceConfig> configs = jdbcTemplate.query(
                "select datasource_key, host, port, username, password, database_name from dbhub_datasource_config order by id desc",
                new DbhubDatasourceConfigMapper());
        for (DbhubDatasourceConfig config : configs) {
            maskPassword(config);
        }
        return configs;
    }

    /**
     * 根据 key 查询数据源配置。
     */
    public DbhubDatasourceConfig getDatasourceConfig(String key) {
        List<DbhubDatasourceConfig> list = jdbcTemplate.query(
                "select datasource_key, host, port, username, password, database_name from dbhub_datasource_config where datasource_key = ?",
                new DbhubDatasourceConfigMapper(), key);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 保存数据源配置。
     */
    public DbhubDatasourceConfig saveDatasourceConfig(DbhubDatasourceConfig config) {
        validateConfig(config);
        Integer count = jdbcTemplate.queryForObject(
                "select count(1) from dbhub_datasource_config where datasource_key = ?",
                Integer.class, config.getKey());
        if (count != null && count > 0) {
            DbhubDatasourceConfig oldConfig = getDatasourceConfig(config.getKey());
            String password = isBlank(config.getPassword()) && oldConfig != null ? oldConfig.getPassword() : config.getPassword();
            jdbcTemplate.update(
                    "update dbhub_datasource_config set host = ?, port = ?, username = ?, password = ?, database_name = ?, updated_at = now() where datasource_key = ?",
                    config.getHost(), config.getPort(), config.getUser(), safe(password), config.getDatabase(), config.getKey());
            DbhubDatasourceConfig saved = getDatasourceConfig(config.getKey());
            maskPassword(saved);
            return saved;
        }
        jdbcTemplate.update(
                "insert into dbhub_datasource_config(datasource_key, host, port, username, password, database_name, created_at, updated_at) values (?, ?, ?, ?, ?, ?, now(), now())",
                config.getKey(), config.getHost(), config.getPort(), config.getUser(), safe(config.getPassword()), config.getDatabase());
        DbhubDatasourceConfig saved = getDatasourceConfig(config.getKey());
        maskPassword(saved);
        return saved;
    }

    /**
     * 删除数据源配置。
     */
    public void deleteDatasourceConfig(String key) {
        jdbcTemplate.update("delete from dbhub_datasource_config where datasource_key = ?", key);
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

    private static class DbhubDatasourceConfigMapper implements RowMapper<DbhubDatasourceConfig> {
        @Override
        public DbhubDatasourceConfig mapRow(ResultSet rs, int rowNum) throws SQLException {
            DbhubDatasourceConfig config = new DbhubDatasourceConfig();
            config.setKey(rs.getString("datasource_key"));
            config.setHost(rs.getString("host"));
            config.setPort(rs.getInt("port"));
            config.setUser(rs.getString("username"));
            config.setPassword(rs.getString("password"));
            config.setDatabase(rs.getString("database_name"));
            return config;
        }
    }
}
