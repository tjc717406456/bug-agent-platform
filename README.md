# Bug Agent Platform

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
- Redis: `localhost:6380`

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
