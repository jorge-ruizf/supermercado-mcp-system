# Sistema de Pedidos de Supermercado a Domicilio mediante Arquitectura MCP y ChatBot con Google ADK

**Fecha de entrega:** 15 de noviembre de 2026

**Repositorios del sistema:**

* **Sistema de Supermercado (Servidor MCP + ChatBot ADK):** [https://github.com/jorge-ruizf/supermercado-mcp-system](https://github.com/jorge-ruizf/supermercado-mcp-system)
* **Laboratorio Base — Servidor MCP de Tareas:** [https://github.com/jorge-ruizf/tareas-mcp-server](https://github.com/jorge-ruizf/tareas-mcp-server)
* **Laboratorio Base — Cliente Agente ADK:** [https://github.com/jorge-ruizf/lab3arq-sdk-gemini-mcp](https://github.com/jorge-ruizf/lab3arq-sdk-gemini-mcp)

---

## 1. Arquitectura General de la Solución

Para resolver el problema de pedidos de supermercado a domicilio mediante lenguaje natural sin comprometer la integridad del inventario ni de los precios, se diseñó una arquitectura desacoplada en **tres niveles** basada en el estándar **Model Context Protocol (MCP)** sobre transporte **Streamable HTTP**:

```text
┌────────────────────────────────────┐      Streamable HTTP (JSON-RPC)      ┌────────────────────────────────────┐
│     CLIENTE CHATBOT (Puerto 8080)  │ ───────────────────────────────────► │    SERVIDOR MCP (Puerto 8089)      │
│   supermercado-adk-chatbot         │      http://127.0.0.1:8089/mcp       │   supermercado-mcp-server          │
│                                    │ ◄─────────────────────────────────── │                                    │
│ • Java 21 + Spring Boot 4.0.2      │      8 Herramientas (@Tool)          │ • Java 21 + Spring Boot 3.5.16     │
│ • Google ADK 1.14.0 (AdkWebServer) │      Respuestas JSON + isError       │ • Spring AI 1.1.4 (MCP Sync)       │
│ • Modelo: gemini-3.5-flash-lite    │                                      │ • Spring Data JPA / Hibernate      │
└────────────────────────────────────┘                                      └─────────────────┬──────────────────┘
                                                                                              │ JDBC (DML restringido)
                                                                                              ▼
                                                                            ┌────────────────────────────────────┐
                                                                            │   BASE DE DATOS RELACIONAL         │
                                                                            │   MariaDB 11.8 (supermercado_mcp)  │
                                                                            │                                    │
                                                                            │ • Tablas: productos, pedidos,      │
                                                                            │   detalle_pedidos                  │
                                                                            │ • Usuario: super_app (sin DDL)     │
                                                                            └────────────────────────────────────┘

```

### Decisiones Arquitectónicas Clave

1. **Separación estricta de responsabilidades:** El agente conversacional (`supermercado-adk-chatbot`) actúa exclusivamente como cliente MCP; no contiene entidades JPA, credenciales de base de datos ni reglas de negocio. Toda la lógica transaccional reside en `supermercado-mcp-server`.
2. **Autoridad de precios y totales en el servidor:** El modelo de lenguaje nunca envía precios unitarios ni subtotales. Cuando el usuario solicita agregar un producto, el servidor consulta el precio oficial vigente en la tabla `productos` y recalcula el total del pedido.
3. **Aislamiento de red (*Loopback-only*):** Tanto el servidor MCP (`127.0.0.1:8089`) como la interfaz web del ChatBot (`127.0.0.1:8080`) se enlazan exclusivamente a la interfaz local para evitar exposición no autorizada en red.

---

## 2. Paso 1: Diseño y Aprovisionamiento de la Base de Datos (MariaDB)

Se seleccionó **MariaDB 11.8** como motor relacional transaccional (`InnoDB` con codificación `utf8mb4`). Aplicando el **principio de mínimo privilegio**, el esquema se creó de forma administrativa y se aprovisionó un usuario aplicativo (`super_app`) con permisos limitados únicamente a operaciones de manipulación de datos (`SELECT, INSERT, UPDATE, DELETE`), bloqueando cualquier sentencia DDL (`CREATE`, `ALTER`, `DROP`).

### Modelo Relacional

* **`productos`:** Almacena el catálogo (`id`, `nombre`, `categoria`, `precio` `DECIMAL(10,2)`, `stock` `INT`, `version` `BIGINT` para bloqueo optimista).
* **`pedidos`:** Registra la cabecera del pedido a domicilio (`id`, `cliente_nombre`, `direccion_entrega`, `telefono`, `estado` en máquina de estados `PENDIENTE -> CONFIRMADO -> CANCELADO`, `total` `DECIMAL(12,2)`, `fecha_creacion`, `version`).
* **`detalle_pedidos`:** Tabla asociativa entre `pedidos` y `productos` con integridad referencial (`ON DELETE CASCADE` hacia `pedidos`), almacenando `cantidad`, `precio_unitario` congelado al momento de agregar el ítem y `subtotal`.

Se cargó un catálogo inicial de **10 productos** distribuidos en las categorías *Despensa*, *Lácteos*, *Carnes*, *Frutas y Verduras*, *Panadería* y *Aseo*.

---

## 3. Paso 2: Implementación del Servidor MCP (`supermercado-mcp-server`)

El backend se construyó en **Java 21** con **Spring Boot 3.5.16** y **Spring AI 1.1.4** (`spring-ai-starter-mcp-server-webmvc`), organizando el código en capas con grafo acíclico de dependencias:

* **`config/`:**
* `DataSourceCredentialsValidator` (`BeanFactoryPostProcessor`): Valida al arranque (*fail-fast*) que `DB_USERNAME` y `DB_PASSWORD` estén definidas en el entorno antes de inicializar el pool de conexiones HikariCP.
* `McpToolsConfig`: Registra el bean `ToolCallbackProvider` mediante `MethodToolCallbackProvider` exponiendo los métodos anotados con `@Tool`.


* **`model/` y `repository/`:** Entidades JPA mapeadas contra el esquema existente con `spring.jpa.hibernate.ddl-auto=validate` y consultas con bloqueo pesimista (`LockModeType.PESSIMISTIC_WRITE`) para evitar condiciones de carrera sobre el inventario.
* **`service/SupermercadoService`:** Capa transaccional (`@Transactional`) que implementa:
* **Agregación de ítems (`agregarProductoAPedido`):** Verifica que el pedido esté en estado `PENDIENTE`, consolida líneas duplicadas del mismo producto, valida que la cantidad acumulada no supere el `stock` disponible y recalcula `total` con aritmética exacta (`BigDecimal` con escala 2 y redondeo `HALF_UP`).
* **Confirmación atómica (`confirmarPedido`):** Bloquea primero el pedido y luego los productos involucrados ordenados ascendentemente por `id` (previniendo *deadlocks*), valida nuevamente el inventario, descuenta el `stock` y transiciona el pedido a `CONFIRMADO`. La operación es idempotente si el pedido ya estaba confirmado.
* **Cancelación con compensación (`cancelarPedido`):** Si el pedido estaba `CONFIRMADO`, bloquea los productos en orden ascendente y restaura el inventario antes de marcarlo como `CANCELADO`. Si estaba `PENDIENTE`, simplemente lo cancela sin alterar inventario.


* **`service/SupermercadoToolsService` y `exception/`:** Adaptador delgado sin acceso directo a repositorios ni SQL que traduce errores de dominio a mensajes claros en español (`isError: true` en JSON-RPC), capturando cualquier excepción inesperada para evitar fugas de *stack traces* o detalles internos.

### Catálogo de 8 Herramientas MCP Expuestas

| Herramienta (`@Tool`) | Parámetros | Función en el Flujo de Compra |
| --- | --- | --- |
| `listarProductos` | *(ninguno)* | Lista todos los productos disponibles ordenados alfabéticamente. |
| `buscarProductos` | `texto` (`String`) | Filtra productos por coincidencia parcial en nombre o categoría. |
| `crearPedido` | `clienteNombre`, `direccionEntrega`, `telefono` | Abre un nuevo pedido a domicilio en estado `PENDIENTE` con total `0.00`. |
| `agregarProductoAPedido` | `pedidoId`, `productoId`, `cantidad` | Agrega o actualiza la cantidad de un producto validando stock y calculando subtotal/total en servidor. |
| `verPedido` | `pedidoId` | Devuelve el detalle completo del pedido (cliente, dirección, ítems, precios unitarios y total). |
| `confirmarPedido` | `pedidoId` | Confirma el pedido y descuenta atómicamente las unidades del inventario. |
| `consultarEstadoPedido` | `pedidoId` | Consulta el estado actual (`PENDIENTE`, `CONFIRMADO`, `CANCELADO`), total y fecha. |
| `cancelarPedido` | `pedidoId` | Cancela el pedido y restaura el inventario si ya había sido confirmado. |

---

## 4. Paso 3: Implementación del Cliente ChatBot con Google ADK (`supermercado-adk-chatbot`)

El cliente conversacional se desarrolló como un proyecto Maven independiente utilizando **Java 21**, **Spring Boot 4.0.2** y **Google ADK 1.14.0** (`google-adk` y `google-adk-dev`):

1. **Conexión MCP Nativa (`McpToolset`):** En `SupermercadoAgent.java`, se configuró `StreamableHttpServerParameters` apuntando a `[http://127.0.0.1:8089/mcp](http://127.0.0.1:8089/mcp)`. Al iniciar, el agente descubre dinámicamente las **8 herramientas** del servidor y registra un *shutdown hook* en la JVM para cerrar la sesión MCP limpiamente.
2. **Validación temprana (*Fail-Fast*):** Si falta la variable de entorno `GEMINI_API_KEY`, el servidor MCP no responde o el número de herramientas descubiertas difiere de 8, la aplicación detiene su arranque con un mensaje explícito.
3. **Ingeniería de Prompts y Guardia de Confirmación en Dos Turnos:** Se configuró `LlmAgent` (`ROOT_AGENT`) respaldado por el modelo **`gemini-3.5-flash-lite`** con instrucciones estrictas en español:
* Prohibición de inventar productos, precios, existencias o identificadores de pedido.
* Obligación de presentar el resumen completo del pedido (ítems, dirección y total) y **solicitar confirmación explícita en un turno previo** antes de invocar acciones con impacto en inventario (`confirmarPedido` o `cancelarPedido`).


4. **Interfaz Web Conversacional:** Mediante `com.google.adk.web.AdkWebServer` en el puerto `8080`, se habilitó la interfaz gráfica web para interactuar con `SupermercadoAgent` e inspeccionar las trazas de invocación de herramientas en tiempo real.

---

## 5. Paso 4: Verificación Automatizada y Pruebas en Vivo

La validación del sistema se ejecutó en dos niveles:

### 5.1. Suite Automatizada del Servidor (`./mvnw -B clean test`)

Se ejecutaron **35 pruebas automatizadas** con **0 fallos y 0 errores** sobre perfil de prueba aislado (H2 en modo MariaDB):

* `SupermercadoServiceTest` (12 pruebas): Cálculo de precios en servidor, consolidación de líneas repetidas, rechazo por stock insuficiente, deducción atómica en confirmación, restauración en cancelación e idempotencia.
* `McpToolsWiringTest` (8 pruebas): Validación de nombres, descripciones en español y esquemas JSON de las 8 herramientas.
* `McpHttpProtocolTest` (4 pruebas): Cliente real `McpSyncClient` ejecutando handshake JSON-RPC (`protocol 2025-06-18`), `tools/list` y el ciclo completo de pedido sobre HTTP Streamable, verificando respuestas `isError=true` limpias ante errores de dominio.
* `SupermercadoToolsServiceTest` (5 pruebas), `DataSourceCredentialsValidatorTest` (5 pruebas) y `SupermercadoMcpServerApplicationTests` (1 prueba).

### 5.2. Prueba Integrada en Vivo (ChatBot Web + Servidor MCP + MariaDB)

1. **Arranque y descubrimiento:** `supermercado-mcp-server` validó el esquema contra MariaDB en el puerto `8089`; `supermercado-adk-chatbot` negoció el protocolo MCP, descubrió las **8 herramientas** y levantó la interfaz web en `[http://127.0.0.1:8080](http://127.0.0.1:8080)`.
2. **Flujo conversacional completo:** A través del chat en español con `gemini-3.5-flash-lite`, se consultó el catálogo, se creó un pedido a domicilio, se agregaron múltiples productos, se verificó el cálculo automático del total, se validó la solicitud de confirmación previa antes de ejecutar `confirmarPedido` y posteriormente se probó la cancelación.
3. **Verificación en MariaDB:** Mediante consultas SQL directas (`SELECT` sobre `pedidos`, `detalle_pedidos` y `productos`), se comprobó la persistencia de las filas, el incremento de `version`, el descuento exacto del `stock` al confirmar y su restauración íntegra al cancelar.
