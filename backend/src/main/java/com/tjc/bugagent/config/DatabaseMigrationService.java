package com.tjc.bugagent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.security.SecureRandom;

/**
 * 补齐旧库缺失的小版本字段和配置表。
 */
@Component
public class DatabaseMigrationService {
    private static final Logger log = LoggerFactory.getLogger(DatabaseMigrationService.class);
    // 随机初始密码字符集：去掉 0/O、1/l/I 这类易混字符，管理员照着日志抄不会抄错
    private static final String PASSWORD_ALPHABET = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties appProperties;

    public DatabaseMigrationService(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder, AppProperties appProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.appProperties = appProperties;
    }

    /**
     * 确保旧库结构能跟上当前版本。
     */
    @PostConstruct
    public void migrate() {
        ensureDbhubDatasourceConfigTable();
        ensureAnalysisScreenshotColumn();
        ensureAnalysisFeedbackColumns();
        ensureAnalysisEmbeddingColumn();
        ensureAnalysisMetricsColumns();
        ensureAnalysisRecordTypeColumn();
        ensureAiVisionColumn();
        // 鉴权相关：先补 owner_id 列与唯一键，再 seed 管理员并回填存量项目归属，顺序不可颠倒
        ensureAuthTables();
        ensureProjectOwnerColumn();
        ensureAuthBootstrap();
        ensureProjectMemberTable();
        ensureAnalysisCreatedByColumn();
        ensureTableComments();
    }

    /** 旧库补建项目可见范围授权表（新库由 schema.sql 建）。 */
    private void ensureProjectMemberTable() {
        jdbcTemplate.execute("create table if not exists project_member (" +
                "id bigint primary key auto_increment," +
                "project_id bigint not null," +
                "user_id bigint not null," +
                "created_at datetime not null," +
                "unique key uk_member(project_id, user_id)," +
                "key idx_member_user(user_id)" +
                ")");
    }

    /** 分析记录补发起人列：项目内历史共享后要能看出这条是谁跑的。 */
    private void ensureAnalysisCreatedByColumn() {
        if (!tableExists("analysis_record")) {
            return;
        }
        addColumnIfMissing("analysis_record", "created_by", "bigint comment '发起人用户ID' after evidence_json");
    }

    /** 旧库补建 users / audit_log（新库由 schema.sql 建）。 */
    private void ensureAuthTables() {
        jdbcTemplate.execute("create table if not exists users (" +
                "id bigint primary key auto_increment," +
                "username varchar(64) not null unique," +
                "password_hash varchar(100) not null," +
                "role varchar(16) not null default 'USER'," +
                "status varchar(16) not null default 'ACTIVE'," +
                "display_name varchar(128)," +
                "must_change_password tinyint not null default 0," +
                "last_login_at datetime," +
                "created_at datetime not null," +
                "updated_at datetime not null" +
                ")");
        jdbcTemplate.execute("create table if not exists audit_log (" +
                "id bigint primary key auto_increment," +
                "actor_user_id bigint," +
                "actor_username varchar(64)," +
                "action varchar(64) not null," +
                "target_type varchar(32)," +
                "target_id varchar(64)," +
                "detail varchar(1024)," +
                "ip varchar(64)," +
                "success tinyint not null default 1," +
                "created_at datetime not null," +
                "key idx_audit_actor(actor_user_id)," +
                "key idx_audit_created(created_at)" +
                ")");
    }

    /**
     * 给项目补所属用户列，并把「code 全局唯一」改成「同一所有者下唯一」——
     * 否则多用户环境里 A 占了某个 code，B 永远无法使用同名编码。
     * 列保持可空：存量行先补列再回填，不让 DDL 卡在 not null 上。
     */
    private void ensureProjectOwnerColumn() {
        if (!tableExists("project")) {
            return;
        }
        addColumnIfMissing("project", "owner_id", "bigint comment '所属用户ID' after code");
        if (!indexExists("project", "idx_project_owner")) {
            jdbcTemplate.execute("alter table project add index idx_project_owner(owner_id)");
        }
        if (!indexExists("project", "uk_project_owner_code")) {
            // 旧库里 `code varchar(64) not null unique` 会生成名为 code 的唯一索引，先摘掉再建复合唯一键
            if (indexExists("project", "code")) {
                jdbcTemplate.execute("alter table project drop index code");
            }
            jdbcTemplate.execute("alter table project add unique key uk_project_owner_code(owner_id, code)");
        }
    }

    /**
     * 首个管理员与存量项目归属：users 为空时创建管理员，再把没有归属的项目挂到它名下。
     * 初始密码优先取配置（可由环境变量注入），否则随机生成并只在创建那一刻打印一次；库里只存 bcrypt。
     */
    private void ensureAuthBootstrap() {
        Long adminId = seedAdminIfAbsent();
        if (adminId == null) {
            adminId = jdbcTemplate.query("select id from users where role = 'ADMIN' order by id limit 1",
                    rs -> rs.next() ? rs.getLong(1) : null);
        }
        if (adminId != null && tableExists("project")) {
            int backfilled = jdbcTemplate.update("update project set owner_id = ? where owner_id is null", adminId);
            if (backfilled > 0) {
                log.info("存量项目归属回填完成：{} 个项目已挂到管理员(userId={})", backfilled, adminId);
            }
        }
    }

    /** users 表为空才创建管理员，返回新建的 id；已有用户返回 null。 */
    private Long seedAdminIfAbsent() {
        Integer userCount = jdbcTemplate.queryForObject("select count(1) from users", Integer.class);
        if (userCount != null && userCount > 0) {
            return null;
        }
        AppProperties.Auth auth = appProperties.getAuth();
        String username = auth.getAdminUsername();
        String configured = auth.getAdminInitialPassword();
        boolean generated = configured == null || configured.trim().isEmpty();
        String password = generated ? randomPassword(16) : configured.trim();
        jdbcTemplate.update("insert into users(username, password_hash, role, status, display_name, must_change_password, created_at, updated_at) " +
                        "values (?, ?, 'ADMIN', 'ACTIVE', ?, 0, now(), now())",
                username, passwordEncoder.encode(password), "管理员");
        Long adminId = jdbcTemplate.queryForObject("select id from users where username = ?", Long.class, username);
        if (generated) {
            // 明文只在这一刻出现在日志里，库里永远只有 bcrypt 哈希
            log.warn("===============================================================");
            log.warn(" 已创建初始管理员账号：{} / {}", username, password);
            log.warn(" 该密码仅打印这一次，请妥善保存；可登录后在「修改密码」中更换。");
            log.warn("===============================================================");
        } else {
            log.info("已按配置创建初始管理员账号：{}（密码来自 app.auth.admin-initial-password）", username);
        }
        return adminId;
    }

    private String randomPassword(int length) {
        SecureRandom random = new SecureRandom();
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(PASSWORD_ALPHABET.charAt(random.nextInt(PASSWORD_ALPHABET.length())));
        }
        return builder.toString();
    }

    private boolean indexExists(String tableName, String indexName) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(1) from information_schema.statistics where table_schema = database() and table_name = ? and index_name = ?",
                Integer.class, tableName, indexName);
        return count != null && count > 0;
    }

    /** 给分析记录补类型列：ANALYSIS bug分析 / EXPLAIN 接口讲解——讲解落库后同样支持追问与历史回看。 */
    private void ensureAnalysisRecordTypeColumn() {
        if (!tableExists("analysis_record")) {
            return;
        }
        addColumnIfMissing("analysis_record", "record_type",
                "varchar(16) not null default 'ANALYSIS' comment '记录类型:ANALYSIS bug分析/EXPLAIN 接口讲解' after api_path");
    }

    /** 给分析记录补轮数/token 列，落每次分析的成本指标，供趋势/p95 统计和调参。 */
    private void ensureAnalysisMetricsColumns() {
        if (!tableExists("analysis_record")) {
            return;
        }
        addColumnIfMissing("analysis_record", "rounds_count", "int not null default 0 comment '查证轮数' after embedding");
        addColumnIfMissing("analysis_record", "total_tokens", "int not null default 0 comment '消耗token总数' after rounds_count");
    }

    /** 给分析记录补 embedding 列，缓存案例文本向量，供相似案例的语义召回复用，免得每次重算。 */
    private void ensureAnalysisEmbeddingColumn() {
        if (!tableExists("analysis_record")) {
            return;
        }
        addColumnIfMissing("analysis_record", "embedding", "mediumtext comment '案例文本向量JSON,语义召回用' after auto_verify_keywords");
    }

    /** 给 AI 配置补"是否支持视觉"列，旧库切到多模态版本不至于查列报错。 */
    private void ensureAiVisionColumn() {
        if (!tableExists("ai_provider_config")) {
            return;
        }
        addColumnIfMissing("ai_provider_config", "supports_vision", "tinyint not null default 0 comment '模型是否支持视觉多模态' after enabled");
        addColumnIfMissing("ai_provider_config", "role", "varchar(16) not null default 'PRIMARY' comment '模型角色:PRIMARY主分析/UTILITY辅助' after supports_vision");
    }

    /**
     * 给分析记录补反馈字段：用户标注结论对错 + 正确根因 + 期望关键词，沉淀成回归用例的 ground truth。
     */
    private void ensureAnalysisFeedbackColumns() {
        if (!tableExists("analysis_record")) {
            return;
        }
        addColumnIfMissing("analysis_record", "feedback_verdict", "varchar(16) comment '人工标注结论(CORRECT/WRONG/PARTIAL)' after evidence_json");
        addColumnIfMissing("analysis_record", "actual_root_cause", "mediumtext comment '人工确认的真实根因' after feedback_verdict");
        addColumnIfMissing("analysis_record", "expect_keywords", "varchar(1024) comment '正确结论必须命中的关键词JSON' after actual_root_cause");
        addColumnIfMissing("analysis_record", "feedback_note", "varchar(512) comment '反馈备注' after expect_keywords");
        addColumnIfMissing("analysis_record", "feedback_at", "datetime comment '反馈时间' after feedback_note");
        // 机器自动验证结果：确定性结论(字段/表不存在)连库核对，无需人工
        addColumnIfMissing("analysis_record", "auto_verify", "varchar(16) comment '自动验证(CONFIRMED/REFUTED/UNVERIFIABLE)' after feedback_at");
        addColumnIfMissing("analysis_record", "auto_verify_keywords", "varchar(512) comment '自动验证得出的期望关键词JSON' after auto_verify");
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(1) from information_schema.tables where table_schema = database() and table_name = ?",
                Integer.class, tableName);
        return count != null && count > 0;
    }

    private void addColumnIfMissing(String tableName, String columnName, String columnDefinition) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(1) from information_schema.columns where table_schema = database() and table_name = ? and column_name = ?",
                Integer.class, tableName, columnName);
        if (count == null || count == 0) {
            jdbcTemplate.execute("alter table " + tableName + " add column " + columnName + " " + columnDefinition);
        }
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
