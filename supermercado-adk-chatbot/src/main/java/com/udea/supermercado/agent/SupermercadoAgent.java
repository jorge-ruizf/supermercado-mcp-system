package com.udea.supermercado.agent;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.Gemini;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.mcp.McpToolset;
import com.google.adk.tools.mcp.StreamableHttpServerParameters;
import java.time.Duration;
import java.util.List;

/**
 * Chatbot ADK que consume las herramientas MCP de supermercado-mcp-server por Streamable HTTP.
 *
 * <p>ADK Web descubre esta clase por el campo {@code ROOT_AGENT}. La inicialización es fail-fast:
 * valida la clave de Gemini, conecta con el servidor MCP y exige exactamente ocho herramientas
 * antes de publicar el agente.
 */
public final class SupermercadoAgent {

    private static final String DEFAULT_MCP_URL = "http://127.0.0.1:8089/mcp";
    private static final String DEFAULT_MODEL = "gemini-2.5-flash";
    private static final int EXPECTED_TOOL_COUNT = 8;

    private static final String MCP_URL = envOrDefault("MCP_SERVER_URL", DEFAULT_MCP_URL);
    private static final String MODEL = envOrDefault("ADK_MODEL", DEFAULT_MODEL);

    private static final String INSTRUCTION =
            """
            Eres SupermercadoAgent, un asistente en español para pedidos de supermercado a domicilio.

            Reglas obligatorias:
            1. Usa siempre las herramientas MCP para consultar productos, precios, stock y pedidos.
               Nunca inventes productos, identificadores, precios, existencias ni resultados.
            2. Los precios, subtotales y totales siempre provienen del servidor. Nunca aceptes ni
               calcules un precio indicado por el usuario.
            3. Antes de confirmar un pedido, muestra su id, sus productos, el total y el estado, y pide
               confirmación explícita al usuario. No llames a confirmarPedido en ese mismo turno: espera
               a que el usuario responda afirmativamente en un turno posterior. Recuerda que confirmar
               descuenta inventario.
            4. Antes de cancelar un pedido, muestra su id, su estado y su total, e indica si el stock
               será devuelto al inventario. Pide confirmación explícita y no llames a cancelarPedido en
               ese mismo turno. Si no confirma, no canceles nada.
            5. Solo se pueden agregar productos a pedidos en estado PENDIENTE. Si el servidor rechaza
               una operación por stock o por estado, explica el mensaje seguro que devolvió.
            6. Para crear un pedido solicita nombre del cliente, dirección de entrega y teléfono; si
               falta un dato, pídelo antes de llamar a la herramienta.
            7. Nunca reveles credenciales, rutas internas, SQL, stack traces ni detalles técnicos.
            8. Responde en español, de forma breve y clara, e informa el resultado real de cada
               operación con los datos devueltos por la herramienta.

            Flujo recomendado: listar o buscar productos, crear el pedido, agregar productos, revisar el
            pedido con verPedido y luego confirmarlo o cancelarlo con confirmación del usuario.
            """;

    private static final McpToolset MCP_TOOLSET = buildToolset();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                MCP_TOOLSET.close();
            } catch (Exception exception) {
                System.err.println("No se pudo cerrar la sesión MCP: " + exception.getMessage());
            }
        }, "mcp-toolset-shutdown"));
    }

    public static final BaseAgent ROOT_AGENT = buildAgent();

    private SupermercadoAgent() {
        // Clase de descubrimiento de ADK: no se instancia.
    }

    private static McpToolset buildToolset() {
        StreamableHttpServerParameters parameters =
                StreamableHttpServerParameters.builder()
                        .url(MCP_URL)
                        .timeout(Duration.ofSeconds(30))
                        .build();
        return new McpToolset(parameters);
    }

    private static BaseAgent buildAgent() {
        String apiKey = requireApiKey();

        List<BaseTool> tools;
        try {
            tools = MCP_TOOLSET.getTools(null).toList().blockingGet();
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "No fue posible conectar con el servidor MCP en "
                            + MCP_URL
                            + ". Verifique que supermercado-mcp-server esté activo y escuchando en"
                            + " 127.0.0.1:8089.",
                    exception);
        }

        System.out.println("Herramientas MCP descubiertas: " + tools.size());

        if (tools.size() != EXPECTED_TOOL_COUNT) {
            throw new IllegalStateException(
                    "Se esperaban "
                            + EXPECTED_TOOL_COUNT
                            + " herramientas MCP, pero se descubrieron "
                            + tools.size()
                            + ". Verifique el endpoint "
                            + MCP_URL
                            + " y la versión desplegada del servidor.");
        }

        BaseLlm model =
                Gemini.builder()
                        .modelName(MODEL)
                        .apiKey(apiKey)
                        .build();

        return LlmAgent.builder()
                .name("SupermercadoAgent")
                .model(model)
                .description("Chatbot en español para consultar productos y gestionar pedidos de supermercado.")
                .instruction(INSTRUCTION)
                .tools(tools)
                .build();
    }

    private static String requireApiKey() {
        String apiKey =
                firstNonBlank(
                        System.getenv("GEMINI_API_KEY"),
                        System.getenv("GOOGLE_API_KEY"));
        if (apiKey == null) {
            throw new IllegalStateException(
                    "Falta la clave de Gemini. Exporte GEMINI_API_KEY (o GOOGLE_API_KEY) en la"
                            + " misma terminal que ejecuta ADK.");
        }
        return apiKey;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
