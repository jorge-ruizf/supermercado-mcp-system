package com.udea.supermercado.config;

import com.udea.supermercado.service.SupermercadoToolsService;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpToolsConfig {

	@Bean
	public ToolCallbackProvider supermercadoToolCallbackProvider(
			SupermercadoToolsService supermercadoToolsService) {
		return MethodToolCallbackProvider.builder()
				.toolObjects(supermercadoToolsService)
				.build();
	}

}
