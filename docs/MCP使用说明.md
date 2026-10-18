# MCP 工具调用使用说明

Java 后端（`spring-ai-rag`）支持连接 MCP (Model Context Protocol) server，把
第三方工具（文件系统、网页抓取、搜索等）接给对话模型用。模型在生成回答时会
自己判断要不要调用，不需要用户在前端做任何额外操作。

配置格式的完整定义见 [CONTRACT.md 第 10 节](CONTRACT.md)。本文只讲"怎么用"。

> **当前只有 Java 后端实现了这个功能**，Python 后端（`langchain-rag`）尚未接入。

## 一、怎么接一个 MCP server

### 1. 编辑 `config/mcp-servers.json`

仓库根目录下的 `config/mcp-servers.json`，默认是一条禁用的占位配置：

```json
{
  "servers": [
    {
      "name": "example",
      "enabled": false,
      "transport": "stdio",
      "command": "npx",
      "args": ["-y", "@example/mcp-server"],
      "env": {}
    }
  ]
}
```

把它换成真正要用的 server，并把 `enabled` 改成 `true`。`servers` 是数组，可以
同时配多个。

**`transport: "stdio"`**（本地起一个子进程通信，大多数免注册的参考 server 用这个）：

```json
{
  "name": "filesystem",
  "enabled": true,
  "transport": "stdio",
  "command": "npx",
  "args": ["-y", "@modelcontextprotocol/server-filesystem", "/path/to/allowed/dir"],
  "env": {}
}
```

**`transport: "sse"`**（连一个远程 server）：

```json
{
  "name": "some-remote-server",
  "enabled": true,
  "transport": "sse",
  "url": "https://example.com/sse"
}
```

> 目前只支持 `stdio` 和 `sse` 两种。不支持 Streamable HTTP（新一代远程 MCP
> server 常用的协议，例如 DeepWiki）——要支持它需要把 `spring-ai.version` 从
> 1.0.0 升到 1.0.7 以上，是一次独立的升级工作，详见下方"常见问题"。

### 2. 重启 Java 后端

MCP server 是**启动时**连接的，改配置后必须重启才生效：

```bash
cd spring-ai-rag
mvn package -DskipTests
java -jar target/spring-ai-rag-1.0.0-SNAPSHOT.jar
```

或者本地开发直接 `mvn spring-boot:run`。

### 3. 确认连接成功

看启动日志，连上了会打一行：

```
INFO com.rag.config.McpToolsConfig : Connected to MCP server '<name>' (stdio)
```

某个 server 连不上也不会导致启动失败，只会打一行 `WARN`，模型照常回答，只是
少了那个工具可用。

### 4. 直接聊天测试

正常走 `/api/chat` 问问题即可，问法上明确提一下想用的能力，模型会自己判断
要不要调用工具。例如接了 `filesystem` 后：

> 帮我看看 /path/to/allowed/dir 下有哪些文件，读一下 xxx.txt 的内容

检索路由（`rag`/`graph`/`hybrid`，问题命中知识库触发检索）和闲聊路由
（`direct`，查询分析器判定跟知识库无关的通用问题）都会带上工具，模型自己
判断要不要调用。

## 二、当前已接入：`weather-demo`（自建演示工具）

`config/mcp-servers.json` 现在接的是仓库自带的一个最小 MCP server
（[mcp-servers/weather-demo/](../mcp-servers/weather-demo/)），不调用任何真实
气象 API，`get_weather` 工具无论问哪个城市都固定回答"晴朗，23°C"。存在的
意义是验证"模型确实在调用外部工具，而不是凭自己的知识编"——如果聊天回答里
出现"晴朗 23°C"这个具体数字，就说明工具调用链路通了。

想自己改成真正查天气（接个真实 API）或者加别的固定返回逻辑，照着
`mcp-servers/weather-demo/index.js` 的样子改 `get_weather` 的实现、或者加
一个新的 `server.registerTool(...)` 调用即可，不需要碰 Java 代码：

```bash
cd mcp-servers/weather-demo
npm install          # 首次才需要
# 改完 index.js 后，重启 Java 后端（见上面"怎么接一个 MCP server"第 2 步）即可生效
```

`config/mcp-servers.json` 里的配置：

```json
{
  "name": "weather-demo",
  "enabled": true,
  "transport": "stdio",
  "command": "node",
  "args": ["../mcp-servers/weather-demo/index.js"],
  "env": {}
}
```

`args` 里的路径是相对 Java 进程工作目录（即 `spring-ai-rag/`）解析的，所以
写的是 `../mcp-servers/...`。

## 三、现成可用的公开 MCP server

不需要注册、不需要 API key，`npx`/`uvx` 一行命令就能跑：

| server | 功能 | 启动方式 |
|---|---|---|
| `@modelcontextprotocol/server-filesystem` | 读写指定目录下的文件 | `npx -y @modelcontextprotocol/server-filesystem /some/dir` |
| `@modelcontextprotocol/server-memory` | 简单的知识图谱式"记忆"，对话间存笔记 | `npx -y @modelcontextprotocol/server-memory` |
| `@modelcontextprotocol/server-sequentialthinking` | 结构化分步推理 | `npx -y @modelcontextprotocol/server-sequentialthinking` |
| `mcp-server-fetch` | 抓取 URL，转成文本/Markdown 给模型 | 需要 Python 3.10+：`pip install mcp-server-fetch`，`command` 填 `python3`，`args` 填 `["-m", "mcp_server_fetch"]`；或用 `uvx mcp-server-fetch`（本机需先装 `uv`） |
| `mcp-server-time` | 查当前时间、时区转换 | 同上，需要 `uv`/`uvx` |
| `@modelcontextprotocol/server-git` | 读本地 git 仓库的 log/diff/status | `uvx mcp-server-git` |

需要注册 API key 的（搜索、GitHub、Slack 等）本文不展开，用的时候按对应
server 的文档配置 `env` 字段传 token。

## 四、常见问题

**改了 `config/mcp-servers.json` 但没生效？**

必须重启 Java 后端——连接是启动时建立的，不是热加载。

**`npx` 第一次跑很慢？**

`npx -y` 第一次会去下载包，之后有本地缓存会快很多。确保机器能访问 npm 源。

**想接 DeepWiki 之类的远程 MCP server，但一直连不上？**

多半是协议不匹配。DeepWiki 等新一代远程 server 已经从 SSE 换成了
Streamable HTTP，而当前项目锁定的 `spring-ai.version=1.0.0` 自带的 MCP SDK
（0.10.0）没有 Streamable HTTP 的客户端实现。验证方法：

```bash
curl -i https://your-server/sse       # 返回 410 Gone 且提示 "deprecated"，就是这个问题
```

要支持需要升级 `spring-ai.version` 到 1.0.7 及以上（连带 MCP SDK 跳到
0.18.2、Spring Boot 升到 3.5.14+），这跟项目从 1.0.0-M1 升到 1.0.0 时一样，
会踩到几个 API 破坏性变更（Options 的 builder 写法、`ChatModel` 构造方式等），
需要专门排一次任务来做，不是改配置能解决的。

**`mcp-server-fetch` 装不上，报 Python 版本不符？**

它要求 Python 3.10+。本机系统自带的是 3.9 的话装不了，先 `brew install
python@3.12` 装一个新版本，再用新版本的 `pip3` 装。

**工具调用会不会把前端 SSE 事件搞乱？**

不会。工具调用发生在"生成"阶段内部（`stage=generating` 的 `start`/`done`
之间），对前端完全透明，不会多出新的事件类型，详见 CONTRACT.md 第 4 节。
