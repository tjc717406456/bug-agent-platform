package com.tjc.bugagent.dbhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tjc.bugagent.config.AppProperties;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import javax.sql.DataSource;
import java.sql.Connection;
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
    private static final Logger log = LoggerFactory.getLogger(DbhubQueryService.class);
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
            log.warn("dbhub testDatasource failed, key={}", actualConfig == null ? null : actualConfig.getKey(), exception);
            throw new IllegalStateException(sanitize(exception), exception);
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
            log.warn("dbhub describeTables failed, datasource={}, tables={}", datasourceKey, tables, exception);
            return "dbhub call failed: " + sanitize(exception);
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
        AppProperties.Dbhub dbhub = appProperties.getDbhub();
        int maxRows = dbhub.getMaxRows();
        try (Connection connection = getConnection(config);
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(dbhub.getQueryTimeoutSeconds());
            // 多取一行用来判断是否被截断，让模型知道结果不完整、该加 WHERE/LIMIT
            statement.setMaxRows(maxRows + 1);
            try (ResultSet resultSet = statement.executeQuery(sql)) {
                List<Map<String, Object>> rows = rowsToList(resultSet);
                boolean truncated = rows.size() > maxRows;
                if (truncated) {
                    rows = new ArrayList<Map<String, Object>>(rows.subList(0, maxRows));
                }
                Map<String, Object> wrapper = new LinkedHashMap<String, Object>();
                wrapper.put("result", rows);
                if (truncated) {
                    wrapper.put("truncated", "结果超过 " + maxRows + " 行，仅返回前 " + maxRows + " 行，请加 WHERE/LIMIT 缩小范围");
                }
                return serialize(wrapper);
            }
        } catch (Exception exception) {
            log.warn("dbhub readonly query failed, datasource={}, sql={}", datasourceKey, sql, exception);
            return "dbhub readonly query failed: " + sanitize(exception);
        }
    }

    /**
     * 字段是否存在，用于自动验证"字段不存在"这类确定性结论。出错返回 null（无法判断）。
     */
    public Boolean columnExists(String datasourceKey, String table, String column) {
        DbhubDatasourceConfig config = datasourceService.getDatasourceConfig(datasourceKey);
        if (config == null) {
            return null;
        }
        try (Connection connection = getConnection(config);
             PreparedStatement statement = connection.prepareStatement(
                     "select count(*) from information_schema.columns where table_schema = ? and table_name = ? and column_name = ?")) {
            statement.setString(1, config.getDatabase());
            statement.setString(2, cleanIdentifier(table));
            statement.setString(3, column);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) > 0;
            }
        } catch (Exception exception) {
            return null;
        }
    }

    /**
     * 表是否存在，用于自动验证"表不存在"。出错返回 null。
     */
    public Boolean tableExists(String datasourceKey, String table) {
        DbhubDatasourceConfig config = datasourceService.getDatasourceConfig(datasourceKey);
        if (config == null) {
            return null;
        }
        try (Connection connection = getConnection(config);
             PreparedStatement statement = connection.prepareStatement(
                     "select count(*) from information_schema.tables where table_schema = ? and table_name = ?")) {
            statement.setString(1, config.getDatabase());
            statement.setString(2, cleanIdentifier(table));
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) > 0;
            }
        } catch (Exception exception) {
            return null;
        }
    }

    /** 去掉反引号、库前缀，取出纯表名。 */
    private String cleanIdentifier(String identifier) {
        if (identifier == null) {
            return "";
        }
        String clean = identifier.replace("`", "").trim();
        int dot = clean.lastIndexOf('.');
        return dot >= 0 ? clean.substring(dot + 1) : clean;
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
        return serialize(wrapper);
    }

    private String serialize(Map<String, Object> wrapper) {
        try {
            return objectMapper.writeValueAsString(wrapper);
        } catch (Exception exception) {
            return "{\"result\":\"" + wrapper.get("result") + "\"}";
        }
    }

    /**
     * 脱敏数据库异常：只取首行、抹掉 jdbc 连接串、限长。
     * 完整堆栈仅记服务端日志，避免把库地址/驱动版本/内部结构漏给上层和模型。
     */
    private String sanitize(Exception exception) {
        String message = exception.getMessage();
        if (isBlank(message)) {
            return exception.getClass().getSimpleName();
        }
        int newline = message.indexOf('\n');
        if (newline >= 0) {
            message = message.substring(0, newline);
        }
        message = message.replaceAll("jdbc:[^\\s]+", "[jdbc-url]");
        return message.length() > 200 ? message.substring(0, 200) + "..." : message;
    }

    private Object describeTable(Connection connection, String database, String tableName) {
        Map<String, Object> tableInfo = new LinkedHashMap<String, Object>();
        try {
            List<Map<String, Object>> columns = queryColumns(connection, database, tableName);
            tableInfo.put("columns", columns);
            tableInfo.put("totalRows", queryApproximateRows(connection, database, tableName));
            tableInfo.put("recentRows", queryRecentRows(connection, tableName, columns));
            return tableInfo;
        } catch (Exception exception) {
            log.warn("dbhub describeTable failed, table={}", tableName, exception);
            Map<String, Object> error = new LinkedHashMap<String, Object>();
            error.put("error", sanitize(exception));
            return error;
        }
    }

    /**
     * 取最近几条样例数据。表名来自模型入参且要拼进 SQL，先收敛成纯标识符防注入；
     * 有主键按主键倒序（走索引，大表也快），没主键就不排序随缘取几条。
     * 样例失败不连坐整个 describe，降级返回错误说明。
     */
    private Object queryRecentRows(Connection connection, String tableName, List<Map<String, Object>> columns) {
        String table = cleanIdentifier(tableName);
        if (!table.matches("[A-Za-z0-9_$]+")) {
            return "invalid table name";
        }
        String primaryKey = null;
        for (Map<String, Object> column : columns) {
            if ("PRI".equals(column.get("COLUMN_KEY"))) {
                primaryKey = String.valueOf(column.get("COLUMN_NAME"));
                break;
            }
        }
        AppProperties.Dbhub dbhub = appProperties.getDbhub();
        String sql = "select * from `" + table + "`"
                + (primaryKey == null ? "" : " order by `" + primaryKey.replace("`", "``") + "` desc")
                + " limit " + dbhub.getSampleRows();
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(dbhub.getQueryTimeoutSeconds());
            try (ResultSet resultSet = statement.executeQuery(sql)) {
                List<Map<String, Object>> rows = rowsToList(resultSet);
                for (Map<String, Object> row : rows) {
                    for (Map.Entry<String, Object> entry : row.entrySet()) {
                        entry.setValue(compactCell(entry.getValue()));
                    }
                }
                return rows;
            }
        } catch (Exception exception) {
            log.warn("dbhub queryRecentRows failed, table={}", tableName, exception);
            return "sample query failed: " + sanitize(exception);
        }
    }

    /** 样例只为看数据长相：长文本截断、二进制不外发，防大字段撑爆模型上下文。 */
    private Object compactCell(Object value) {
        if (value instanceof String && ((String) value).length() > 200) {
            return ((String) value).substring(0, 200) + "...";
        }
        if (value instanceof byte[]) {
            return "[binary " + ((byte[]) value).length + " bytes]";
        }
        return value;
    }

    private List<Map<String, Object>> queryColumns(Connection connection, String database, String tableName) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        // 定位 bug 够用即可：字段名、类型、键、注释；去掉 IS_NULLABLE/COLUMN_DEFAULT 减少喂给模型的体积
        String sql = "select COLUMN_NAME, DATA_TYPE, COLUMN_KEY, COLUMN_COMMENT " +
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

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
