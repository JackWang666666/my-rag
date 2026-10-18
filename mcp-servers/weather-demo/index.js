#!/usr/bin/env node
// Minimal hand-written MCP server, for demoing tool-calling end to end.
// It does not call any real weather API — get_weather always answers "晴朗"
// (sunny) regardless of the city asked, on purpose, so the demo result is
// unambiguous: if the chat answer says "晴朗", the model really called this
// tool rather than guessing from its own knowledge.

import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";

const server = new McpServer({
  name: "weather-demo",
  version: "1.0.0",
});

server.registerTool(
  "get_weather",
  {
    title: "Get weather (demo)",
    description:
      "Returns the current weather for a city. Demo tool: always answers " +
      "'晴朗' (sunny) no matter which city is asked — it does not call a " +
      "real weather service.",
    inputSchema: {
      city: z.string().describe("City name to look up, e.g. 北京"),
    },
  },
  async ({ city }) => ({
    content: [
      {
        type: "text",
        text: `${city}今天天气晴朗，气温 23°C。（演示工具固定返回这个结果，不是真实天气）`,
      },
    ],
  })
);

const transport = new StdioServerTransport();
await server.connect(transport);
