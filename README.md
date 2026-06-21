# Bug Agent Platform

[English](#bug-agent-platform) | [中文](#bug-agent-平台中文)

Multi-project backend bug analysis agent platform.

## Stack

- Backend: Java 8, Spring Boot 2.7, Spring JDBC, MySQL, Redis
- Frontend: Vue 3, Vite, Element Plus
- Code graph: JavaParser, XML parser, JSqlParser
- AI: OpenAI-compatible chat API
- dbhub: backend built-in readonly datasource access

## Local Run

1. Start local dependencies:

- MySQL: `localhost:3306`, root password `1234`
- Redis: `localhost:6379`

2. Start backend:

```bash
cd backend
mvn spring-boot:run
```

3. Start frontend:

```bash
cd frontend
npm install
npm run dev
```

Frontend: http://localhost:5173
Backend: http://localhost:8088/api

## Required Runtime

- JDK 8
- Maven 3.8+
- Node.js 18+
- Local MySQL and Redis with the configured ports
- Git client for repository import

## First Run Checklist

1. Create or select a project.
2. Import source by Git or ZIP.
3. Wait until the version index status becomes `SUCCESS`.
4. Save global AI config with `baseUrl`, `apiKey`, and `modelName`.
5. Save a project dbhub datasource if database evidence is needed.
6. Open the analysis workspace and submit API path, request body, response body, and optional stack trace.

## Backend API Groups

- `/projects`: project metadata and dbhub datasource binding.
- `/projects/{projectId}/sources`: Git and ZIP source import.
- `/ai-config`: global OpenAI-compatible provider configuration.
- `/analysis`: bug analysis orchestration.

## Notes

- The AI key is base64-encoded in v1. Replace it with KMS or database encryption before production use.
- dbhub datasource management and readonly evidence queries are built into the backend.
- CodeGraph v1 focuses on Spring routes, Java methods, MyBatis XML SQL, and table extraction.

## V1 Features

- Create projects
- Import source by Git or ZIP
- Build a code graph asynchronously
- Configure global AI base URL, API key, and model
- Bind project dbhub datasource keys
- Analyze API bugs from request, response, and optional stack trace

---

# Bug Agent 平台（中文）

面向多项目的后端 Bug 分析 Agent 平台。

## 技术栈

- 后端：Java 8、Spring Boot 2.7、Spring JDBC、MySQL、Redis
- 前端：Vue 3、Vite、Ant Design Vue
- 代码图谱：JavaParser、XML 解析、JSqlParser
- AI：OpenAI 兼容的 Chat 接口
- dbhub：后端内置只读数据源取证

## 本地运行

1. 启动本地依赖：

- MySQL：`localhost:3306`，root 密码 `1234`
- Redis：`localhost:6379`

2. 启动后端：

```bash
cd backend
mvn spring-boot:run
```

3. 启动前端：

```bash
cd frontend
npm install
npm run dev
```

前端：http://localhost:5173
后端：http://localhost:8088/api

## 运行环境要求

- JDK 8
- Maven 3.8+
- Node.js 18+
- 按配置端口起好本地 MySQL 和 Redis
- Git 客户端（用于仓库导入）

## 首次使用清单

1. 新建或选择一个项目。
2. 通过 Git 或 ZIP 导入源码。
3. 等版本索引状态变成 `SUCCESS`。
4. 配好全局 AI：填 `baseUrl`、`apiKey`、`modelName`。
5. 需要数据库取证就给项目绑定 dbhub 数据源。
6. 进分析工作台，填接口路径、请求体、响应体，按需贴异常堆栈。

## 后端接口分组

- `/projects`：项目元数据与 dbhub 数据源绑定。
- `/projects/{projectId}/sources`：Git 和 ZIP 源码导入。
- `/ai-config`：全局 OpenAI 兼容服务商配置。
- `/analysis`：Bug 分析编排（含接口分析、异步任务、反馈标注）。

## 说明

- v1 的 AI 密钥仅做 base64 编码，上生产前换成 KMS 或数据库加密。
- dbhub 数据源管理与只读取证查询内置在后端。
- 代码图谱 v1 聚焦 Spring 路由、Java 方法、MyBatis XML SQL 和表名提取。

## V1 功能

- 新建项目
- Git / ZIP 导入源码
- 异步构建代码图谱
- 配置全局 AI 的 baseUrl、apiKey、模型
- 绑定项目 dbhub 数据源
- 基于请求、响应、可选堆栈分析接口 Bug
- 接口分析：只填项目+接口，讲清接口职责与调用流程
- 历史飞轮：标注/连库验证的案例喂回评测，并作为相似参考辅助新分析
