# 共享契约（两个后端必须完全一致）

本文件是 `langchain-rag`（Python）与 `spring-ai-rag`（Java）之间的强约束。
两个后端指向同一个 SQLite 库、同一批 Milvus collection、同一个 Neo4j 图，
因此前端在设置页切换后端时看到的数据必须完全相同。

**任何一侧改动本文件涉及的结构，另一侧必须同步改。**

---

## 1. 元数据库（SQLite）

路径由环境变量 `DB_PATH` 决定，两端默认值都是 `../data/rag.db`
（Python 从 `langchain-rag/` 启动、Java 从 `spring-ai-rag/` 启动，
因此都解析到 `<项目根>/data/rag.db`）。

- 连接必须开启 `PRAGMA journal_mode=WAL`（允许两个进程并发读写）
- 连接必须开启 `PRAGMA foreign_keys=ON`
- 所有时间戳存 UTC ISO-8601 字符串，格式 `YYYY-MM-DDTHH:MM:SSZ`
- `CREATE TABLE IF NOT EXISTS` 对已存在的表不会补列：给某张表新增列时，两端初始化
  逻辑里都要在建表脚本之后加一段幂等的 `ALTER TABLE ... ADD COLUMN`（查
  `PRAGMA table_info` 判断列是否已存在），否则线上库不会跟着新建库走

```sql
CREATE TABLE IF NOT EXISTS users (
    username        TEXT PRIMARY KEY,
    hashed_password TEXT NOT NULL,
    role            TEXT NOT NULL DEFAULT 'user',   -- admin | user
    created_at      TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS knowledge_bases (
    id            TEXT PRIMARY KEY,                 -- uuid4().hex，32 位小写十六进制
    name          TEXT NOT NULL,
    description   TEXT NOT NULL DEFAULT '',
    kb_type       TEXT NOT NULL DEFAULT 'document', -- document | book | project
    owner         TEXT NOT NULL,
    is_global     INTEGER NOT NULL DEFAULT 0,
    embedding_dim INTEGER NOT NULL DEFAULT 1536,    -- 建库时的向量维度，之后不可变
    created_at    TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS documents (
    id          TEXT PRIMARY KEY,
    kb_id       TEXT NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    filename    TEXT NOT NULL,
    file_type   TEXT NOT NULL,
    file_size   INTEGER NOT NULL DEFAULT 0,
    chunk_count INTEGER NOT NULL DEFAULT 0,
    status      TEXT NOT NULL DEFAULT 'processing', -- processing | completed | failed
    stage       TEXT,                               -- parsing | embedding | graph
    error       TEXT,
    created_at  TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_documents_kb ON documents(kb_id);

-- chunks 是「可浏览副本」：Milvus 只负责向量近邻，分页/全文检索/总数都走这里。
-- Milvus 2.4 的 like 只支持前缀匹配，无法做 %keyword% 中缀搜索，所以必须有这张表。
CREATE TABLE IF NOT EXISTS chunks (
    id          TEXT PRIMARY KEY,                   -- 与 Milvus 主键 id 一致
    kb_id       TEXT NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    document_id TEXT NOT NULL,
    chunk_index INTEGER NOT NULL,
    source      TEXT NOT NULL DEFAULT '',
    text        TEXT NOT NULL,
    metadata    TEXT NOT NULL DEFAULT '{}',         -- JSON 字符串
    created_at  TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_chunks_kb  ON chunks(kb_id);
CREATE INDEX IF NOT EXISTS idx_chunks_doc ON chunks(document_id);

CREATE TABLE IF NOT EXISTS sessions (
    id         TEXT PRIMARY KEY,
    title      TEXT NOT NULL,
    kb_id      TEXT NOT NULL,
    owner      TEXT NOT NULL,                       -- 会话归属，鉴权靠它
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sessions_owner ON sessions(owner);

CREATE TABLE IF NOT EXISTS messages (
    id                   TEXT PRIMARY KEY,
    session_id           TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    message_index        INTEGER NOT NULL,          -- 会话内序号，从 0 开始
    role                 TEXT NOT NULL,              -- user | assistant
    content              TEXT NOT NULL,
    sources              TEXT,                       -- JSON 数组
    confidence           REAL,
    route                TEXT,
    suggested_questions  TEXT,                       -- JSON 字符串数组，仅 assistant 消息可能有
    llm_calls            TEXT,                       -- JSON 数组，每轮 LLM 调用一条记录，仅 assistant 消息可能有
    created_at           TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_messages_session ON messages(session_id);

CREATE TABLE IF NOT EXISTS feedback (
    id            TEXT PRIMARY KEY,
    session_id    TEXT NOT NULL,
    message_index INTEGER NOT NULL,
    owner         TEXT NOT NULL,
    rating        TEXT NOT NULL,                    -- up | down
    comment       TEXT,
    created_at    TEXT NOT NULL,
    UNIQUE(session_id, message_index)
);
```

## 2. Milvus

- collection 名：`kb_` + `knowledge_bases.id`（32 位 hex，无连字符）→ `kb_<32hex>`
- 字段（顺序与名字都不可变）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | VARCHAR(64), 主键, autoID=false | 与 `chunks.id` 一致 |
| `document_id` | VARCHAR(64) | |
| `text` | VARCHAR(65535) | 分块正文，超长截断到 65000 |
| `metadata` | VARCHAR(65535) | **JSON 字符串**，不是 Milvus JSON 类型 |
| `embedding` | FLOAT_VECTOR(dim) | dim = `knowledge_bases.embedding_dim` |

- 索引：`IVF_FLAT` / `COSINE` / `nlist=128`
- `metadata` JSON 固定键：`source`、`file_type`、`chunk_index`、`document_id`
- 写入后必须 flush，否则刚上传的文档搜不到

## 3. Neo4j

```
(:Entity {name, kb_id, type})-[:RELATION {type: <谓词>, kb_id}]->(:Entity {...})
```

- 属性名是 **snake_case 的 `kb_id`**（不是 `kbId`）
- 关系类型固定为 `RELATION`，谓词放在关系属性 `type` 上（不使用动态关系类型，
  否则两端生成的图无法互相查询）
- 删除知识库时两端都必须 `MATCH (n:Entity {kb_id: $kb_id}) DETACH DELETE n`

## 4. SSE 事件（`POST /api/chat`）

事件名与 data 格式两端必须一致：

| event | data | 时机 |
|---|---|---|
| `session` | 会话 id（纯文本） | 最先发送 |
| `metadata` | `{"route","confidence","reason","intent","complexity"}` | 查询分析后 |
| `stage` | `{"stage","status","elapsedMs"?}` | 每个阶段开始/结束时，可发生多次 |
| `confidence` | 纯数字，如 `0.72` | 检索+重排后，正文之前 |
| `chunk` | 正文片段（纯文本） | 流式多次 |
| `sources` | JSON 数组 | 正文结束后 |
| `suggestedQuestions` | JSON 字符串数组，如 `["问题1","问题2"]` | 正文（及 sources）结束后，done 之前；生成失败或为空时跳过该事件 |
| `llmCall` | `{"step","model","promptTokens","completionTokens","totalTokens","durationMs","contentPreview"}` | 每次独立 LLM 调用结束后发出一次，可发生多次，与其它事件交错 |
| `done` | 空字符串 | 最后 |
| `error` | 错误信息（纯文本） | 出错时，替代 done |

`sources` 每一项：`{text, score, source, documentId, rerankScore}`（`rerankScore` 可为
`null`，表示未重排）。与其它响应体一样是 camelCase。

`stage` 的 `stage` 取值：`analyzing`（路由+改写）、`retrieving`（多查询检索+重排，
仅非 `direct` 路由）、`graph`（图谱上下文，仅 `graph`/`hybrid` 且有抽取词时）、
`generating`（LLM 生成）。`status` 为 `start` 或 `done`；`done` 时带整数毫秒
`elapsedMs`，`start` 时省略该字段。纯粹用于前端展示进度，不代表模型真实推理过程。

`chunk` 的正文可能含换行。按 SSE 规范，两端都把换行拆成多个 `data:` 行
（Python 的 sse-starlette、Java 的 `SseEmitter` 都是这个行为），前端**必须**把同一事件的
多个 `data:` 行用 `\n` 拼回去，否则 Markdown 排版会丢失。

`suggestedQuestions` 由"问题 + 完整回答"追加一次独立的小 LLM 调用生成（与路由分析、
正文生成都是不同的调用），2-4 条，纯粹是产品层的"猜你想问"推荐，不代表模型真实
推理过程。随消息持久化到 `messages.suggested_questions`，`GET /api/chat/history/{sessionId}`
返回的每条消息里也带 `suggestedQuestions` 字段（无则为空数组或省略）。

`llmCall` 把一问一答背后调用大模型的每一步都暴露给前端做"思考过程"展示。`step`
取值：`analyzing`（路由分析+查询改写）、`generating`（主回答生成）、`suggesting`
（猜你想问）；Java 端在 rag/graph/hybrid 路由上还会额外发出 `grounding`（忠实性
校验）和 `regenerate`（校验失败或疑似拒答后的重新生成），Python 端目前没有这两
步，这是两端允许的既有分歧（见第8节关于 GroundingService 的说明）。

`promptTokens`/`completionTokens`/`totalTokens` 在拿不到时为 `null`（不是 `0`），
典型场景：Java 端 `generating` 步若命中 `direct` 路由（纯流式，当前 Spring AI
1.0.0-M1 版本的 OpenAI 协议请求体不支持 `stream_options`，拿不到流式 usage），
三个 token 字段固定为 `null`，但 `durationMs` 仍然准确；LLM 调用本身失败时同样
token 为 `null`，`contentPreview` 写明失败原因。`contentPreview` 只给非正文类
步骤（analyzing 的路由结论、suggesting 的问题列表、grounding 的判定理由）；
`generating` 步骤的正文已经在 `chunk`/`content` 里，这里固定为 `null`，避免重复。

一问一答期间产生的全部 `llmCall` 记录随 assistant 消息一起持久化到
`messages.llm_calls`（JSON 数组），`GET /api/chat/history/{sessionId}` 返回的每条
消息里也带 `llmCalls` 字段（无则为空数组或省略），供历史会话回放时复现完整的
多轮思考过程。

## 5. REST 契约

请求体与响应体**一律 camelCase**；查询参数也一律 camelCase。

| 方法 | 路径 | 请求 | 响应 |
|---|---|---|---|
| POST | `/api/auth/login` | `{username,password}` | `{token,username,role}` |
| POST | `/api/auth/register` | `{username,password}` | `{token,username,role}` |
| GET | `/api/knowledge-bases` | | `KnowledgeBase[]` |
| POST | `/api/knowledge-bases` | `{name,description,kbType,isGlobal}` | `KnowledgeBase` |
| GET | `/api/knowledge-bases/{id}` | | `KnowledgeBase` |
| DELETE | `/api/knowledge-bases/{id}` | | `{message}` |
| GET | `/api/knowledge-bases/{id}/chunks` | `?page=&pageSize=&search=` | `{chunks,total,page,pageSize}` |
| POST | `/api/knowledge-bases/{id}/documents` | multipart `file` | `Document`（status=processing） |
| POST | `/api/knowledge-bases/{id}/import-project` | multipart `file`,`fileExtensions?` | `Document`（status=processing） |
| GET | `/api/knowledge-bases/{id}/documents` | | `Document[]` |
| GET | `/api/documents/{id}` | | `Document`（供前端轮询状态） |
| DELETE | `/api/documents/{id}` | | `{message}` |
| POST | `/api/chat` | `{question,knowledgeBaseId,sessionId?}` | SSE |

`knowledgeBaseId` 除了真实知识库 id，还可以是字面值 `"__all__"`
（两端常量：Python `ALL_KB_SENTINEL`、Java `ChatService.ALL_KB_SENTINEL`），
含义是"调用者自己名下的全部私有知识库"（不含全局库；全局库仍按第4节的现有规则
自动合并，不受这个模式影响）。该模式下两端行为一致地做了两点简化：
- 跳过改写查询的多路召回（只用消解后的问题做一次 embedding，对每个目标库各搜一次），
  避免"目标库数 × 改写数"的检索量爆炸
- 跳过图谱上下文增强（Neo4j 节点按单个 `kb_id` 打标，没有单一的图可查）

路由分析器收到的知识库名称是目标库名称用"、"拼接的字符串，类型固定传
`document`。会话一旦用这个 sentinel 创建，`sessions.kb_id` 就存这个字面值
（该表没有外键约束，可以安全存放非真实 id），续接会话时按字符串相等比较，
无需额外处理。
| GET | `/api/chat/history/{sessionId}` | | `{sessionId,messages}` |
| GET | `/api/sessions` | `?knowledgeBaseId=` | `{sessions}` |
| POST | `/api/sessions` | `{knowledgeBaseId,title?}` | `Session` |
| GET | `/api/sessions/{id}` | | `Session` |
| DELETE | `/api/sessions/{id}` | | `{message}` |
| GET | `/api/graph/{kbId}` | | `{nodes,edges}` |
| POST | `/api/graph/query` | `{knowledgeBaseId,entity}` | `{nodes,edges}` |
| GET | `/api/settings` | | `Settings`（见下） |
| PUT | `/api/settings` | `Settings` 的部分字段 | `Settings` |
| POST | `/api/feedback` | `{sessionId,messageIndex,rating,comment?}` | `{success}` |
| GET | `/api/feedback/stats` | | `{total,positive,negative,satisfactionRate}` |
| GET | `/health` | | `{status}` |

`Settings` 读取形状（密钥永不回传）：

```
llmProvider, openaiApiBase, openaiModel, ollamaBaseUrl, ollamaModel,
volcengineApiBase, volcengineModel, embeddingProvider, openaiEmbeddingModel,
localEmbeddingModel, rerankerProvider, milvusHost, milvusPort, neo4jUri,
chunkSize, chunkOverlap
```

PUT 额外接受只写字段：`openaiApiKey`、`volcengineApiKey`、`neo4jUser`、`neo4jPassword`。

## 6. 鉴权规则

- 除 `/api/auth/**`、`/`、`/health` 外全部要求 Bearer JWT
- JWT 密钥来自 `JWT_SECRET`；未设置时进程启动生成随机密钥并打警告（不得硬编码）
- `JWT_SECRET` **至少 32 个字符**：HS256 要求 256 位密钥，Java 侧 jjwt 会直接拒绝更短的密钥。
  两端都对原始 UTF-8 字节做签名，不做补齐/哈希派生（否则 token 无法互通）
- 签名算法 HS256，payload 固定为 `{sub: 用户名, role, exp}`
- 知识库：全局库所有人可读，仅 admin 可写/删；私有库仅 owner 或 admin 可读写
- **会话/历史/反馈：仅 `sessions.owner` 本人或 admin 可读、可删、可追加**
- 设置：仅 admin 可 PUT

## 7. 置信度计算（两端公式必须一致）

检索结果的 `score` 统一归一化到 `[0,1]`：

```
clamp01(x) = max(0, min(1, x))
重排后:  score = 0.7 * (rerankScore / 10) + 0.3 * clamp01(vectorScore)
未重排:  score = clamp01(vectorScore)
```

置信度：

```
无结果            -> 0.2
countFactor       = min(结果数 / 5, 1)
scoreFactor       = min(前 3 条 score 均值 / 0.8, 1)
confidence        = 0.3*countFactor + 0.5*scoreFactor + 0.2*routeConfidence
```

结果保留两位小数并裁剪到 `[0,1]`。

## 8. 查询分析（单次 LLM 调用）

路由与查询改写**合并为一次调用**（原来是两次串行调用，白等一个 round-trip）。
返回 JSON：

```json
{
  "route": "rag | graph | hybrid | direct",
  "confidence": 0.0,
  "reason": "",
  "intent": "factual | analytical | comparative | procedural",
  "resolvedQuery": "",
  "rewrittenQueries": [],
  "keywords": [],
  "graphEntities": [],
  "complexity": "simple | moderate | complex"
}
```

解析失败时两端都退化为：`route=rag`、`confidence=0.5`、`resolvedQuery=原问题`。

**路由器必须知道全局知识库的存在。** 提示词里除当前知识库外，还要列出检索阶段
真正会搜的那几个全局知识库（名称 + 描述），并明确 `direct` 的含义是"与当前库和
这些全局库**都**无关"。漏掉这一段会导致：拿私有库问一个只存在于全局库里的主题，
路由判 `direct` 直接跳过检索、用模型自己的知识作答，而全局库压根没被搜——尽管
检索层的全局库合并是正确的。传给路由器的列表必须和检索实际搜的那份**是同一份**
（已按上限截断），否则路由器批准了一个不会被搜的库，判 `rag` 也召回不到东西。

> Spring 端已修（`ChatService.globalKbs()` + `MetadataStore.listGlobalKbs()`）。
> Python 端 `store.list_global_kb_ids()` 仍只 `SELECT id`，同一个 bug 尚未修。

## 9. 重排

`RERANKER_PROVIDER`：

| 值 | Python | Java |
|---|---|---|
| `local` | sentence-transformers CrossEncoder（`RERANKER_MODEL`，默认 `BAAI/bge-reranker-v2-m3`） | 不支持，退化为 `llm` 并打警告 |
| `llm` | LLM 打分 0-10 | LLM 打分 0-10 |
| `none` | 跳过，按向量分排序 | 跳过，按向量分排序 |

阈值 `RERANK_THRESHOLD`（默认 3.0，0-10 区间），保留 `RERANK_TOP_K`（默认 5）条。
