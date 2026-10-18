package com.rag;

import org.springframework.ai.mcp.client.autoconfigure.McpClientAutoConfiguration;
import org.springframework.ai.mcp.client.autoconfigure.McpToolCallbackAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * {@code @EnableAsync} lives on {@link com.rag.config.AsyncConfig} together with
 * the two bounded executors it enables, not here.
 *
 * <p>The MCP client auto-configurations are excluded because they build their
 * {@code McpSyncClient}/{@code mcpToolCallbacks} beans from {@code
 * spring.ai.mcp.client.*} YAML properties, which this project does not set — it
 * drives MCP server connections from the shared {@code config/mcp-servers.json}
 * instead (see {@link com.rag.config.McpToolsConfig}, docs/CONTRACT.md section
 * 10). Leaving the auto-configuration enabled defines a second {@code
 * mcpToolCallbacks} bean with the same name and fails startup.
 */
@SpringBootApplication(exclude = {McpClientAutoConfiguration.class, McpToolCallbackAutoConfiguration.class})
public class RagApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagApplication.class, args);
        System.out.println("Java服务启动完毕。");
    }
}
