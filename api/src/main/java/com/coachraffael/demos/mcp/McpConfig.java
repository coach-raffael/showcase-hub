package com.coachraffael.demos.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registriert die @Tool-Methoden beim MCP-Server (Streamable HTTP unter /api/mcp). */
@Configuration
public class McpConfig {

    @Bean
    ToolCallbackProvider demoTools(DemoMcpTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
