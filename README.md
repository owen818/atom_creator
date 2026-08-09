# Atoms Forge Demo

一个可交付的 AI 应用构建工作台：用户注册后，用自然语言描述产品，智能体调用 DeepSeek 生成一个可交互的单页应用，在内嵌预览中即时体验，并将项目与每次生成的版本保存到 SQLite。

## 亮点与流程

`注册/登录 → 描述需求 → DeepSeek 生成 HTML 应用 → Sandbox 预览 → 查看历史版本 → 继续迭代`

- **真实交互**：账号、项目创建、重生成、版本切换、删除与预览均可实际操作。
- **持久化**：SQLite 保存在 `data/atoms-demo.db`，重启后账号、项目和版本仍在。
- **DeepSeek 接入**：后端通过 DeepSeek OpenAI-compatible `chat/completions` API 调用 `deepseek-chat`；密钥不进入前端。没有密钥时使用带提示的本地回退生成器，保证评审可立即走通流程。
- **增量迭代与回归**：修改或 Bug 修复模式会把当前 HTML 和最近 8 条历史需求传给模型，生成不可变的新版本；后端透明展示按钮、表单和脚本的结构回归检查结果。
- **交付能力**：支持查看生成 HTML、一键复制和导出包含 `index.html`、版本信息的 ZIP 文件。
- **可观察 Agent 流程**：生成先进入可编辑计划，用户批准后异步执行；界面持续显示上下文加载、代码生成、回归检查和版本保存阶段，并支持取消。任务状态持久化在 `agent_runs` 表中。

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

## 验收测试

完整的手工验收与回归测试步骤见 [TEST_CASES.md](TEST_CASES.md)。

## Agent 使用流程

1. 选择“创建应用”“增量修改”或“修复 Bug”，填写需求并点击“生成计划”。
2. 审阅或编辑计划；此时尚未调用代码生成模型。
3. 点击“批准并执行”，页面轮询展示 `LOADING_CONTEXT → GENERATING → REGRESSION → COMPLETED`。
4. 可在执行中请求取消；模型 HTTP 请求无法被强制中断，但完成后会检查取消状态，不会保存新版本。
5. 完成后查看回归检查、预览、代码或导出的 ZIP。

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

1. 从仓库构建 `Dockerfile`，服务端口设为 `8080`（平台使用 `PORT` 时本应用自动读取）。Dockerfile 已将 Vue 的 `frontend/dist` 复制到最终运行镜像。
2. 添加 Secret：`DEEPSEEK_API_KEY`，可选 `DEEPSEEK_MODEL=deepseek-chat`。
3. 挂载持久卷到 `/app/data`，否则容器重启会丢失 SQLite 数据。
4. 将生成的 HTTPS 地址作为在线体验链接提交。

生产建议把认证改为 BCrypt/Argon2、会话/JWT 和安全 Cookie，并把 SQLite 换为托管 PostgreSQL；这是当前 take-home 原型刻意控制的复杂度边界。
