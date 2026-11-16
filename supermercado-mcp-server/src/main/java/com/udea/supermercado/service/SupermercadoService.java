package com.udea.supermercado.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.udea.supermercado.dto.AgregarProductoRequest;
import com.udea.supermercado.dto.CrearPedidoRequest;
import com.udea.supermercado.dto.DetallePedidoResponse;
import com.udea.supermercado.dto.EstadoPedidoResponse;
import com.udea.supermercado.dto.PedidoDetalleResponse;
import com.udea.supermercado.dto.PedidoResponse;
import com.udea.supermercado.dto.ProductoResponse;
import com.udea.supermercado.exception.EstadoPedidoException;
import com.udea.supermercado.exception.PedidoNotFoundException;
import com.udea.supermercado.exception.ProductoNotFoundException;
import com.udea.supermercado.exception.StockInsuficienteException;
import com.udea.supermercado.exception.SupermercadoConflictoException;
import com.udea.supermercado.exception.SupermercadoValidationException;
import com.udea.supermercado.model.DetallePedido;
import com.udea.supermercado.model.EstadoPedido;
import com.udea.supermercado.model.Pedido;
import com.udea.supermercado.model.Producto;
import com.udea.supermercado.repository.PedidoRepository;
import com.udea.supermercado.repository.ProductoRepository;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SupermercadoService {

	private static final int MAX_CANTIDAD = 999;
	private static final int MAX_NOMBRE = 120;
	private static final int MAX_DIRECCION = 255;
	private static final int MAX_TELEFONO = 30;

	private final ProductoRepository productoRepository;

	private final PedidoRepository pedidoRepository;

	public SupermercadoService(ProductoRepository productoRepository, PedidoRepository pedidoRepository) {
		this.productoRepository = productoRepository;
		this.pedidoRepository = pedidoRepository;
	}

	@Transactional(readOnly = true)
	public List<ProductoResponse> listarProductos() {
		return this.productoRepository.findAllByOrderByNombreAsc().stream().map(this::toProducto).toList();
	}

	@Transactional(readOnly = true)
	public List<ProductoResponse> buscarProductos(String texto) {
		if (texto == null || texto.isBlank()) {
			throw new SupermercadoValidationException("El texto de búsqueda es obligatorio.");
		}
		return this.productoRepository.buscar(texto.trim()).stream().map(this::toProducto).toList();
	}

	@Transactional
	public PedidoResponse crearPedido(CrearPedidoRequest request) {
		String cliente = requerirTexto(request.clienteNombre(), "El nombre del cliente es obligatorio.", MAX_NOMBRE);
		String direccion = requerirTexto(request.direccionEntrega(),
				"La dirección de entrega es obligatoria.", MAX_DIRECCION);
		String telefono = requerirTexto(request.telefono(), "El teléfono es obligatorio.", MAX_TELEFONO);

		Pedido pedido = this.pedidoRepository.save(new Pedido(cliente, direccion, telefono));
		return toPedido(pedido);
	}

	@Transactional
	public PedidoResponse agregarProductoAPedido(AgregarProductoRequest request) {
		Long pedidoId = requerirId(request.pedidoId(), "pedido");
		Long productoId = requerirId(request.productoId(), "producto");
		int cantidad = requerirCantidad(request.cantidad());

		Pedido pedido = cargarPedidoBloqueado(pedidoId);
		if (pedido.getEstado() != EstadoPedido.PENDIENTE) {
			throw new EstadoPedidoException("Solo se pueden agregar productos a pedidos pendientes.");
		}

		Producto producto = this.productoRepository.findByIdForUpdate(productoId)
				.orElseThrow(() -> new ProductoNotFoundException(productoId));

		DetallePedido detalle = pedido.getDetalles().stream()
				.filter(linea -> linea.getProducto().getId().equals(productoId))
				.findFirst()
				.orElse(null);

		int nuevaCantidad = (detalle == null ? 0 : detalle.getCantidad()) + cantidad;
		if (nuevaCantidad > producto.getStock()) {
			throw new StockInsuficienteException(producto.getNombre(), producto.getStock());
		}

		if (detalle == null) {
			detalle = new DetallePedido();
			detalle.setProducto(producto);
			pedido.agregarDetalle(detalle);
		}
		detalle.actualizar(nuevaCantidad, producto.getPrecio());
		pedido.recalcularTotal();

		return toPedido(guardar(pedido));
	}

	@Transactional(readOnly = true)
	public PedidoDetalleResponse verPedido(Long pedidoId) {
		Pedido pedido = this.pedidoRepository.findByIdWithDetalles(requerirId(pedidoId, "pedido"))
				.orElseThrow(() -> new PedidoNotFoundException(pedidoId));

		List<DetallePedidoResponse> productos = pedido.getDetalles().stream()
				.map(linea -> new DetallePedidoResponse(
						linea.getProducto().getId(),
						linea.getProducto().getNombre(),
						linea.getCantidad(),
						linea.getPrecioUnitario(),
						linea.getSubtotal()))
				.toList();

		return new PedidoDetalleResponse(
				pedido.getId(),
				pedido.getClienteNombre(),
				pedido.getDireccionEntrega(),
				pedido.getTelefono(),
				pedido.getEstado().name(),
				pedido.getTotal(),
				pedido.getFechaCreacion(),
				productos);
	}

	@Transactional
	public PedidoResponse confirmarPedido(Long pedidoId) {
		Pedido pedido = cargarPedidoBloqueado(requerirId(pedidoId, "pedido"));

		if (pedido.getEstado() == EstadoPedido.CONFIRMADO) {
			return toPedido(pedido);
		}
		if (pedido.getEstado() == EstadoPedido.CANCELADO) {
			throw new EstadoPedidoException("El pedido fue cancelado y no se puede confirmar.");
		}
		if (pedido.getDetalles().isEmpty()) {
			throw new SupermercadoValidationException(
					"El pedido no tiene productos; agrega al menos uno antes de confirmar.");
		}

		for (Map.Entry<Long, Integer> linea : cantidadesPorProducto(pedido).entrySet()) {
			Producto producto = this.productoRepository.findByIdForUpdate(linea.getKey())
					.orElseThrow(() -> new ProductoNotFoundException(linea.getKey()));
			if (producto.getStock() < linea.getValue()) {
				throw new StockInsuficienteException(producto.getNombre(), producto.getStock());
			}
			producto.setStock(producto.getStock() - linea.getValue());
		}

		pedido.setEstado(EstadoPedido.CONFIRMADO);
		return toPedido(guardar(pedido));
	}

	@Transactional(readOnly = true)
	public EstadoPedidoResponse consultarEstadoPedido(Long pedidoId) {
		Pedido pedido = this.pedidoRepository.findById(requerirId(pedidoId, "pedido"))
				.orElseThrow(() -> new PedidoNotFoundException(pedidoId));

		return new EstadoPedidoResponse(
				pedido.getId(),
				pedido.getEstado().name(),
				pedido.getTotal(),
				pedido.getFechaCreacion());
	}

	@Transactional
	public PedidoResponse cancelarPedido(Long pedidoId) {
		Pedido pedido = cargarPedidoBloqueado(requerirId(pedidoId, "pedido"));

		if (pedido.getEstado() == EstadoPedido.CANCELADO) {
			return toPedido(pedido);
		}

		if (pedido.getEstado() == EstadoPedido.CONFIRMADO) {
			for (Map.Entry<Long, Integer> linea : cantidadesPorProducto(pedido).entrySet()) {
				Producto producto = this.productoRepository.findByIdForUpdate(linea.getKey())
						.orElseThrow(() -> new ProductoNotFoundException(linea.getKey()));
				producto.setStock(producto.getStock() + linea.getValue());
			}
		}

		pedido.setEstado(EstadoPedido.CANCELADO);
		return toPedido(guardar(pedido));
	}

	private Pedido cargarPedidoBloqueado(Long pedidoId) {
		return this.pedidoRepository.findByIdForUpdate(pedidoId)
				.orElseThrow(() -> new PedidoNotFoundException(pedidoId));
	}

	private Pedido guardar(Pedido pedido) {
		try {
			return this.pedidoRepository.saveAndFlush(pedido);
		}
		catch (ObjectOptimisticLockingFailureException ex) {
			throw new SupermercadoConflictoException(ex);
		}
	}

	private Map<Long, Integer> cantidadesPorProducto(Pedido pedido) {
		Map<Long, Integer> cantidades = new TreeMap<>();
		for (DetallePedido detalle : pedido.getDetalles()) {
			cantidades.merge(detalle.getProducto().getId(), detalle.getCantidad(), Integer::sum);
		}
		return cantidades;
	}

	private ProductoResponse toProducto(Producto producto) {
		return new ProductoResponse(
				producto.getId(),
				producto.getNombre(),
				producto.getCategoria(),
				producto.getPrecio(),
				producto.getStock());
	}

	private PedidoResponse toPedido(Pedido pedido) {
		return new PedidoResponse(
				pedido.getId(),
				pedido.getClienteNombre(),
				pedido.getDireccionEntrega(),
				pedido.getTelefono(),
				pedido.getEstado().name(),
				pedido.getTotal(),
				pedido.getFechaCreacion());
	}

	private Long requerirId(Long id, String campo) {
		if (id == null || id <= 0) {
			throw new SupermercadoValidationException(
					"El identificador de " + campo + " debe ser un número positivo.");
		}
		return id;
	}

	private int requerirCantidad(Integer cantidad) {
		if (cantidad == null || cantidad < 1 || cantidad > MAX_CANTIDAD) {
			throw new SupermercadoValidationException(
					"La cantidad debe ser un número entre 1 y " + MAX_CANTIDAD + ".");
		}
		return cantidad;
	}

	private String requerirTexto(String valor, String mensaje, int maximo) {
		if (valor == null || valor.isBlank()) {
			throw new SupermercadoValidationException(mensaje);
		}
		String limpio = valor.trim();
		if (limpio.length() > maximo) {
			throw new SupermercadoValidationException("El valor no puede superar " + maximo + " caracteres.");
		}
		return limpio;
	}

}
