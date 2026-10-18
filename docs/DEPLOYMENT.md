# RAG 知识库系统 - 部署清单

## 系统架构概览

```
┌─────────────────┐     ┌──────────────────────────┐
│   Next.js 前端   │────▶│  Spring AI 后端 (8080)    │
│   (port 3000)   │     │  或 LangChain 后端 (8000)  │
└─────────────────┘     └──────────┬───────────────┘
                                   │
              ┌──────────┬─────────┼─────────┬──────────┐
              ▼          ▼         ▼         ▼          ▼
        ┌──────────┐┌──────────┐┌──────────┐┌──────────────┐
        │  Milvus  ││  Neo4j   ││   LLM    ││ SQLite rag.db│
        │ (19530)  ││ (7687)   ││  (API)   ││  （两端共享）│
        └──────────┘└──────────┘└──────────┘└──────────────┘
```

> 两个后端不是二选一部署，而是可以同时跑：它们共享同一个 SQLite 元数据库、同一个
> Milvus 集合命名规则和同一个 Neo4j，前端在设置页切换后端后看到的是同一批数据。
> 前提是两端的 `DB_PATH` 指向同一个文件、`JWT_SECRET` 取同一个值。

## 环境要求

| 组件 | 最低版本 |
|------|----------|
| Docker & Docker Compose | 24.0+ / 2.20+ |
| Node.js | 18.17+ |
| Python | 3.11+ |
| Java JDK | 17+ |
| Maven | 3.9+ |
| 内存 | 16GB (推荐) / 8GB (最低) |
| 磁盘 | 至少 20GB 可用空间 |

---

## 一、Mac 本地部署 (开发环境)

### 1.1 安装前置依赖

```bash
# Homebrew (如果没有)
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"

# Docker Desktop
brew install --cask docker

# Node.js (推荐用 nvm)
brew install nvm
nvm install 18
nvm use 18

# Python (推荐用 pyenv)
brew install pyenv
pyenv install 3.11.9
pyenv global 3.11.9

# Java 17
brew install openjdk@17
# 添加到 PATH (zsh):
echo 'export PATH="/opt/homebrew/opt/openjdk@17/bin:$PATH"' >> ~/.zshrc
source ~/.zshrc

# Maven
brew install maven
```

### 1.2 启动基础设施 (Milvus + Neo4j)

```bash
cd docker

# 复制环境变量配置
cp .env.example .env
# 编辑 .env：至少填 JWT_SECRET 和一个 LLM 的 API Key
vim .env

# 启动基础设施（etcd + MinIO + Milvus + Neo4j + Attu）
docker compose up -d

# 验证服务状态，等到 milvus 和 neo4j 都显示 healthy
docker compose ps
```

`JWT_SECRET` 用 `openssl rand -hex 32` 生成。**两个后端必须用同一个值**，否则在一端
登录拿到的令牌在另一端会被判为无效，前端切后端就会被踢回登录页。留空的话每个后端进程
启动时各自生成一个随机密钥，并在日志里告警。

应用服务（两个后端 + 前端）在同一个 compose 文件里，但放在 `apps` profile 下，所以
`docker compose up -d` 只起基础设施——本地开发通常就要这个效果。全容器启动见 2.4。

**验证:**
- Milvus: http://localhost:9091/healthz → 返回 OK
- Neo4j Browser: http://localhost:7474 → 用 `NEO4J_USER` / `NEO4J_PASSWORD`（默认 neo4j/neo4j123456）登录
- Milvus 管理界面 (Attu): http://localhost:3300
- MinIO Console: http://localhost:9001 → minioadmin/minioadmin

### 1.3 启动 LangChain 后端 (Python)

```bash
cd langchain-rag

# 创建虚拟环境
python -m venv venv
source venv/bin/activate

# 安装依赖
pip install -r requirements.txt

# 复制环境变量
cp .env.example .env
# 编辑配置：LLM 的 API Key、JWT_SECRET、DB_PATH
vim .env

# 启动服务（等价于 uvicorn main:app --reload --port 8000）
python main.py
```

服务运行在 http://localhost:8000

**验证:** `curl http://localhost:8000/health` → `{"status": "healthy"}`

启动日志里会打印元数据库的实际路径（`Metadata store: ...`）。`DB_PATH` 默认是
`../data/rag.db`，即项目根目录下的 `data/rag.db`——Spring 后端的默认值相同，所以两端
不改配置就是共享的。若 `ADMIN_PASSWORD` 未设置，日志里还会打印一次随机生成的管理员
密码，这是唯一一次输出，没记下来就只能删库或改库重来。

### 1.4 启动 Spring AI 后端 (Java)

需要 JDK 17；JDK 8 / 11 会在编译期直接失败（`pom.xml` 里 `java.version=17`）。

```bash
cd spring-ai-rag

# 设置环境变量 (或写进 docker/.env 之外的自己的 shell profile)
export OPENAI_API_KEY=sk-your-key
export LLM_PROVIDER=openai
# 必须与 Python 后端一致，否则两端令牌互不承认
export JWT_SECRET=<与 langchain-rag/.env 相同的值>

# 开发模式直接跑
mvn spring-boot:run

# 或者打包后跑（artifactId 是 spring-ai-rag，版本 1.0.0-SNAPSHOT）
mvn clean package -DskipTests
java -jar target/spring-ai-rag-1.0.0-SNAPSHOT.jar
```

服务运行在 http://localhost:8080

**验证:** `curl http://localhost:8080/health` → `{"status": "healthy"}`

不要用 `curl /api/knowledge-bases` 验证：这个接口需要登录，未带令牌会返回 401。

### 1.5 启动前端

```bash
cd frontend

# 安装依赖
npm install

# 配置后端地址（NEXT_PUBLIC_* 在构建时内联进 bundle）
cp .env.example .env.local

# 开发模式启动
npm run dev
```

前端运行在 http://localhost:3000。用 `ADMIN_USERNAME`（默认 `admin`）和后端日志里的
管理员密码登录，之后可在设置页切换后端。

### 1.6 使用 Ollama 本地模型 (可选，无需 API Key)

```bash
# 安装 Ollama
brew install ollama

# 启动 Ollama 服务
ollama serve

# 拉取模型 (选一个)
ollama pull qwen2:7b          # 通义千问 7B, 中文效果好
ollama pull llama3:8b          # Llama3 8B
ollama pull nomic-embed-text   # 本地 Embedding 模型

# 修改 .env 配置
LLM_PROVIDER=ollama
OLLAMA_BASE_URL=http://localhost:11434
OLLAMA_MODEL=qwen2:7b
```

---

## 二、Linux 服务器部署 (生产环境)

### 2.1 服务器要求

- **操作系统**: Ubuntu 22.04 LTS / CentOS 8+ / Debian 12
- **配置推荐**: 4C8G 起步，16G+ 内存更佳
- **端口开放**: 3000 (前端), 8000/8080 (后端), 19530 (Milvus), 7474/7687 (Neo4j)

### 2.2 安装 Docker

```bash
# Ubuntu
curl -fsSL https://get.docker.com -o get-docker.sh
sudo sh get-docker.sh
sudo usermod -aG docker $USER

# 安装 Docker Compose
sudo apt-get install docker-compose-plugin

# 验证
docker --version
docker compose version
```

### 2.3 部署基础设施

```bash
# 克隆或上传项目到服务器
cd /opt/rag

# 编辑环境变量
cp docker/.env.example docker/.env
vim docker/.env

# 启动
cd docker
docker compose up -d

# 查看日志
docker compose logs -f milvus
docker compose logs -f neo4j
```

### 2.4 一键启动全部服务

`langchain-rag/`、`spring-ai-rag/`、`frontend/` 下各有一个 Dockerfile，三个应用服务
也已经写在 `docker/docker-compose.yml` 里（放在 `apps` profile 下），不需要自己写
Dockerfile 或另建 compose 文件：

```bash
cd /opt/rag/docker

# 构建并启动全部：基础设施 + 两个后端 + 前端
docker compose --profile apps up -d --build

# 只看应用日志
docker compose logs -f langchain-rag spring-ai-rag frontend

# 只重建其中一个
docker compose --profile apps up -d --build spring-ai-rag
```

关于这套编排需要知道的几件事：

- **两个后端挂同一对卷**：`rag_data` 挂在 `/app/data`（SQLite 元数据库），
  `rag_uploads` 挂在 `/app/uploads`（原始文件）。删掉 `rag_data` 就等于删掉所有账号、
  知识库和会话记录。
- **`depends_on` 用的是 `condition: service_healthy`**，所以 Milvus 会等 etcd 和 MinIO
  真的健康之后才启动，后端会等 Milvus 和 Neo4j 健康之后才启动。冷启动到全部 healthy
  通常要 1–2 分钟。
- **前端的 `NEXT_PUBLIC_*` 是构建期变量**，通过 build args 传入并内联进 JS bundle。
  改了这三个值必须 `docker compose build frontend`，重启容器没有任何效果。
- **`NEXT_PUBLIC_*` 填的是浏览器能访问到的地址**，不是容器名。写成
  `http://spring-ai-rag:8080` 只在容器之间解析得到，浏览器会全部请求失败。
- **Ollama 用 `host.docker.internal`**：容器里的 `localhost` 是容器自己。

如果只想跑应用而基础设施在别处（托管 Milvus、独立 Neo4j），也可以单独 build 再 run，
注意把上面这几点手工补上：

```bash
docker build -t rag/langchain-rag ./langchain-rag
docker run -d --name rag-langchain --network rag-network \
  -e MILVUS_HOST=rag-milvus -e NEO4J_URI=bolt://rag-neo4j:7687 \
  -e JWT_SECRET=$JWT_SECRET -e OPENAI_API_KEY=sk-xxx \
  -v rag_data:/app/data -v rag_uploads:/app/uploads \
  -p 8000:8000 rag/langchain-rag
```

### 2.5 使用 Nginx 反向代理 (生产推荐)

前端调用后端用的是**绝对地址**（`NEXT_PUBLIC_SPRING_API_URL` /
`NEXT_PUBLIC_LANGCHAIN_API_URL`），不是相对路径。所以反代要生效，必须让这两个变量在
**构建时**就指向反代后的地址，否则浏览器仍然直连 8000/8080，反代等于没配。

又因为要保留“切换后端”这个能力，两个后端得各占一个路径前缀。下面这份配置把
`/langchain/` 和 `/spring/` 分别代到两个后端：

```bash
# 构建前端时（docker/.env 或 build args）
NEXT_PUBLIC_LANGCHAIN_API_URL=https://your-domain.com/langchain
NEXT_PUBLIC_SPRING_API_URL=https://your-domain.com/spring
```

```nginx
# /etc/nginx/sites-available/rag
server {
    listen 80;
    server_name your-domain.com;

    # 前端
    location / {
        proxy_pass http://localhost:3000;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection 'upgrade';
        proxy_set_header Host $host;
        proxy_cache_bypass $http_upgrade;
    }

    # LangChain 后端。注意 proxy_pass 结尾没有斜杠之后的路径：
    # 这里用 / 结尾把 /langchain/api/... 重写成 /api/...
    location /langchain/ {
        proxy_pass http://localhost:8000/;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_buffering off;           # 必须：SSE 靠它才能边生成边推
        proxy_cache off;
        proxy_read_timeout 300s;       # 流式回答可能持续很久
        client_max_body_size 50m;      # 与 MAX_UPLOAD_MB 对齐
    }

    # Spring AI 后端，同上
    location /spring/ {
        proxy_pass http://localhost:8080/;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 300s;
        client_max_body_size 50m;
    }
}
```

同时把后端的 `CORS_ORIGINS` 改成前端真正的对外地址（如
`https://your-domain.com`）。经过反代之后 Origin 变成了域名而不再是
`http://localhost:3000`，不改就会被 CORS 拦掉。

如果不需要在线切后端，也可以只反代一个后端、把另一个变量指向同一个地址。

---

## 三、环境变量配置说明

### 3.1 安全（两个后端必须取相同值）

| 变量名 | 说明 | 默认值 | 必填 |
|--------|------|--------|------|
| `JWT_SECRET` | 签发 / 校验令牌的密钥。两端不一致则令牌互不承认；留空则每个进程各自生成随机值，重启后所有令牌失效 | 随机 | **生产必填** |
| `ACCESS_TOKEN_EXPIRE_HOURS` | 令牌有效期（小时） | 72 | 否 |
| `ADMIN_USERNAME` | 初始管理员用户名 | admin | 否 |
| `ADMIN_PASSWORD` | 初始管理员密码。留空则随机生成，并在启动日志里打印一次 | 随机 | 否 |
| `CORS_ORIGINS` | 允许的浏览器来源，逗号分隔。**不能用 `*`**：带凭证的请求不允许通配来源 | http://localhost:3000 | 是 |

### 3.2 共享存储（两个后端必须取相同值）

| 变量名 | 说明 | 默认值 | 必填 |
|--------|------|--------|------|
| `DB_PATH` | 共享 SQLite 元数据库路径（用户、知识库、文档、会话、反馈） | ../data/rag.db | 是 |
| `UPLOAD_DIR` | 上传文件落盘目录 | ./uploads | 否 |
| `MILVUS_HOST` | Milvus 地址 | localhost | 是 |
| `MILVUS_PORT` | Milvus 端口 | 19530 | 是 |
| `MILVUS_TOKEN` | 托管实例（如 Zilliz Cloud）的鉴权 token；compose 里的 Milvus 无鉴权 | - | 否 |
| `MILVUS_DATABASE` | Milvus database 名 | default | 否 |
| `NEO4J_URI` | Neo4j 连接 URI | bolt://localhost:7687 | 是 |
| `NEO4J_USER` | Neo4j 用户名 | neo4j | 是 |
| `NEO4J_PASSWORD` | Neo4j 密码 | neo4j123456 | 是 |

### 3.3 LLM 与 Embedding

| 变量名 | 说明 | 默认值 | 必填 |
|--------|------|--------|------|
| `LLM_PROVIDER` | LLM 提供商 (openai/ollama/volcengine) | openai | 是 |
| `OPENAI_API_KEY` | OpenAI API Key | - | 使用 OpenAI 时必填 |
| `OPENAI_API_BASE` | OpenAI 接口地址。**两端默认值不同**：LangChain 需要带 `/v1`，Spring AI 会自己拼 `/v1`，所以 Java 端应填不带后缀的地址（compose 里对应 `SPRING_OPENAI_API_BASE`）。Java 端也会主动去掉结尾的 `/v1`，填错不会导致 `/v1/v1` 的 404 | Python: https://api.openai.com/v1<br>Java: https://api.openai.com | 否 |
| `OPENAI_MODEL` | 使用的模型 | gpt-4o | 否 |
| `OLLAMA_BASE_URL` | Ollama 服务地址。容器内要用 `http://host.docker.internal:11434` | http://localhost:11434 | 使用 Ollama 时必填 |
| `OLLAMA_MODEL` | Ollama 模型名 | qwen2:7b | 否 |
| `VOLCENGINE_API_KEY` | 火山引擎 API Key（**仅 Python 后端支持**） | - | 使用火山引擎时必填 |
| `VOLCENGINE_API_BASE` | 火山引擎 API 地址 | https://ark.cn-beijing.volces.com/api/v3 | 否 |
| `VOLCENGINE_MODEL` | 火山引擎模型/端点 ID | - | 使用火山引擎时必填 |
| `EMBEDDING_PROVIDER` | Embedding 提供商 (openai/local)。`local` 仅 Python 后端支持，Java 端会告警并回退 openai | openai | 是 |
| `OPENAI_EMBEDDING_MODEL` | Embedding 模型名 | text-embedding-3-small | 否 |
| `LOCAL_EMBEDDING_MODEL` | 本地 Embedding 模型 | BAAI/bge-m3 | 使用 local 时 |
| `EMBEDDING_BATCH_SIZE` | 单次向量化的分块数 | 64 | 否 |

### 3.4 检索与重排

| 变量名 | 说明 | 默认值 | 必填 |
|--------|------|--------|------|
| `RERANKER_PROVIDER` | `local`（交叉编码器，仅 Python）/ `llm`（模型打分）/ `none`（沿用向量顺序） | llm | 否 |
| `RERANKER_MODEL` | `local` 模式使用的模型 | BAAI/bge-reranker-v2-m3 | 否 |
| `RERANK_TOP_K` | 重排后保留的片段数 | 5 | 否 |
| `RERANK_THRESHOLD` | 低于此分值的片段丢弃 | 3.0 | 否 |

### 3.5 摄取

| 变量名 | 说明 | 默认值 | 必填 |
|--------|------|--------|------|
| `CHUNK_SIZE` | 文本分块大小 | 1000 | 否 |
| `CHUNK_OVERLAP` | 分块重叠 | 200 | 否 |
| `MAX_UPLOAD_MB` | 单文件上限，反代的 `client_max_body_size` 要跟着改 | 50 | 否 |
| `GRAPH_MAX_CHUNKS` | 每次摄取送去做实体抽取的分块上限，每块一次 LLM 调用——这是上传大文档时最主要的成本开关 | 40 | 否 |

### 3.6 前端（构建期）

这三个是 `NEXT_PUBLIC_*`，在 `npm run build` / `docker build` 时被内联进 JS bundle，
运行时改环境变量无效，而且**任何人都能从页面里读到，不要放密钥**。

| 变量名 | 说明 | 默认值 | 必填 |
|--------|------|--------|------|
| `NEXT_PUBLIC_SPRING_API_URL` | 浏览器访问 Spring 后端的地址 | http://localhost:8080 | 是 |
| `NEXT_PUBLIC_LANGCHAIN_API_URL` | 浏览器访问 Python 后端的地址 | http://localhost:8000 | 是 |
| `NEXT_PUBLIC_DEFAULT_BACKEND` | 首次访问默认用哪个后端：`spring` / `langchain`。之后存在 localStorage 的 `rag-preferences` 里，由设置页修改 | spring | 否 |

---

## 四、常用 LLM 配置示例

### 4.1 OpenAI

```env
LLM_PROVIDER=openai
OPENAI_API_KEY=sk-xxxxxxxxxxxxxxxx
OPENAI_API_BASE=https://api.openai.com/v1
OPENAI_MODEL=gpt-4o
```

### 4.2 Ollama (本地模型)

```env
LLM_PROVIDER=ollama
OLLAMA_BASE_URL=http://localhost:11434
OLLAMA_MODEL=qwen2:7b
```

### 4.3 火山引擎 (字节跳动)

```env
LLM_PROVIDER=volcengine
VOLCENGINE_API_KEY=your-ark-api-key
VOLCENGINE_API_BASE=https://ark.cn-beijing.volces.com/api/v3
VOLCENGINE_MODEL=your-endpoint-id
```

### 4.4 其他 OpenAI 兼容接口

任何兼容 OpenAI API 格式的服务都可以直接使用：

```env
LLM_PROVIDER=openai
OPENAI_API_KEY=your-key
OPENAI_API_BASE=https://your-custom-api.com/v1
OPENAI_MODEL=your-model-name
```

适用于：DeepSeek、智谱 AI (GLM)、月之暗面 (Kimi)、百川等。

---

## 五、常见问题排查

### 5.1 Milvus 启动失败

```bash
# 检查日志
docker logs rag-milvus

# compose 里已经用 condition: service_healthy 让 Milvus 等 etcd 和 MinIO 就绪，
# 所以“依赖未就绪”这一类失败基本不会再出现。仍然起不来时先重启看看：
docker compose restart milvus

# 如果持续失败，清理数据重来
docker compose down -v
docker compose up -d
```

### 5.2 Neo4j 连接失败

```bash
# 检查 Neo4j 是否就绪
docker logs rag-neo4j

# 首次启动可能需要等待较长时间
# 验证连接
docker exec rag-neo4j cypher-shell -u neo4j -p neo4j123456 "RETURN 1"
```

### 5.3 Embedding 维度不匹配

如果切换了 Embedding 模型（如从 OpenAI 切到 BGE），需要重建 Milvus collection：
1. 删除对应知识库
2. 重新创建并重新上传文档

### 5.4 内存不足

Milvus standalone 模式推荐 8GB+。如果内存不足：
- 减小 `nlist` 参数
- 使用更小的 Embedding 维度
- 或使用 Milvus Lite (纯嵌入模式，适合开发)

### 5.5 前端无法连接后端 / 浏览器报 CORS 错误

CORS **不是** allow-all：后端只放行 `CORS_ORIGINS` 里列出的来源，默认只有
`http://localhost:3000`。而且这里不能填 `*`——带凭证的请求不允许通配来源，两个后端都会
在检测到 `*` 时关闭 `allow_credentials`，结果是登录态发不出去。

排查顺序：

1. 前端实际的对外地址是否在两个后端的 `CORS_ORIGINS` 里（换域名、加反代、改端口都要同步改）。
2. `NEXT_PUBLIC_SPRING_API_URL` / `NEXT_PUBLIC_LANGCHAIN_API_URL` 是否指向浏览器能访问
   的地址。这两个值是构建期内联的，改完要重新 build 前端。
3. 后端是否真的起来了：`curl http://<后端地址>/health`。

### 5.6 切换后端之后被踢回登录页 / 提示登录已过期

两个后端的 `JWT_SECRET` 不一致，A 签发的令牌 B 验不过。检查两端配置取的是同一个值；
如果留空，每个进程启动时都会生成自己的随机密钥（启动日志里有告警）。

### 5.7 两个后端看到的知识库不一样

`DB_PATH` 没指向同一个文件。分别看两端启动日志里打印的元数据库路径：Python 后端打印
`Metadata store: ...`。注意相对路径是相对各自的工作目录解析的，本地开发时
`langchain-rag/` 和 `spring-ai-rag/` 下的 `../data/rag.db` 恰好都落在项目根目录的
`data/` 下；换了启动目录就会分叉。容器里两端都固定为 `/app/data/rag.db`，并挂同一个卷。

### 5.8 上传的文档一直停在 processing

解析、向量化、图谱抽取都在后台跑，前端会轮询状态。一直不动的话看后端日志：常见原因是
LLM 或 Embedding 的 API Key 无效、额度耗尽，或 `GRAPH_MAX_CHUNKS` 设得很大导致实体抽取
排了很久的队。失败会把原因写进文档记录，前端在状态徽标下面直接显示。

### 5.9 上传大文件返回 413

`MAX_UPLOAD_MB` 同时控制两个后端的请求体上限（Spring 侧还会设置 multipart 上限）。走了
Nginx 的话还要把 `client_max_body_size` 改成同一个值，否则请求在反代就被截断了。

---

## 六、端口速查表

| 服务 | 端口 | 用途 |
|------|------|------|
| Next.js 前端 | 3000 | Web UI |
| LangChain 后端 | 8000 | Python API |
| Spring AI 后端 | 8080 | Java API |
| Milvus | 19530 | 向量数据库 gRPC |
| Milvus Health | 9091 | 健康检查 |
| Neo4j HTTP | 7474 | Neo4j Browser |
| Neo4j Bolt | 7687 | Neo4j 连接 |
| MinIO API | 9000 | 对象存储 |
| MinIO Console | 9001 | MinIO 管理界面 |
| Attu | 3300 | Milvus 管理界面 |
| Ollama | 11434 | 本地模型服务 |

---

## 七、快速启动命令汇总 (Mac)

```bash
# 终端 1: 基础设施
cd docker && docker compose up -d
docker compose ps                      # 等 milvus / neo4j 变成 healthy

# 终端 2: Python 后端
cd langchain-rag && source venv/bin/activate && python main.py

# 终端 3: Java 后端（可以和 Python 后端同时跑）
cd spring-ai-rag && mvn spring-boot:run

# 终端 4: 前端
cd frontend && npm run dev

# 打开浏览器访问 http://localhost:3000
```

两个后端可以同时开着，在前端设置页来回切，对同一个知识库比较两种实现的回答。前提是
`langchain-rag/.env` 和 Java 端的环境变量里：

- `JWT_SECRET` 取同一个值（否则切换后端会掉登录态）
- `DB_PATH` 指向同一个文件（默认都是项目根目录下的 `data/rag.db`）

全容器方式一条命令：

```bash
cd docker && docker compose --profile apps up -d --build
```
