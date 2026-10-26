# OJ

面向编程教学与训练的在线判题平台：Vue 3 前端、Spring Cloud 微服务后端、Python AI 辅导与向量服务。

## 功能

- 题库与提交：题目管理、代码编辑、运行与提交、提交记录。
- 异步判题：RabbitMQ 三档队列（high / normal / run）+ 延迟重试与死信，Redis 状态机与 MySQL 终态。
- 代码沙箱：native / Docker 双后端，可复用借还池，资源限制与超时控制。
- 学习记忆：Event → Episode → Pattern，按学生与题目沉淀薄弱点并组装辅导上下文。
- 题目检索：Elasticsearch 8 + 384 维 embedding 边车，BM25 + Dense + RRF 融合。
- AI 辅导：SSE 流式回答、三档提示、防泄答案、按用户与全局双限流。

## 目录结构

```text
oj-backend-master/   Spring Boot 微服务（user / judge / code-sandbox / gateway）
oj-frontend-master/  Vue 3 + TypeScript 前端
ai/                  FastAPI AI 辅导服务（端口 8026）
ai_service/          文本向量边车（端口 8025）
benchmark/           压测、评测与端到端脚本
scripts/             本地启动、验证与种子数据脚本
```

## 环境要求

- JDK 21、Maven 3.9+
- Node 20+、pnpm
- MySQL 8、Redis 7、Nacos 2.x、RabbitMQ 3.8+
- Elasticsearch 8.15（检索功能可选；不启动时检索接口返回 503，判题链路不受影响）
- Python 3.11（AI 服务与评测脚本）

## 快速开始

### 1. 初始化数据库

```bash
mysql -uroot -p -e "CREATE DATABASE IF NOT EXISTS oj CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci"
mysql -uroot -p oj < oj-backend-master/sql/oj.sql
```

增量脚本位于 `oj-backend-master/sql/migration/`，按文件名顺序执行。

### 2. 配置

各服务的示例配置在 `src/main/resources/application-example.yml`。复制为 `application.yml` 后按本机环境修改；`application.yml`、`.env` 与 `.runtime/` 均不会进入 Git。

敏感值通过环境变量注入：

```text
OJ_MYSQL_PASSWORD      MySQL 密码
OJ_REDIS_PASSWORD      Redis 密码（无密码可留空）
OJ_JWT_SECRET          网关 / 用户服务共享的 JWT 密钥
OJ_MAIL_USERNAME       邮件账号
OJ_MAIL_PASSWORD       邮件授权码
NACOS_USERNAME         Nacos 用户名
NACOS_PASSWORD         Nacos 密码
NACOS_SERVER_ADDR      Nacos 地址（默认 127.0.0.1:8848）
OJ_MYSQL_HOST          MySQL 地址（默认 127.0.0.1）
OJ_REDIS_HOST          Redis 地址（默认 127.0.0.1）
DEEPSEEK_API_KEY       AI 服务的模型 Key（可选；不配置时用 fake provider）
```

AI 服务优先读取 `DEEPSEEK_API_KEY`，其次读取 `.runtime/secrets/deepseek.key`（单行文本，已被 Git 忽略）。密钥不会写入日志或接口响应。

### 3. 启动后端

```bash
# Nacos（standalone）
startup.cmd -m standalone

# 构建
cd oj-backend-master
mvn -DskipTests package

# 依次启动（另行启动 Nacos / MySQL / Redis / RabbitMQ）
java -jar user/target/user-1.0-SNAPSHOT.jar
java -jar code-sandbox/target/code-sandbox-1.0-SNAPSHOT.jar
java -jar judge/target/judge-1.0-SNAPSHOT.jar
java -jar gateway/target/gateway-1.0-SNAPSHOT.jar
```

默认端口：user `8011`、judge `8012`、code-sandbox `8013`、gateway `8024`、AI `8026`、embedding `8025`。

### 4. 启动前端

```bash
cd oj-frontend-master
pnpm install --frozen-lockfile
pnpm dev          # http://localhost:8002，/api 代理到网关 8024
```

### 5. 启动 AI 服务

```bash
cd ai
pip install -r requirements.txt
P6_PROVIDER=fake uvicorn app:app --host 127.0.0.1 --port 8026
```

使用真实模型时设置 `P6_PROVIDER=deepseek` 与 `DEEPSEEK_API_KEY`。

## 验证

```bash
# 后端单测
cd oj-backend-master && mvn test

# AI 服务单测
python -m pytest ai/tests -q

# 前端类型检查与浏览器用例
cd oj-frontend-master
pnpm type-check
pnpm exec playwright install chromium
pnpm exec playwright test
```

## 安全约定

- 仓库不包含 API Key、数据库密码、邮箱授权码或本地绝对路径。
- `application.yml`、`.env*`、`.runtime/`、`secrets/`、`*.key` 已在 `.gitignore` 中排除。
- 生产部署请使用独立的密钥管理服务或 CI Secret，不要复用示例值。
