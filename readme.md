# RAG 知识库系统

一个检索增强生成（RAG）知识库系统，**同一套前端 + 两个功能等价的后端**：一个用
Python / LangChain 写，一个用 Java / Spring AI 写。两者共享同一份基础设施与同一份
数据，因此可以在界面上随时切换后端、对同一个知识库提同一个问题，直接比较两种技术栈
的实现差异与效果

```
                    ┌──────────────────────────┐
                    │  Next.js 前端  :3000     │
                    │  设置页可切换后端        │
                    └───────┬──────────┬───────┘
                            │          │
              ┌─────────────┘          └─────────────┐
              ▼                                      ▼
   ┌──────────────────────┐              ┌──────────────────────┐
   │ FastAPI + LangChain  │              │ Spring Boot 3.3      │
   │ langchain-rag  :8000 │              │ spring-ai-rag  :8080 │
   └──────┬───────┬───────┘              └──────┬───────┬───────┘
          │       └──────────────┬──────────────┘       │
          ▼                      ▼                      ▼
   ┌─────────────┐      ┌────────────────┐      ┌──────────────┐
   │ Milvus 2.4  │      │ SQLite rag.db  │      │  Neo4j 5.18  │
   │ 向量        │      │ 元数据（共享） │      │  知识图谱    │
   └─────────────┘      └────────────────┘      └──────────────┘
```

## 两个后端如何共享数据

这是本项目的核心设计约束：**换后端不换数据**。

| 存储 | 共享方式 |
| --- | --- |
| 元数据（用户、知识库、文档、会话、反馈） | 同一个 SQLite 文件，由 `DB_PATH` 指定，表结构在两端完全一致 |
| 向量 | 同一个 Milvus，集合名统一为 `kb_<32位hex>`，字段名统一为 `id` / `vector` / `text` / `metadata`（`metadata` 是存 JSON 字符串的 `VARCHAR(65535)`） |
| 知识图谱 | 同一个 Neo4j，节点标签与关系类型一致 |
| 上传的原始文件 | 同一个 `UPLOAD_DIR` |
| 登录令牌 | 同一个 `JWT_SECRET` 签发的 JWT，两端互认 |

字段命名、路由取值、SSE 事件格式等跨端约定写在 [docs/CONTRACT.md](docs/CONTRACT.md)，
改任何一端之前先读它。

## 检索流程

两个后端实现的是同一条流水线：

1. **查询分析** — 一次 LLM 调用同时完成路由判断和查询改写，产出 `rag` / `graph` /
   `hybrid` / `direct` 之一以及若干改写后的子查询。（早期版本是路由、改写、扩展三次
   串行调用，成本和首字延迟都是现在的三倍。）
2. **多路检索** — 每个子查询各自向量检索，结果按 id 去重合并。
3. **重排** — `RERANKER_PROVIDER` 决定用交叉编码器（仅 Python）、LLM 打分，还是直接
   沿用向量顺序；低于 `RERANK_THRESHOLD` 的片段丢弃。
4. **图谱上下文** — 路由为 `graph` 或 `hybrid` 时，从 Neo4j 取相关实体及其邻居拼进
   上下文。
5. **生成与置信度** — 流式输出答案，并给出引用片段和一个置信度分值。
6. **SSE 推送** — 前端边收边渲染，结束时收到 `sources` 与 `confidence`。

## 快速开始

### 1. 起基础设施

```bash
cd docker
cp .env.example .env        # 至少填 JWT_SECRET 和一个 LLM key
docker compose up -d        # etcd + MinIO + Milvus + Neo4j + Attu
docker compose ps           # 等到 milvus / neo4j 都是 healthy
```

`JWT_SECRET` 用 `openssl rand -hex 32` 生成。留空的话两个后端会各自生成一个随机密钥，
在一端登录得到的令牌另一端不认，切后端就会被踢回登录页。

### 2. 起应用

**全容器**（最省事，改代码要重新 build）：

```bash
cd docker
docker compose --profile apps up -d --build
```

**本地跑后端**（开发常用，基础设施仍在容器里）：

```bash
# Python 后端
cd langchain-rag
python -m venv venv && source venv/bin/activate
pip install -r requirements.txt
cp .env.example .env        # 与 docker/.env 保持同样的 JWT_SECRET 和 DB_PATH
uvicorn main:app --reload --port 8000

# Java 后端（需要 JDK 17，8 和 11 都编不过）
cd spring-ai-rag
mvn spring-boot:run

# 前端
cd frontend
npm install
cp .env.example .env.local
npm run dev
```

打开 <http://localhost:3000>。首次登录用 `ADMIN_USERNAME`（默认 `admin`）；
`ADMIN_PASSWORD` 没设的话，后端启动日志里会打印一次随机密码。

部署细节、反向代理、生产环境注意事项见 [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)。

## 功能

- **知识库管理** — 建库、上传文档（PDF / Word / Markdown / 文本 / 表格 / 源码）、
  按目录导入整个项目（自动遵守 `.gitignore`）、分块预览与全文检索。
- **对话** — 多会话、历史留存、流式回答、引用溯源、置信度、点赞点踩加评论。
- **知识图谱** — 从文档抽实体和关系写入 Neo4j，可视化浏览、按实体检索邻居。
- **权限** — JWT 登录，知识库属主隔离，管理员可见全部。
- **设置** — 在界面上切换后端、LLM 供应商、嵌入模型、重排策略和分块参数。

## 目录结构

```
├── frontend/                 Next.js 14 (App Router) + Tailwind
│   └── src/
│       ├── app/              chat / knowledge / graph / settings / login
│       ├── components/       UI 组件
│       ├── lib/api.ts        两个后端共用的 API 客户端，含 SSE 解析
│       └── types/index.ts    跨端契约的 TypeScript 定义
│
├── langchain-rag/            FastAPI 后端
│   └── app/
│       ├── api/              路由层
│       ├── core/             配置、SQLite、鉴权、权限
│       └── services/         切分、嵌入、检索、重排、图谱、对话
│
├── spring-ai-rag/            Spring Boot 后端
│   └── src/main/java/com/rag/
│       ├── controller/       与 Python 侧一一对应的接口
│       ├── config/           Milvus、LLM、JWT、CORS
│       ├── service/          与 app/services/ 一一对应
│       └── store/            共享 SQLite 的访问层
│
├── docker/                   compose 编排与 .env.example
└── docs/                     CONTRACT.md（跨端契约）、DEPLOYMENT.md（部署）
```

## 技术栈

| | Python 后端 | Java 后端 |
| --- | --- | --- |
| 框架 | FastAPI 0.111 | Spring Boot 3.3.1 |
| LLM 编排 | LangChain 0.2 | Spring AI 1.0.0-M1 |
| 向量库客户端 | pymilvus 2.4 | milvus-sdk-java 2.4.11 |
| 图数据库 | neo4j-driver 5.22 | Spring Data Neo4j |
| 文档解析 | pypdf / python-docx / pandas | Apache Tika 2.9 |
| 元数据 | 标准库 `sqlite3`（WAL） | `sqlite-jdbc` 3.46 |
| 运行要求 | Python 3.11+ | JDK 17 |

前端：Next.js 14.2 / React 18 / TypeScript 5 / Tailwind / react-force-graph-2d。

## 两端的已知差异

功能等价不等于完全相同，以下差异是有意保留的：

- **文档格式**：Java 端通过 Tika 还能解析 `.pptx` / `.odt` / `.rtf` / `.epub`，
  Python 端不能。前端的可选格式取两者交集，保证同一个文件在两端得到同样的结果。
- **本地重排与本地嵌入**：`RERANKER_PROVIDER=local`、`EMBEDDING_PROVIDER=local`
  依赖 sentence-transformers，只有 Python 端支持；Java 端会告警并回退。
- **火山引擎**：只有 Python 端有对应客户端。
- **`OPENAI_API_BASE`**：LangChain 要求带 `/v1` 后缀，Spring AI 会自己拼，所以
  `docker/.env` 里分成 `OPENAI_API_BASE` 和 `SPRING_OPENAI_API_BASE` 两个变量。
  Java 端会把结尾的 `/v1` 去掉再用，所以两个变量填成一样的也能跑通，只是日志里
  会记一行说明。

## 开发

```bash
cd frontend && npm run typecheck && npm run lint
cd langchain-rag && pytest
cd spring-ai-rag && mvn test
```

两端的单元测试都不需要 Milvus、Neo4j 或任何 LLM：SQLite 用临时目录，LLM 用假实现，
所以不起基础设施也能跑。Python 端只需要 `pytest pydantic-settings bcrypt
python-jose fastapi`，重依赖缺失时 `tests/conftest.py` 会自动打桩。

两端有几组测试是**成对**的，一边加用例另一边也要加，否则同一个输入会在两端得到不同结果：

| Python | Java | 盯的是什么 |
| --- | --- | --- |
| `tests/test_gitignore.py` | `util/GitignoreTest.java` | 同一次上传两端排除的文件必须一致 |
| `tests/test_db.py` | `store/DbTest.java` | 共享库的表结构、pragma、时间戳格式 |
| `tests/test_store.py` | `store/MetadataStoreTest.java` | 会话隔离、chunk 中缀搜索、反馈去重 |
| `tests/test_auth.py` | `config/JwtUtilTest.java`<br>`service/UserServiceTest.java` | token 与 bcrypt 哈希能互相验证 |
| `tests/test_config.py` | `config/RagPropertiesTest.java` | 两端默认值相同 |
| `tests/test_query_analyzer.py` | `service/QueryAnalyzerServiceTest.java` | 同一份模型输出必须路由到同一条链路 |
