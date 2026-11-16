package com.udea.supermercado.service;

import java.util.List;
import java.util.function.Supplier;

import com.udea.supermercado.dto.AgregarProductoRequest;
import com.udea.supermercado.dto.CrearPedidoRequest;
import com.udea.supermercado.dto.EstadoPedidoResponse;
import com.udea.supermercado.dto.PedidoDetalleResponse;
import com.udea.supermercado.dto.PedidoResponse;
import com.udea.supermercado.dto.ProductoResponse;
import com.udea.supermercado.exception.EstadoPedidoException;
import com.udea.supermercado.exception.PedidoNotFoundException;
import com.udea.supermercado.exception.ProductoNotFoundException;
import com.udea.supermercado.exception.StockInsuficienteException;
import com.udea.supermercado.exception.SupermercadoConflictoException;
import com.udea.supermercado.exception.SupermercadoOperacionException;
import com.udea.supermercado.exception.SupermercadoValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

@Service
public class SupermercadoToolsService {

	private static final Logger logger = LoggerFactory.getLogger(SupermercadoToolsService.class);

	private final SupermercadoService supermercadoService;

	public SupermercadoToolsService(SupermercadoService supermercadoService) {
		this.supermercadoService = supermercadoService;
	}

	@Tool(name = "listarProductos",
			description = "Lista todos los productos disponibles con su precio y stock, ordenados por nombre.")
	public List<ProductoResponse> listarProductos() {
		return ejecutar("listarProductos", this.supermercadoService::listarProductos);
	}

	@Tool(name = "buscarProductos",
			description = "Busca productos por coincidencia de texto en el nombre o la categoría.")
	public List<ProductoResponse> buscarProductos(
			@ToolParam(description = "Texto a buscar en el nombre o la categoría del producto.") String texto) {
		return ejecutar("buscarProductos", () -> this.supermercadoService.buscarProductos(texto));
	}

	@Tool(name = "crearPedido",
			description = "Crea un pedido vacío en estado PENDIENTE para un cliente. Los productos se agregan "
					+ "después con agregarProductoAPedido.")
	public PedidoResponse crearPedido(
			@ToolParam(description = "Nombre completo del cliente.") String clienteNombre,
			@ToolParam(description = "Dirección de entrega del pedido.") String direccionEntrega,
			@ToolParam(description = "Teléfono de contacto del cliente.") String telefono) {
		return ejecutar("crearPedido",
				() -> this.supermercadoService.crearPedido(
						new CrearPedidoRequest(clienteNombre, direccionEntrega, telefono)));
	}

	@Tool(name = "agregarProductoAPedido",
			description = "Agrega unidades de un producto a un pedido pendiente. El servidor consulta el precio "
					+ "y calcula el subtotal y el total; el cliente nunca envía precios.")
	public PedidoResponse agregarProductoAPedido(
			@ToolParam(description = "Identificador del pedido pendiente.") Long pedidoId,
			@ToolParam(description = "Identificador del producto que se agregará.") Long productoId,
			@ToolParam(description = "Cantidad de unidades, entre 1 y 999.") Integer cantidad) {
		return ejecutar("agregarProductoAPedido",
				() -> this.supermercadoService.agregarProductoAPedido(
						new AgregarProductoRequest(pedidoId, productoId, cantidad)));
	}

	@Tool(name = "verPedido",
			description = "Muestra el detalle completo de un pedido: cliente, estado, total y líneas de producto.")
	public PedidoDetalleResponse verPedido(
			@ToolParam(description = "Identificador del pedido.") Long pedidoId) {
		return ejecutar("verPedido", () -> this.supermercadoService.verPedido(pedidoId));
	}

	@Tool(name = "confirmarPedido",
			description = "Confirma un pedido pendiente y descuenta el stock de forma atómica. Requiere "
					+ "confirmación previa del usuario. Si ya está confirmado, no vuelve a descontar stock.")
	public PedidoResponse confirmarPedido(
			@ToolParam(description = "Identificador del pedido pendiente que se confirmará.") Long pedidoId) {
		return ejecutar("confirmarPedido", () -> this.supermercadoService.confirmarPedido(pedidoId));
	}

	@Tool(name = "consultarEstadoPedido",
			description = "Consulta el estado actual, el total y la fecha de creación de un pedido.")
	public EstadoPedidoResponse consultarEstadoPedido(
			@ToolParam(description = "Identificador del pedido.") Long pedidoId) {
		return ejecutar("consultarEstadoPedido",
				() -> this.supermercadoService.consultarEstadoPedido(pedidoId));
	}

	@Tool(name = "cancelarPedido",
			description = "Cancela un pedido. Si estaba confirmado, devuelve las unidades al inventario. "
					+ "Requiere confirmación previa del usuario. Si ya está cancelado, no cambia nada.")
	public PedidoResponse cancelarPedido(
			@ToolParam(description = "Identificador del pedido que se cancelará.") Long pedidoId) {
		return ejecutar("cancelarPedido", () -> this.supermercadoService.cancelarPedido(pedidoId));
	}

	private <T> T ejecutar(String herramienta, Supplier<T> accion) {
		try {
			return accion.get();
		}
		catch (SupermercadoValidationException | ProductoNotFoundException | PedidoNotFoundException
				| StockInsuficienteException | EstadoPedidoException | SupermercadoConflictoException ex) {
			throw ex;
		}
		catch (RuntimeException ex) {
			logger.error("Error inesperado en la herramienta '{}'", herramienta, ex);
			throw new SupermercadoOperacionException(ex);
		}
	}

}
