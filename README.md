# Atoms Forge Demo

一个可交付的 AI 应用构建工作台：用户注册后，用自然语言描述产品，智能体调用 DeepSeek 生成一个可交互的单页应用，在内嵌预览中即时体验，并将项目与每次生成的版本保存到 SQLite。

## 亮点与流程

`注册/登录 → 描述需求 → DeepSeek 生成 HTML 应用 → Sandbox 预览 → 查看历史版本 → 继续迭代`

- **真实交互**：账号、项目创建、重生成、版本切换、删除与预览均可实际操作。
- **持久化**：SQLite 保存在 `data/atoms-demo.db`，重启后账号、项目和版本仍在。
- **DeepSeek 接入**：后端通过 DeepSeek OpenAI-compatible `chat/completions` API 调用 `deepseek-chat`；密钥不进入前端。没有密钥时使用带提示的本地回退生成器，保证评审可立即走通流程。
- **延展能力**：同一项目的每次提示生成一个版本，可回看任一版本。

## 技术与架构

| 层 | 选择 | 职责 |
|---|---|---|
| 前端 | Vue 3 + Vite | 三栏 Agent 工作台、认证和预览交互 |
| 后端 | Java 25 + Spring Boot 4 | REST API、业务编排、静态文件托管 |
| AI | DeepSeek API | 基于产品描述生成完整 HTML |
| 数据 | SQLite | 用户、项目、生成版本的本地持久化 |

```text
Vue 浏览器 ──REST──> Spring Boot ──> SQLite
                         │
                         └──> DeepSeek chat/completions API
Vue iframe <── sandboxed HTML artifact ── Spring Boot
```

项目结构：

```text
src/main/java/.../api/ApiController.java  # API 和项目编排
src/main/java/.../service/DeepSeekService.java # DeepSeek 适配与可靠回退
src/main/resources/schema.sql             # 可追踪的数据库结构
frontend/src/App.vue                       # 核心 Vue 交互界面
frontend/src/style.css                     # 响应式视觉设计
Dockerfile / docker-compose.yml            # 容器化部署
```

代码的关键行为均有简短注释；`GeminiService` 被单独抽象，后续接入流式输出、工具调用或其他模型无需改动 API 层。

## 本地启动

前置条件：Java 25、Maven 3.9+、Node.js 22+（本项目使用 Vite 最新稳定版）。

```bash
cp .env.example .env
# 编辑 .env 并填入 DEEPSEEK_API_KEY；也可跳过，体验本地回退模式
mkdir -p data
set -a && source .env && set +a
mvn spring-boot:run
```

另一终端运行前端热更新：

```bash
cd frontend
npm install
npm run dev
```

开发访问 `http://localhost:5173`。生产构建前端后由 Java 同域托管：

```bash
cd frontend && npm install && npm run build && cd ..
mvn package
DEEPSEEK_API_KEY=你的密钥 java -jar target/atoms-demo-1.0.0.jar
```

访问 `http://localhost:8080`。API 密钥请通过环境变量或部署平台的 Secret 注入，绝不要提交 `.env`。

## Docker 与云部署

本地容器：`DEEPSEEK_API_KEY=你的密钥 docker compose up --build`，访问 `http://localhost:8080`。

部署到 Render、Railway、Fly.io 或任意容器平台时：

1. 从仓库构建 `Dockerfile`，服务端口设为 `8080`（平台使用 `PORT` 时本应用自动读取）。
2. 添加 Secret：`DEEPSEEK_API_KEY`，可选 `DEEPSEEK_MODEL=deepseek-chat`。
3. 挂载持久卷到 `/app/data`，否则容器重启会丢失 SQLite 数据。
4. 将生成的 HTTPS 地址作为在线体验链接提交。

生产建议把认证改为 BCrypt/Argon2、会话/JWT 和安全 Cookie，并把 SQLite 换为托管 PostgreSQL；这是当前 take-home 原型刻意控制的复杂度边界。
