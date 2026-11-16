# Supermercado ADK Chatbot (`supermercado-adk-chatbot`)

Chatbot **Google ADK para Java** que consume, como cliente MCP independiente, las ocho herramientas
del servidor [`supermercado-mcp-server`](../supermercado-mcp-server). La conexión usa **Streamable
HTTP** contra `http://127.0.0.1:8089/mcp` y la interfaz web de ADK se publica en
`http://127.0.0.1:8080`.

> **Frontera estricta.** Este proyecto es solo un cliente MCP. No contiene entidades, repositorios,
> reglas de negocio, esquema ni herramientas del servidor. No se modifica `supermercado-mcp-server`.

---

## 1. Arquitectura

```text
ADK Web (127.0.0.1:8080)
        |
        | Gemini + McpToolset
        v
McpToolset / StreamableHttpServerParameters
        |
        | MCP Streamable HTTP
        v
http://127.0.0.1:8089/mcp  ->  Spring Boot + Spring AI  ->  MariaDB supermercado_mcp
```

- El agente descubre las herramientas al arrancar. Deben ser exactamente **8**.
- El servidor MCP sigue siendo el único dueño de precios, stock, totales y persistencia.
- No se usa `mcp-remote`: ADK habla Streamable HTTP de forma nativa.

---

## 2. Tecnologías

| Componente | Versión |
|---|---|
| Java | 21 |
| Spring Boot (soporte del servidor ADK) | 4.0.2 |
| Google ADK Java | 1.14.0 |
| `google-adk-dev` | 1.14.0 |
| Modelo por defecto | `gemini-2.5-flash` |
| Endpoint MCP | `http://127.0.0.1:8089/mcp` |

---

## 3. Requisitos previos

1. Java 21 y Maven (o el wrapper incluido).
2. MariaDB con la base `supermercado_mcp`, las tablas `productos`, `pedidos`, `detalle_pedidos` y el
   usuario `super_app` (solo DML).
3. `supermercado-mcp-server` compilado y ejecutándose:

   ```bash
   cd ../supermercado-mcp-server
   export DB_USERNAME="super_app"
   export DB_PASSWORD="…"          # solo en la sesión
   ./mvnw -B spring-boot:run
   ```

   Confirme el puerto:

   ```bash
   ss -ltn | grep ':8089 '
   ```

4. Una clave de Gemini válida.

---

## 4. Configuración de la clave (nunca en Git)

ADK Java **no carga `.env` automáticamente**. Copie el ejemplo, complete la clave y expórtela en la
misma terminal que inicia ADK:

```bash
cp .env.example .env
chmod 600 .env
# edite .env y reemplace replace-me
set -a; source .env; set +a
```

| Variable | Obligatoria | Uso |
|---|---|---|
| `GEMINI_API_KEY` | Sí (primaria) | Clave de la API de Gemini |
| `GOOGLE_API_KEY` | Alternativa | Se usa solo si `GEMINI_API_KEY` está vacía |
| `ADK_MODEL` | No | Modelo; por defecto `gemini-2.5-flash` |
| `MCP_SERVER_URL` | No | Por defecto `http://127.0.0.1:8089/mcp` |

`.env` está en `.gitignore`. Nunca confirme la clave ni la incluya en capturas, informes o logs.

---

## 5. Compilación

```bash
./mvnw -B clean verify
```

Resultado esperado: `BUILD SUCCESS`. La compilación no requiere la clave ni el servidor MCP.

---

## 6. Arranque del chatbot

Con el servidor MCP activo y la clave exportada:

```bash
./mvnw -B exec:java@runAdkWebServer
```

Resultado esperado en el log:

```text
Herramientas MCP descubiertas: 8
Tomcat started on port 8080
```

Abra `http://127.0.0.1:8080` y seleccione el agente **SupermercadoAgent**.

Comportamiento fail-fast:

- sin clave → error claro indicando `GEMINI_API_KEY` / `GOOGLE_API_KEY`;
- servidor MCP apagado → error de conexión con el endpoint;
- cantidad de herramientas distinta de 8 → error indicando el número descubierto.

---

## 7. Herramientas disponibles

| Herramienta | Uso |
|---|---|
| `listarProductos` | Catálogo con precio y stock |
| `buscarProductos` | Búsqueda por nombre o categoría |
| `crearPedido` | Crea un pedido `PENDIENTE` vacío |
| `agregarProductoAPedido` | Agrega unidades; el servidor calcula precios y total |
| `verPedido` | Detalle completo del pedido |
| `confirmarPedido` | Confirma y descuenta stock |
| `consultarEstadoPedido` | Estado, total y fecha |
| `cancelarPedido` | Cancela; devuelve stock si estaba confirmado |

---

## 8. Prompts de verificación (español)

Ejecute en este orden. Reemplace `<unique>` por un sufijo único, `<id>` por el identificador del
producto y `<pedidoId>` por el identificador devuelto por el agente.

| Paso | Prompt | Herramienta esperada |
|---|---|---|
| Catálogo | `Lista los productos disponibles con su precio y stock.` | `listarProductos` |
| Búsqueda | `Busca productos de la categoría <categoría>.` | `buscarProductos` |
| Creación | `Crea un pedido para el cliente SMOKE-<unique>, dirección Calle 10 # 20-30, teléfono 3001234567.` | `crearPedido` |
| Agregar | `Agrega 2 unidades del producto <id> al pedido <pedidoId>.` | `agregarProductoAPedido` |
| Revisión | `Verifica el pedido <pedidoId>.` | `verPedido` |
| Confirmación (guarda) | `Confirma el pedido <pedidoId>.` | **Ninguna**: el agente debe pedir confirmación |
| Confirmación (respuesta) | `Sí, confirma el pedido <pedidoId>.` | `confirmarPedido` |
| Estado | `Consulta el estado del pedido <pedidoId>.` | `consultarEstadoPedido` |
| Cancelación (guarda) | `Cancela el pedido <pedidoId>.` | **Ninguna**: el agente debe pedir confirmación e indicar si se devuelve stock |
| Cancelación (respuesta) | `Sí, cancela el pedido <pedidoId>.` | `cancelarPedido` |

La prueba de confirmación y cancelación es obligatoria: el primer prompt debe producir una pregunta
de confirmación, no una llamada inmediata a la herramienta.

Use únicamente pedidos sintéticos `SMOKE-*`; no modifique datos preexistentes.

---

## 9. Verificación en MariaDB

Ejecute las consultas de forma interactiva para no exponer la contraseña en `argv`.

```sql
SELECT id, cliente_nombre, estado, total, fecha_creacion
FROM pedidos
WHERE cliente_nombre LIKE 'SMOKE-%'
ORDER BY id DESC;

SELECT pedido_id, producto_id, cantidad, precio_unitario, subtotal
FROM detalle_pedidos
WHERE pedido_id = <pedidoId>;

SELECT id, nombre, stock
FROM productos
WHERE id = <id>;
```

Secuencia esperada:

1. tras crear: pedido `PENDIENTE` con total `0.00`;
2. tras agregar: línea con precio unitario tomado del servidor y total recalculado;
3. tras confirmar: estado `CONFIRMADO` y stock del producto reducido;
4. tras cancelar: estado `CANCELADO` y stock restaurado.

---

## 10. Limpieza de datos sintéticos

El usuario `super_app` solo tiene permisos DML. El operador puede eliminar los pedidos de prueba en
este orden:

```sql
DELETE FROM detalle_pedidos WHERE pedido_id IN (SELECT id FROM pedidos WHERE cliente_nombre LIKE 'SMOKE-%');
DELETE FROM pedidos WHERE cliente_nombre LIKE 'SMOKE-%';
```

Cancele los pedidos confirmados antes de borrarlos para que el inventario quede consistente.

---

## 11. Solución de problemas

| Síntoma | Causa probable | Acción |
|---|---|---|
| `Falta la clave de Gemini` | `.env` no exportado en esa terminal | `set -a; source .env; set +a` y reiniciar ADK |
| `No fue posible conectar con el servidor MCP` | Servidor apagado o puerto distinto | Iniciar `supermercado-mcp-server`; comprobar `:8089` |
| `Se esperaban 8 herramientas MCP` | Servidor incompleto o endpoint incorrecto | Verificar despliegue y `MCP_SERVER_URL` |
| `401` / error de autenticación | Clave inválida o sin acceso al modelo | Revisar la clave y `ADK_MODEL` |
| El agente no aparece en ADK Web | Falló la inicialización de `ROOT_AGENT` | Leer el log de arranque; corregir la causa |
| Error de stock al confirmar | Inventario insuficiente | Revisar `stock` en `productos`; el agente mostrará el mensaje seguro |
| Puerto 8080 ocupado | Otro proceso en uso | Liberar el puerto o ajustar `--server.port` |

Los logs de ADK pueden contener metadatos de sesión. No los publique.
