package com.tjc.bugagent.dbhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tjc.bugagent.config.AppProperties;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 执行后端内置的只读数据库取证查询。
 */
@Service
public class DbhubQueryService {
    private final DbhubDatasourceService datasourceService;
    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;
    private final Map<String, HikariDataSource> dataSourceCache = new ConcurrentHashMap<String, HikariDataSource>();

    public DbhubQueryService(DbhubDatasourceService datasourceService, ObjectMapper objectMapper, AppProperties appProperties) {
        this.datasourceService = datasourceService;
        this.objectMapper = objectMapper;
        this.appProperties = appProperties;
    }

    /**
     * 测试数据源连接。
     */
    public String testDatasourceConfig(DbhubDatasourceConfig config) {
        DbhubDatasourceConfig actualConfig = resolveConfig(config);
        try (Connection connection = createDataSource(actualConfig).getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("select 1 as ok")) {
            return resultSet.next() && resultSet.getInt("ok") == 1 ? "ok" : "failed";
        } catch (Exception exception) {
            throw new IllegalStateException(exception.getMessage(), exception);
        }
    }

    /**
     * 查询表结构、数据量和最近样例。
     */
    public String describeTables(String datasourceKey, List<String> tables) {
        if (isBlank(datasourceKey)) {
            return "No dbhub datasource is configured.";
        }
        DbhubDatasourceConfig config = datasourceService.getDatasourceConfig(datasourceKey);
        if (config == null) {
            return "Unknown datasource: " + datasourceKey;
        }
        if (tables == null || tables.isEmpty()) {
            return "No tables to describe";
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        try (Connection connection = getConnection(config)) {
            for (String tableName : tables) {
                result.put(tableName, describeTable(connection, config.getDatabase(), tableName));
            }
        } catch (Exception exception) {
            return "dbhub call failed: " + exception.getMessage();
        }
        return wrapResult(result);
    }

    /**
     * 执行只读 SQL。
     */
    public String queryReadonly(String datasourceKey, String sql) {
        if (isBlank(datasourceKey)) {
            return "No dbhub datasource is configured.";
        }
        if (!ReadonlySqlGuard.isReadonly(sql)) {
            return "Only readonly SQL is allowed";
        }
        DbhubDatasourceConfig config = datasourceService.getDatasourceConfig(datasourceKey);
        if (config == null) {
            return "Unknown datasource: " + datasourceKey;
        }
        try (Connection connection = getConnection(config);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            return wrapResult(rowsToList(resultSet));
        } catch (Exception exception) {
            return "dbhub readonly query failed: " + exception.getMessage();
        }
    }

    /**
     * 清理缓存的数据源。
     */
    public void evictDatasource(String key) {
        HikariDataSource dataSource = dataSourceCache.remove(key);
        if (dataSource != null) {
            dataSource.close();
        }
    }

    /**
     * 应用关闭时释放业务库连接池。
     */
    @PreDestroy
    public void closeDataSources() {
        for (HikariDataSource dataSource : dataSourceCache.values()) {
            dataSource.close();
        }
        dataSourceCache.clear();
    }

    /**
     * 把查询结果包成标准 JSON 返给模型，避免之前 Map.toString() 那种非标准格式难解析。
     */
    private String wrapResult(Object data) {
        Map<String, Object> wrapper = new LinkedHashMap<String, Object>();
        wrapper.put("result", data);
        try {
            return objectMapper.writeValueAsString(wrapper);
        } catch (Exception exception) {
            return "{\"result\":\"" + data + "\"}";
        }
    }

    private Object describeTable(Connection connection, String database, String tableName) {
        Map<String, Object> tableInfo = new LinkedHashMap<String, Object>();
        try {
            tableInfo.put("columns", queryColumns(connection, database, tableName));
            tableInfo.put("totalRows", queryApproximateRows(connection, database, tableName));
            tableInfo.put("recentData", queryRecentData(connection, tableName));
            return tableInfo;
        } catch (Exception exception) {
            Map<String, Object> error = new LinkedHashMap<String, Object>();
            error.put("error", exception.getMessage());
            return error;
        }
    }

    private List<Map<String, Object>> queryColumns(Connection connection, String database, String tableName) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        String sql = "select COLUMN_NAME, DATA_TYPE, IS_NULLABLE, COLUMN_KEY, COLUMN_DEFAULT, COLUMN_COMMENT " +
                "from INFORMATION_SCHEMA.COLUMNS where TABLE_SCHEMA = ? and TABLE_NAME = ? order by ORDINAL_POSITION";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, database);
            statement.setString(2, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                rows.addAll(rowsToList(resultSet));
            }
        }
        return rows;
    }

    /**
     * 取 information_schema 里的近似行数，避免对大表执行 count(*) 全表扫描。
     * InnoDB 下 TABLE_ROWS 是估算值，作"数据量大致多少"的取证证据足够。
     */
    private long queryApproximateRows(Connection connection, String database, String tableName) throws Exception {
        String sql = "select TABLE_ROWS from INFORMATION_SCHEMA.TABLES where TABLE_SCHEMA = ? and TABLE_NAME = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, database);
            statement.setString(2, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong("TABLE_ROWS") : 0L;
            }
        }
    }

    private List<Map<String, Object>> queryRecentData(Connection connection, String tableName) throws Exception {
        String orderColumn = hasColumn(connection, tableName, "id") ? " order by `id` desc" : "";
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("select * from " + quoteIdentifier(tableName) + orderColumn + " limit 5")) {
            return rowsToList(resultSet);
        }
    }

    private boolean hasColumn(Connection connection, String tableName, String columnName) throws Exception {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet resultSet = metaData.getColumns(connection.getCatalog(), null, tableName, columnName)) {
            return resultSet.next();
        }
    }

    private List<Map<String, Object>> rowsToList(ResultSet resultSet) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        ResultSetMetaData metaData = resultSet.getMetaData();
        int columnCount = metaData.getColumnCount();
        while (resultSet.next()) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            for (int index = 1; index <= columnCount; index++) {
                row.put(metaData.getColumnLabel(index), resultSet.getObject(index));
            }
            rows.add(row);
        }
        return rows;
    }

    private Connection getConnection(DbhubDatasourceConfig config) throws Exception {
        return dataSourceCache.computeIfAbsent(config.getKey(), key -> createDataSource(config)).getConnection();
    }

    private HikariDataSource createDataSource(DbhubDatasourceConfig config) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setJdbcUrl("jdbc:mysql://" + config.getHost() + ":" + config.getPort() + "/" + config.getDatabase() +
                "?allowPublicKeyRetrieval=true&useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai");
        dataSource.setUsername(config.getUser());
        dataSource.setPassword(config.getPassword() == null ? "" : config.getPassword());
        AppProperties.Dbhub dbhub = appProperties.getDbhub();
        dataSource.setMaximumPoolSize(dbhub.getMaxPoolSize());
        dataSource.setMinimumIdle(dbhub.getMinIdle());
        dataSource.setConnectionTimeout(dbhub.getConnectionTimeoutMs());
        dataSource.setPoolName("dbhub-" + config.getKey());
        return dataSource;
    }

    private DbhubDatasourceConfig resolveConfig(DbhubDatasourceConfig config) {
        if (config != null && !isBlank(config.getKey())) {
            DbhubDatasourceConfig saved = datasourceService.getDatasourceConfig(config.getKey());
            if (saved != null) {
                if (isBlank(config.getHost()) || config.getPort() == null || isBlank(config.getUser()) || isBlank(config.getDatabase())) {
                    return saved;
                }
                if (isBlank(config.getPassword())) {
                    config.setPassword(saved.getPassword());
                }
            }
        }
        return config;
    }

    private String quoteIdentifier(String identifier) {
        if (isBlank(identifier) || !identifier.matches("^[A-Za-z0-9_]+$")) {
            throw new IllegalArgumentException("Invalid table name: " + identifier);
        }
        return "`" + identifier + "`";
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
