package com.rag.config;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Connects to the MCP (Model Context Protocol) servers listed in the shared
 * {@code config/mcp-servers.json} (see docs/CONTRACT.md section 10) and exposes
 * their tools as a {@link ToolCallback} array for {@link
 * com.rag.service.LlmService} to pass into {@code ToolCallingChatOptions}.
 *
 * <p>Any single server that fails to connect is logged and skipped — tool
 * calling is an enhancement, not a hard dependency, the same stance {@link
 * ProviderBeans#ifUsable} takes for an optional chat/embedding provider. An
 * empty {@code ToolCallback[]} bean (no servers configured, or all failed)
 * leaves {@code LlmService} behaving exactly as before this feature existed.
 */
@Configuration
@Slf4j
public class McpToolsConfig {

    private final List<McpSyncClient> clients = new ArrayList<>();

    @Bean
    public ToolCallback[] mcpToolCallbacks(RagProperties props) {
        List<ServerConfig> servers = readConfig(props.getMcpServersConfig());
        for (ServerConfig server : servers) {
            if (!server.enabled) {
                continue;
            }
            try {
                clients.add(connect(server));
                log.info("Connected to MCP server '{}' ({})", server.name, server.transport);
            } catch (Exception e) {
                log.warn("Failed to connect to MCP server '{}': {}", server.name, e.getMessage());
            }
        }
        if (clients.isEmpty()) {
            return new ToolCallback[0];
        }
        return new SyncMcpToolCallbackProvider(clients).getToolCallbacks();
    }

    @PreDestroy
    void closeClients() {
        for (McpSyncClient client : clients) {
            try {
                client.closeGracefully();
            } catch (Exception e) {
                log.debug("Error closing MCP client: {}", e.getMessage());
            }
        }
    }

    private McpSyncClient connect(ServerConfig server) {
        McpClientTransport transport = switch (server.transport) {
            case "stdio" -> stdioTransport(server);
            case "sse" -> sseTransport(server);
            default -> throw new IllegalArgumentException(
                    "Unsupported MCP transport: " + server.transport);
        };
        McpSyncClient client = McpClient.sync(transport).build();
        client.initialize();
        return client;
    }

    private static McpClientTransport stdioTransport(ServerConfig server) {
        if (server.command == null || server.command.isBlank()) {
            throw new IllegalArgumentException("stdio server requires 'command'");
        }
        ServerParameters.Builder builder = ServerParameters.builder(server.command);
        if (server.args != null) {
            builder.args(server.args);
        }
        if (server.env != null) {
            builder.env(server.env);
        }
        return new StdioClientTransport(builder.build());
    }

    private static McpClientTransport sseTransport(ServerConfig server) {
        if (server.url == null || server.url.isBlank()) {
            throw new IllegalArgumentException("sse server requires 'url'");
        }
        return HttpClientSseClientTransport.builder(server.url).build();
    }

    private static List<ServerConfig> readConfig(String path) {
        Path configPath = Path.of(path);
        if (!Files.isRegularFile(configPath)) {
            log.info("No MCP server config at {}; MCP tool calling is disabled", path);
            return List.of();
        }
        try (Reader reader = new FileReader(configPath.toFile())) {
            McpServersFile file = new Gson().fromJson(reader, McpServersFile.class);
            return file == null || file.servers == null ? List.of() : file.servers;
        } catch (IOException | JsonSyntaxException e) {
            log.warn("Failed to read MCP server config at {}: {}", path, e.getMessage());
            return List.of();
        }
    }

    /** Shape of {@code config/mcp-servers.json}; see docs/CONTRACT.md section 10. */
    private static class McpServersFile {
        List<ServerConfig> servers;
    }

    private static class ServerConfig {
        String name;
        boolean enabled;
        String transport;
        String command;
        List<String> args;
        Map<String, String> env;
        String url;
    }
}
