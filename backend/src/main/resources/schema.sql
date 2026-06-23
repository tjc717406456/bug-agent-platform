create table if not exists project (
  id bigint primary key auto_increment comment '项目ID',
  name varchar(128) not null comment '项目名称',
  code varchar(64) not null unique comment '项目编码',
  description varchar(512) comment '项目说明',
  created_at datetime not null comment '创建时间',
  updated_at datetime not null comment '更新时间'
);

create table if not exists project_version (
  id bigint primary key auto_increment comment '版本ID',
  project_id bigint not null comment '项目ID',
  source_type varchar(16) not null comment '源码来源类型',
  branch_name varchar(128) comment 'Git分支名',
  commit_id varchar(128) comment 'Git提交ID',
  source_path varchar(512) not null comment '源码存储路径',
  index_status varchar(32) not null comment '代码索引状态',
  index_message text comment '代码索引信息',
  created_at datetime not null comment '创建时间',
  indexed_at datetime comment '索引完成时间',
  index_started_at datetime comment '索引开始时间',
  key idx_project_version_project(project_id)
);

create table if not exists project_datasource (
  id bigint primary key auto_increment comment '绑定ID',
  project_id bigint not null comment '项目ID',
  env varchar(32) not null comment '环境标识',
  dbhub_key varchar(128) not null comment 'dbhub数据源Key',
  whitelist_tables text comment '允许查询的表名',
  enabled tinyint not null default 1 comment '是否启用',
  created_at datetime not null comment '创建时间',
  updated_at datetime not null comment '更新时间',
  key idx_datasource_project(project_id)
);

create table if not exists dbhub_datasource_config (
  id bigint primary key auto_increment comment '数据源ID',
  datasource_key varchar(128) not null unique comment '数据源Key',
  host varchar(255) not null comment '数据库主机',
  port int not null comment '数据库端口',
  username varchar(128) not null comment '数据库用户名',
  password varchar(512) comment '数据库密码',
  database_name varchar(128) not null comment '数据库名称',
  created_at datetime not null comment '创建时间',
  updated_at datetime not null comment '更新时间'
);

create table if not exists ai_provider_config (
  id bigint primary key auto_increment comment 'AI配置ID',
  provider varchar(64) not null comment 'AI服务商',
  base_url varchar(512) not null comment 'AI接口基础地址',
  model_name varchar(128) not null comment '模型名称',
  api_key_cipher text not null comment 'API Key密文',
  timeout_seconds int not null comment '超时时间秒数',
  enabled tinyint not null comment '是否启用',
  supports_vision tinyint not null default 0 comment '模型是否支持视觉多模态',
  role varchar(16) not null default 'PRIMARY' comment '模型角色:PRIMARY主分析/UTILITY辅助',
  created_at datetime not null comment '创建时间',
  updated_at datetime not null comment '更新时间'
);

create table if not exists code_node (
  id bigint primary key auto_increment comment '代码节点ID',
  project_id bigint not null comment '项目ID',
  version_id bigint not null comment '版本ID',
  node_type varchar(32) not null comment '节点类型',
  name varchar(256) not null comment '节点名称',
  qualified_name varchar(768) comment '节点全限定名',
  file_path varchar(768) comment '源码文件路径',
  line_no int comment '源码行号',
  metadata_json text comment '节点元数据JSON',
  key idx_node_project_version(project_id, version_id),
  key idx_node_type_name(node_type, name)
);

create table if not exists code_edge (
  id bigint primary key auto_increment comment '代码关系ID',
  project_id bigint not null comment '项目ID',
  version_id bigint not null comment '版本ID',
  from_node_id bigint not null comment '起始节点ID',
  to_node_id bigint not null comment '目标节点ID',
  edge_type varchar(64) not null comment '关系类型',
  metadata_json text comment '关系元数据JSON',
  key idx_edge_project_version(project_id, version_id),
  key idx_edge_from(from_node_id),
  key idx_edge_to(to_node_id)
);

create table if not exists analysis_record (
  id bigint primary key auto_increment comment '分析记录ID',
  project_id bigint not null comment '项目ID',
  version_id bigint not null comment '版本ID',
  api_path varchar(512) not null comment '接口路径',
  user_description mediumtext comment '用户问题描述',
  request_body mediumtext comment '请求参数或请求体',
  response_body mediumtext comment '响应结果',
  stack_trace mediumtext comment '异常堆栈',
  screenshot_paths mediumtext comment '截图文件路径',
  trace_id varchar(128) comment '链路追踪ID',
  request_time varchar(64) comment '请求发生时间',
  conclusion mediumtext comment '分析结论',
  confidence varchar(32) comment '置信度',
  evidence_json mediumtext comment '分析证据JSON',
  created_at datetime not null comment '创建时间',
  key idx_analysis_project(project_id),
  key idx_analysis_api(api_path)
);
