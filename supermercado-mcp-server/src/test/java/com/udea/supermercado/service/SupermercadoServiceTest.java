package com.udea.supermercado.service;

import java.math.BigDecimal;
import java.util.List;

import com.udea.supermercado.dto.AgregarProductoRequest;
import com.udea.supermercado.dto.CrearPedidoRequest;
import com.udea.supermercado.dto.PedidoDetalleResponse;
import com.udea.supermercado.dto.PedidoResponse;
import com.udea.supermercado.dto.ProductoResponse;
import com.udea.supermercado.exception.EstadoPedidoException;
import com.udea.supermercado.exception.PedidoNotFoundException;
import com.udea.supermercado.exception.ProductoNotFoundException;
import com.udea.supermercado.exception.StockInsuficienteException;
import com.udea.supermercado.exception.SupermercadoValidationException;
import com.udea.supermercado.model.EstadoPedido;
import com.udea.supermercado.model.Producto;
import com.udea.supermercado.repository.ProductoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
@Import(SupermercadoService.class)
class SupermercadoServiceTest {

	@Autowired
	private SupermercadoService service;

	@Autowired
	private ProductoRepository productoRepository;

	private Producto arroz;

	private Producto leche;

	@BeforeEach
	void seed() {
		this.arroz = producto("Arroz 1 kg", "Granos", "3500.00", 10);
		this.leche = producto("Leche entera 1 L", "Lácteos", "4200.50", 5);
	}

	@Test
	void listsProductsOrderedByName() {
		List<ProductoResponse> productos = this.service.listarProductos();

		assertThat(productos).extracting(ProductoResponse::nombre)
				.containsExactly("Arroz 1 kg", "Leche entera 1 L");
		assertThat(productos.getFirst().precio()).isEqualByComparingTo("3500.00");
	}

	@Test
	void searchesByNameOrCategoryIgnoringCase() {
		assertThat(this.service.buscarProductos("arroz")).extracting(ProductoResponse::id)
				.containsExactly(this.arroz.getId());
		assertThat(this.service.buscarProductos("LÁCTEOS")).extracting(ProductoResponse::id)
				.containsExactly(this.leche.getId());
		assertThatThrownBy(() -> this.service.buscarProductos("  "))
				.isInstanceOf(SupermercadoValidationException.class);
	}

	@Test
	void createsAnEmptyPendingOrderWithZeroTotal() {
		PedidoResponse pedido = this.service.crearPedido(
				new CrearPedidoRequest("  Ana Pérez ", "Calle 10 # 20-30", "3001234567"));

		assertThat(pedido.id()).isPositive();
		assertThat(pedido.clienteNombre()).isEqualTo("Ana Pérez");
		assertThat(pedido.estado()).isEqualTo("PENDIENTE");
		assertThat(pedido.total()).isEqualByComparingTo("0.00");
		assertThat(pedido.fechaCreacion()).isNotNull();
	}

	@Test
	void rejectsIncompleteCustomerData() {
		assertThatThrownBy(() -> this.service.crearPedido(
				new CrearPedidoRequest(" ", "Calle 10", "3001234567")))
				.isInstanceOf(SupermercadoValidationException.class)
				.hasMessageContaining("nombre del cliente");
		assertThatThrownBy(() -> this.service.crearPedido(
				new CrearPedidoRequest("Ana", "", "3001234567")))
				.isInstanceOf(SupermercadoValidationException.class)
				.hasMessageContaining("dirección");
		assertThatThrownBy(() -> this.service.crearPedido(
				new CrearPedidoRequest("Ana", "Calle 10", null)))
				.isInstanceOf(SupermercadoValidationException.class)
				.hasMessageContaining("teléfono");
	}

	@Test
	void usesServerPriceAndAggregatesDuplicateLines() {
		PedidoResponse pedido = crearPedido();

		this.service.agregarProductoAPedido(new AgregarProductoRequest(pedido.id(), this.arroz.getId(), 2));
		PedidoResponse actualizado =
				this.service.agregarProductoAPedido(new AgregarProductoRequest(pedido.id(), this.arroz.getId(), 1));

		assertThat(actualizado.total()).isEqualByComparingTo("10500.00");

		PedidoDetalleResponse detalle = this.service.verPedido(pedido.id());
		assertThat(detalle.productos()).hasSize(1);
		assertThat(detalle.productos().getFirst().cantidad()).isEqualTo(3);
		assertThat(detalle.productos().getFirst().precioUnitario()).isEqualByComparingTo("3500.00");
		assertThat(detalle.productos().getFirst().subtotal()).isEqualByComparingTo("10500.00");
	}

	@Test
	void rejectsQuantitiesAboveAvailableStockWhenAdding() {
		PedidoResponse pedido = crearPedido();

		assertThatThrownBy(() -> this.service.agregarProductoAPedido(
				new AgregarProductoRequest(pedido.id(), this.leche.getId(), 6)))
				.isInstanceOf(StockInsuficienteException.class)
				.hasMessageContaining("Leche entera 1 L")
				.hasMessageContaining("Disponible: 5");
	}

	@Test
	void rejectsInvalidQuantityAndUnknownEntities() {
		PedidoResponse pedido = crearPedido();

		assertThatThrownBy(() -> this.service.agregarProductoAPedido(
				new AgregarProductoRequest(pedido.id(), this.arroz.getId(), 0)))
				.isInstanceOf(SupermercadoValidationException.class);
		assertThatThrownBy(() -> this.service.agregarProductoAPedido(
				new AgregarProductoRequest(pedido.id(), 999999L, 1)))
				.isInstanceOf(ProductoNotFoundException.class)
				.hasMessage("No existe un producto con id 999999.");
		assertThatThrownBy(() -> this.service.verPedido(999999L))
				.isInstanceOf(PedidoNotFoundException.class)
				.hasMessage("No existe un pedido con id 999999.");
	}

	@Test
	void confirmsOrderDeductingStockOnce() {
		PedidoResponse pedido = crearPedido();
		this.service.agregarProductoAPedido(new AgregarProductoRequest(pedido.id(), this.arroz.getId(), 4));

		PedidoResponse confirmado = this.service.confirmarPedido(pedido.id());

		assertThat(confirmado.estado()).isEqualTo("CONFIRMADO");
		assertThat(stockOf(this.arroz)).isEqualTo(6);

		PedidoResponse repetido = this.service.confirmarPedido(pedido.id());
		assertThat(repetido.estado()).isEqualTo("CONFIRMADO");
		assertThat(stockOf(this.arroz)).isEqualTo(6);
	}

	@Test
	void rejectsConfirmationWithoutProductsOrAfterCancellation() {
		PedidoResponse vacio = crearPedido();
		assertThatThrownBy(() -> this.service.confirmarPedido(vacio.id()))
				.isInstanceOf(SupermercadoValidationException.class)
				.hasMessageContaining("no tiene productos");

		PedidoResponse cancelado = crearPedido();
		this.service.agregarProductoAPedido(
				new AgregarProductoRequest(cancelado.id(), this.arroz.getId(), 1));
		this.service.cancelarPedido(cancelado.id());

		assertThatThrownBy(() -> this.service.confirmarPedido(cancelado.id()))
				.isInstanceOf(EstadoPedidoException.class)
				.hasMessageContaining("cancelado");
	}

	@Test
	void cancellingConfirmedOrderRestoresStock() {
		PedidoResponse pedido = crearPedido();
		this.service.agregarProductoAPedido(new AgregarProductoRequest(pedido.id(), this.leche.getId(), 3));
		this.service.confirmarPedido(pedido.id());
		assertThat(stockOf(this.leche)).isEqualTo(2);

		PedidoResponse cancelado = this.service.cancelarPedido(pedido.id());

		assertThat(cancelado.estado()).isEqualTo("CANCELADO");
		assertThat(stockOf(this.leche)).isEqualTo(5);

		PedidoResponse repetido = this.service.cancelarPedido(pedido.id());
		assertThat(repetido.estado()).isEqualTo("CANCELADO");
		assertThat(stockOf(this.leche)).isEqualTo(5);
	}

	@Test
	void cancellingPendingOrderDoesNotChangeStock() {
		PedidoResponse pedido = crearPedido();
		this.service.agregarProductoAPedido(new AgregarProductoRequest(pedido.id(), this.arroz.getId(), 2));

		PedidoResponse cancelado = this.service.cancelarPedido(pedido.id());

		assertThat(cancelado.estado()).isEqualTo("CANCELADO");
		assertThat(stockOf(this.arroz)).isEqualTo(10);
	}

	@Test
	void cannotAddProductsToNonPendingOrder() {
		PedidoResponse pedido = crearPedido();
		this.service.agregarProductoAPedido(new AgregarProductoRequest(pedido.id(), this.arroz.getId(), 1));
		this.service.confirmarPedido(pedido.id());

		assertThatThrownBy(() -> this.service.agregarProductoAPedido(
				new AgregarProductoRequest(pedido.id(), this.leche.getId(), 1)))
				.isInstanceOf(EstadoPedidoException.class)
				.hasMessage("Solo se pueden agregar productos a pedidos pendientes.");
	}

	private PedidoResponse crearPedido() {
		return this.service.crearPedido(
				new CrearPedidoRequest("Cliente prueba", "Calle 1 # 2-3", "3000000000"));
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
