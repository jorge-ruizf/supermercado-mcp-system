package com.udea.supermercado.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class McpToolsWiringTest {

	private static final List<String> EXPECTED_TOOLS = List.of(
			"listarProductos",
			"buscarProductos",
			"crearPedido",
			"agregarProductoAPedido",
			"verPedido",
			"confirmarPedido",
			"consultarEstadoPedido",
			"cancelarPedido");

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Autowired
	private ToolCallbackProvider toolCallbackProvider;

	@Test
	void registersExactlyTheApprovedSpanishTools() {
		assertThat(this.toolCallbackProvider.getToolCallbacks())
				.extracting(callback -> callback.getToolDefinition().name())
				.containsExactlyInAnyOrderElementsOf(EXPECTED_TOOLS);
	}

	@Test
	void everyToolHasANonBlankSpanishDescription() {
		for (ToolCallback callback : this.toolCallbackProvider.getToolCallbacks()) {
			assertThat(callback.getToolDefinition().description())
					.as("description of %s", callback.getToolDefinition().name())
					.isNotBlank();
		}
	}

	@Test
	void listarProductosHasNoParameters() throws Exception {
		JsonNode schema = schemaOf("listarProductos");

		assertThat(propertyNames(schema)).isEmpty();
	}

	@Test
	void buscarProductosRequiresText() throws Exception {
		JsonNode schema = schemaOf("buscarProductos");

		assertThat(propertyNames(schema)).containsExactly("texto");
		assertThat(schema.path("properties").path("texto").path("type").asText()).isEqualTo("string");
	}

	@Test
	void crearPedidoRequiresCustomerStrings() throws Exception {
		JsonNode schema = schemaOf("crearPedido");

		assertThat(propertyNames(schema))
				.containsExactlyInAnyOrder("clienteNombre", "direccionEntrega", "telefono");
		assertThat(requiredNames(schema))
				.containsExactlyInAnyOrder("clienteNombre", "direccionEntrega", "telefono");
	}

	@Test
	void agregarProductoAPedidoRequiresIdentifiersAndQuantity() throws Exception {
		JsonNode schema = schemaOf("agregarProductoAPedido");

		assertThat(propertyNames(schema))
				.containsExactlyInAnyOrder("pedidoId", "productoId", "cantidad");
		assertThat(schema.path("properties").path("pedidoId").path("type").asText()).isEqualTo("integer");
		assertThat(schema.path("properties").path("productoId").path("type").asText()).isEqualTo("integer");
		assertThat(schema.path("properties").path("cantidad").path("type").asText()).isEqualTo("integer");
	}

	@Test
	void singleOrderToolsRequirePedidoId() throws Exception {
		for (String tool : List.of("verPedido", "confirmarPedido", "consultarEstadoPedido", "cancelarPedido")) {
			JsonNode schema = schemaOf(tool);
			assertThat(propertyNames(schema)).as("parameters of %s", tool).containsExactly("pedidoId");
			assertThat(requiredNames(schema)).as("required of %s", tool).containsExactly("pedidoId");
			assertThat(schema.path("properties").path("pedidoId").path("type").asText())
					.as("pedidoId type of %s", tool)
					.isEqualTo("integer");
		}
	}

	@Test
	void noToolExposesSyntheticArgNamesOrTheOptimisticLockVersion() {
		for (ToolCallback callback : this.toolCallbackProvider.getToolCallbacks()) {
			String schema = callback.getToolDefinition().inputSchema();

			assertThat(schema).as("schema of %s", callback.getToolDefinition().name())
					.doesNotContain("arg0")
					.doesNotContain("\"version\"");
		}
	}

	private JsonNode schemaOf(String toolName) throws Exception {
		Map<String, ToolCallback> callbacks = new LinkedHashMap<>();
		for (ToolCallback callback : this.toolCallbackProvider.getToolCallbacks()) {
			callbacks.put(callback.getToolDefinition().name(), callback);
		}

		ToolCallback callback = callbacks.get(toolName);
		assertThat(callback).as("tool %s", toolName).isNotNull();
		return this.objectMapper.readTree(callback.getToolDefinition().inputSchema());
	}

	private List<String> propertyNames(JsonNode schema) {
		return fieldNames(schema.path("properties"));
	}

	private List<String> requiredNames(JsonNode schema) {
		JsonNode required = schema.path("required");
		if (!required.isArray()) {
			return List.of();
		}
		return java.util.stream.StreamSupport.stream(required.spliterator(), false)
				.map(JsonNode::asText)
				.toList();
	}

	private List<String> fieldNames(JsonNode node) {
		if (!node.isObject()) {
			return List.of();
		}
		java.util.List<String> names = new java.util.ArrayList<>();
		node.fieldNames().forEachRemaining(names::add);
		return names;
	}

}
