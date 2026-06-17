package com.tjc.bugagent.config;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * 补齐旧库缺失的小版本字段和配置表。
 */
@Component
public class DatabaseMigrationService {
    private final JdbcTemplate jdbcTemplate;

    public DatabaseMigrationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 确保旧库结构能跟上当前版本。
     */
    @PostConstruct
    public void migrate() {
        ensureDbhubDatasourceConfigTable();
        ensureDefaultDbhubDatasources();
        ensureAnalysisScreenshotColumn();
        ensureTableComments();
    }

    /**
     * 创建内置 dbhub 数据源配置表。
     */
    private void ensureDbhubDatasourceConfigTable() {
        jdbcTemplate.execute("create table if not exists dbhub_datasource_config (" +
                "id bigint primary key auto_increment," +
                "datasource_key varchar(128) not null unique," +
                "host varchar(255) not null," +
                "port int not null," +
                "username varchar(128) not null," +
                "password varchar(512)," +
                "database_name varchar(128) not null," +
                "created_at datetime not null," +
                "updated_at datetime not null" +
                ")");
    }

    /**
     * 写入本地开发默认数据源。
     */
    private void ensureDefaultDbhubDatasources() {
        insertDefaultDatasource("bug_agent", "bug_agent");
        insertDefaultDatasource("user_bug_demo", "user_bug_demo");
    }

    private void insertDefaultDatasource(String key, String database) {
        jdbcTemplate.update(
                "insert into dbhub_datasource_config(datasource_key, host, port, username, password, database_name, created_at, updated_at) " +
                        "select ?, 'localhost', 3306, 'root', '1234', ?, now(), now() " +
                        "where not exists (select 1 from dbhub_datasource_config where datasource_key = ?)",
                key, database, key);
    }

    /**
     * 确保分析记录能保存截图路径。
     */
    private void ensureAnalysisScreenshotColumn() {
        Integer tableCount = jdbcTemplate.queryForObject(
                "select count(1) from information_schema.tables where table_schema = database() and table_name = 'analysis_record'",
                Integer.class);
        if (tableCount == null || tableCount == 0) {
            return;
        }
        Integer count = jdbcTemplate.queryForObject(
                "select count(1) from information_schema.columns where table_schema = database() and table_name = 'analysis_record' and column_name = 'screenshot_paths'",
                Integer.class);
        if (count == null || count == 0) {
            jdbcTemplate.execute("alter table analysis_record add column screenshot_paths mediumtext after stack_trace");
        }
    }

    /**
     * 给现有表字段补注释。
     */
    private void ensureTableComments() {
        alterColumnComment("project", "id", "bigint not null auto_increment comment '项目ID'");
        alterColumnComment("project", "name", "varchar(128) not null comment '项目名称'");
        alterColumnComment("project", "code", "varchar(64) not null comment '项目编码'");
        alterColumnComment("project", "description", "varchar(512) comment '项目说明'");
        alterColumnComment("project", "created_at", "datetime not null comment '创建时间'");
        alterColumnComment("project", "updated_at", "datetime not null comment '更新时间'");

        alterColumnComment("project_version", "id", "bigint not null auto_increment comment '版本ID'");
        alterColumnComment("project_version", "project_id", "bigint not null comment '项目ID'");
        alterColumnComment("project_version", "source_type", "varchar(16) not null comment '源码来源类型'");
        alterColumnComment("project_version", "branch_name", "varchar(128) comment 'Git分支名'");
        alterColumnComment("project_version", "commit_id", "varchar(128) comment 'Git提交ID'");
        alterColumnComment("project_version", "source_path", "varchar(512) not null comment '源码存储路径'");
        alterColumnComment("project_version", "index_status", "varchar(32) not null comment '代码索引状态'");
        alterColumnComment("project_version", "index_message", "text comment '代码索引信息'");
        alterColumnComment("project_version", "created_at", "datetime not null comment '创建时间'");
        alterColumnComment("project_version", "indexed_at", "datetime comment '索引完成时间'");
        alterColumnComment("project_version", "index_started_at", "datetime comment '索引开始时间'");

        alterColumnComment("project_datasource", "id", "bigint not null auto_increment comment '绑定ID'");
        alterColumnComment("project_datasource", "project_id", "bigint not null comment '项目ID'");
        alterColumnComment("project_datasource", "env", "varchar(32) not null comment '环境标识'");
        alterColumnComment("project_datasource", "dbhub_key", "varchar(128) not null comment 'dbhub数据源Key'");
        alterColumnComment("project_datasource", "whitelist_tables", "text comment '允许查询的表名'");
        alterColumnComment("project_datasource", "enabled", "tinyint not null default 1 comment '是否启用'");
        alterColumnComment("project_datasource", "created_at", "datetime not null comment '创建时间'");
        alterColumnComment("project_datasource", "updated_at", "datetime not null comment '更新时间'");

        alterColumnComment("dbhub_datasource_config", "id", "bigint not null auto_increment comment '数据源ID'");
        alterColumnComment("dbhub_datasource_config", "datasource_key", "varchar(128) not null comment '数据源Key'");
        alterColumnComment("dbhub_datasource_config", "host", "varchar(255) not null comment '数据库主机'");
        alterColumnComment("dbhub_datasource_config", "port", "int not null comment '数据库端口'");
        alterColumnComment("dbhub_datasource_config", "username", "varchar(128) not null comment '数据库用户名'");
        alterColumnComment("dbhub_datasource_config", "password", "varchar(512) comment '数据库密码'");
        alterColumnComment("dbhub_datasource_config", "database_name", "varchar(128) not null comment '数据库名称'");
        alterColumnComment("dbhub_datasource_config", "created_at", "datetime not null comment '创建时间'");
        alterColumnComment("dbhub_datasource_config", "updated_at", "datetime not null comment '更新时间'");

        alterColumnComment("ai_provider_config", "id", "bigint not null auto_increment comment 'AI配置ID'");
        alterColumnComment("ai_provider_config", "provider", "varchar(64) not null comment 'AI服务商'");
        alterColumnComment("ai_provider_config", "base_url", "varchar(512) not null comment 'AI接口基础地址'");
        alterColumnComment("ai_provider_config", "model_name", "varchar(128) not null comment '模型名称'");
        alterColumnComment("ai_provider_config", "api_key_cipher", "text not null comment 'API Key密文'");
        alterColumnComment("ai_provider_config", "timeout_seconds", "int not null comment '超时时间秒数'");
        alterColumnComment("ai_provider_config", "enabled", "tinyint not null comment '是否启用'");
        alterColumnComment("ai_provider_config", "created_at", "datetime not null comment '创建时间'");
        alterColumnComment("ai_provider_config", "updated_at", "datetime not null comment '更新时间'");

        alterColumnComment("code_node", "id", "bigint not null auto_increment comment '代码节点ID'");
        alterColumnComment("code_node", "project_id", "bigint not null comment '项目ID'");
        alterColumnComment("code_node", "version_id", "bigint not null comment '版本ID'");
        alterColumnComment("code_node", "node_type", "varchar(32) not null comment '节点类型'");
        alterColumnComment("code_node", "name", "varchar(256) not null comment '节点名称'");
        alterColumnComment("code_node", "qualified_name", "varchar(768) comment '节点全限定名'");
        alterColumnComment("code_node", "file_path", "varchar(768) comment '源码文件路径'");
        alterColumnComment("code_node", "line_no", "int comment '源码行号'");
        alterColumnComment("code_node", "metadata_json", "text comment '节点元数据JSON'");

        alterColumnComment("code_edge", "id", "bigint not null auto_increment comment '代码关系ID'");
        alterColumnComment("code_edge", "project_id", "bigint not null comment '项目ID'");
        alterColumnComment("code_edge", "version_id", "bigint not null comment '版本ID'");
        alterColumnComment("code_edge", "from_node_id", "bigint not null comment '起始节点ID'");
        alterColumnComment("code_edge", "to_node_id", "bigint not null comment '目标节点ID'");
        alterColumnComment("code_edge", "edge_type", "varchar(64) not null comment '关系类型'");
        alterColumnComment("code_edge", "metadata_json", "text comment '关系元数据JSON'");

        alterColumnComment("analysis_record", "id", "bigint not null auto_increment comment '分析记录ID'");
        alterColumnComment("analysis_record", "project_id", "bigint not null comment '项目ID'");
        alterColumnComment("analysis_record", "version_id", "bigint not null comment '版本ID'");
        alterColumnComment("analysis_record", "api_path", "varchar(512) not null comment '接口路径'");
        alterColumnComment("analysis_record", "user_description", "mediumtext comment '用户问题描述'");
        alterColumnComment("analysis_record", "request_body", "mediumtext comment '请求参数或请求体'");
        alterColumnComment("analysis_record", "response_body", "mediumtext comment '响应结果'");
        alterColumnComment("analysis_record", "stack_trace", "mediumtext comment '异常堆栈'");
        alterColumnComment("analysis_record", "screenshot_paths", "mediumtext comment '截图文件路径'");
        alterColumnComment("analysis_record", "trace_id", "varchar(128) comment '链路追踪ID'");
        alterColumnComment("analysis_record", "request_time", "varchar(64) comment '请求发生时间'");
        alterColumnComment("analysis_record", "conclusion", "mediumtext comment '分析结论'");
        alterColumnComment("analysis_record", "confidence", "varchar(32) comment '置信度'");
        alterColumnComment("analysis_record", "evidence_json", "mediumtext comment '分析证据JSON'");
        alterColumnComment("analysis_record", "created_at", "datetime not null comment '创建时间'");
    }

    private void alterColumnComment(String tableName, String columnName, String columnDefinition) {
        Integer exists = jdbcTemplate.queryForObject(
                "select count(1) from information_schema.columns where table_schema = database() and table_name = ? and column_name = ?",
                Integer.class, tableName, columnName);
        if (exists != null && exists > 0) {
            jdbcTemplate.execute("alter table " + tableName + " modify column " + columnName + " " + columnDefinition);
        }
    }
}
