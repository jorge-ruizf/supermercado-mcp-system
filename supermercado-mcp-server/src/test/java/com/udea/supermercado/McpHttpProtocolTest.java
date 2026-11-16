package com.udea.supermercado;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.udea.supermercado.model.Producto;
import com.udea.supermercado.repository.ProductoRepository;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class McpHttpProtocolTest {

	private static final String H2_DATABASE = "supermercado_mcp_http_"
			+ UUID.randomUUID().toString().replace("-", "");

	private static final List<String> EXPECTED_TOOLS = List.of(
			"listarProductos",
			"buscarProductos",
			"crearPedido",
			"agregarProductoAPedido",
			"verPedido",
			"confirmarPedido",
			"consultarEstadoPedido",
			"cancelarPedido");

	@DynamicPropertySource
	static void datasource(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", () -> "jdbc:h2:mem:" + H2_DATABASE
				+ ";MODE=MariaDB;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
	}

	private final ObjectMapper objectMapper = new ObjectMapper();

	@LocalServerPort
	private int port;

	@Autowired
	private ProductoRepository productoRepository;

	private McpSyncClient client;

	private Producto arroz;

	private Producto leche;

	@BeforeEach
	void connectAndSeed() {
		HttpClientStreamableHttpTransport transport =
				HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + this.port)
						.endpoint("/mcp")
						.connectTimeout(Duration.ofSeconds(10))
						.build();
		this.client = McpClient.sync(transport)
				.requestTimeout(Duration.ofSeconds(20))
				.initializationTimeout(Duration.ofSeconds(20))
				.clientInfo(new McpSchema.Implementation("supermercado-mcp-test-client", "1.0.0"))
				.build();
		this.client.initialize();

		String suffix = UUID.randomUUID().toString().substring(0, 8);
		this.arroz = producto("Arroz " + suffix, "Granos", "3500.00", 10);
		this.leche = producto("Leche " + suffix, "Lácteos", "4200.50", 1);
	}

	@AfterEach
	void disconnect() {
		if (this.client != null) {
			this.client.closeGracefully();
		}
	}

	@Test
	void handshakeExposesServerIdentityAndApprovedTools() {
		McpSchema.InitializeResult initialization = this.client.getCurrentInitializationResult();

		assertThat(initialization).isNotNull();
		assertThat(initialization.protocolVersion()).isIn("2025-03-26", "2025-06-18");
		assertThat(initialization.serverInfo().name()).isEqualTo("supermercado-mcp-server");

		McpSchema.ListToolsResult tools = this.client.listTools();
		assertThat(tools.tools()).extracting(McpSchema.Tool::name)
				.containsExactlyInAnyOrderElementsOf(EXPECTED_TOOLS);
		assertThat(tools.tools()).allSatisfy(tool -> {
			assertThat(tool.description()).isNotBlank();
			assertThat(tool.inputSchema().properties().keySet()).doesNotContain("arg0", "version");
		});
	}

	@Test
	void fullOrderLifecycleDeductsAndRestoresStock() throws Exception {
		JsonNode created = json(call("crearPedido", Map.of(
				"clienteNombre", "SMOKE-" + UUID.randomUUID(),
				"direccionEntrega", "Calle 10 # 20-30",
				"telefono", "3001234567")));
		long pedidoId = created.path("id").asLong();
		assertThat(pedidoId).isPositive();
		assertThat(created.path("estado").asText()).isEqualTo("PENDIENTE");
		assertThat(created.path("total").decimalValue()).isEqualByComparingTo("0.00");

		JsonNode added = json(call("agregarProductoAPedido", Map.of(
				"pedidoId", pedidoId,
				"productoId", this.arroz.getId(),
				"cantidad", 2)));
		assertThat(added.path("total").decimalValue()).isEqualByComparingTo("7000.00");

		JsonNode detalle = json(call("verPedido", Map.of("pedidoId", pedidoId)));
		assertThat(detalle.path("productos")).hasSize(1);
		assertThat(detalle.path("productos").get(0).path("cantidad").asInt()).isEqualTo(2);
		assertThat(detalle.path("productos").get(0).path("precioUnitario").decimalValue())
				.isEqualByComparingTo("3500.00");

		JsonNode confirmed = json(call("confirmarPedido", Map.of("pedidoId", pedidoId)));
		assertThat(confirmed.path("estado").asText()).isEqualTo("CONFIRMADO");
		assertThat(stockOf(this.arroz)).isEqualTo(8);

		JsonNode state = json(call("consultarEstadoPedido", Map.of("pedidoId", pedidoId)));
		assertThat(state.path("estado").asText()).isEqualTo("CONFIRMADO");
		assertThat(state.path("fechaCreacion").asText()).isNotBlank();

		json(call("confirmarPedido", Map.of("pedidoId", pedidoId)));
		assertThat(stockOf(this.arroz)).isEqualTo(8);

		JsonNode cancelled = json(call("cancelarPedido", Map.of("pedidoId", pedidoId)));
		assertThat(cancelled.path("estado").asText()).isEqualTo("CANCELADO");
		assertThat(stockOf(this.arroz)).isEqualTo(10);
	}

	@Test
	void listsAndSearchesProducts() throws Exception {
		JsonNode products = json(call("listarProductos", Map.of()));
		assertThat(products.isArray()).isTrue();
		assertThat(products).isNotEmpty();

		JsonNode found = json(call("buscarProductos", Map.of("texto", "granos")));
		assertThat(found).isNotEmpty();
		assertThat(found.get(0).path("categoria").asText()).isEqualTo("Granos");
	}

	@Test
	void returnsSafeSpanishErrorsWithoutLeakingInternals() {
		CallToolResult unknownProduct = call("agregarProductoAPedido", Map.of(
				"pedidoId", crearPedidoVacio(),
				"productoId", 999999L,
				"cantidad", 1));
		assertThat(unknownProduct.isError()).isTrue();
		assertThat(textOf(unknownProduct)).contains("No existe un producto con id 999999.");

		CallToolResult noStock = call("agregarProductoAPedido", Map.of(
				"pedidoId", crearPedidoVacio(),
				"productoId", this.leche.getId(),
				"cantidad", 2));
		assertThat(noStock.isError()).isTrue();
		assertThat(textOf(noStock)).contains("No hay stock suficiente").contains("Disponible: 1");

		CallToolResult unknownOrder = call("verPedido", Map.of("pedidoId", 999999L));
		assertThat(unknownOrder.isError()).isTrue();
		assertThat(textOf(unknownOrder)).contains("No existe un pedido con id 999999.");

		for (CallToolResult result : List.of(unknownProduct, noStock, unknownOrder)) {
			assertThat(textOf(result))
					.doesNotContain("Exception")
					.doesNotContain("SELECT")
					.doesNotContain("com.udea")
					.doesNotContain("\tat ");
		}
	}

	private long crearPedidoVacio() {
		JsonNode pedido = json(call("crearPedido", Map.of(
				"clienteNombre", "Cliente " + UUID.randomUUID(),
				"direccionEntrega", "Calle 1 # 2-3",
				"telefono", "3000000000")));
		return pedido.path("id").asLong();
	}

	private CallToolResult call(String tool, Map<String, Object> arguments) {
		return this.client.callTool(new McpSchema.CallToolRequest(tool, arguments));
	}

	private JsonNode json(CallToolResult result) {
		assertThat(result.isError()).as(textOf(result)).isNotEqualTo(Boolean.TRUE);
		try {
			return this.objectMapper.readTree(textOf(result));
		}
		catch (Exception ex) {
			throw new IllegalStateException("Respuesta MCP no es JSON: " + textOf(result), ex);
		}
	}

	private String textOf(CallToolResult result) {
		return result.content().stream()
				.filter(TextContent.class::isInstance)
				.map(TextContent.class::cast)
				.map(TextContent::text)
				.collect(Collectors.joining("\n"));
	}

	private int stockOf(Producto producto) {
		return this.productoRepository.findById(producto.getId()).orElseThrow().getStock();
	}

	private Producto producto(String nombre, String categoria, String precio, int stock) {
		Producto producto = new Producto();
		producto.setNombre(nombre);
		producto.setCategoria(categoria);
		producto.setPrecio(new BigDecimal(precio));
		producto.setStock(stock);
		return this.productoRepository.saveAndFlush(producto);
	}

}
