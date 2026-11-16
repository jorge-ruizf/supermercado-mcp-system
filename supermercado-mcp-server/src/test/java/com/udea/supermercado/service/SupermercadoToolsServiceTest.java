package com.udea.supermercado.service;

import java.util.List;

import com.udea.supermercado.dto.CrearPedidoRequest;
import com.udea.supermercado.dto.ProductoResponse;
import com.udea.supermercado.exception.PedidoNotFoundException;
import com.udea.supermercado.exception.SupermercadoOperacionException;
import com.udea.supermercado.exception.SupermercadoValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupermercadoToolsServiceTest {

	@Mock
	private SupermercadoService supermercadoService;

	@InjectMocks
	private SupermercadoToolsService toolsService;

	@Test
	void translatesUnexpectedFailuresIntoASafeMessage() {
		when(this.supermercadoService.listarProductos())
				.thenThrow(new IllegalStateException("jdbc:secret: password and SQL"));

		assertThatThrownBy(this.toolsService::listarProductos)
				.isInstanceOf(SupermercadoOperacionException.class)
				.hasMessage(SupermercadoOperacionException.MESSAGE)
				.hasMessageNotContaining("jdbc")
				.hasCauseInstanceOf(IllegalStateException.class);
	}

	@Test
	void preservesExpectedDomainFailures() {
		when(this.supermercadoService.verPedido(5L)).thenThrow(new PedidoNotFoundException(5L));

		assertThatThrownBy(() -> this.toolsService.verPedido(5L))
				.isInstanceOf(PedidoNotFoundException.class)
				.hasMessage("No existe un pedido con id 5.");
	}

	@Test
	void preservesValidationFailures() {
		when(this.supermercadoService.buscarProductos("x"))
				.thenThrow(new SupermercadoValidationException("Texto inválido."));

		assertThatThrownBy(() -> this.toolsService.buscarProductos("x"))
				.isInstanceOf(SupermercadoValidationException.class)
				.hasMessage("Texto inválido.");
	}

	@Test
	void delegatesOrderCreationWithTheClientData() {
		when(this.supermercadoService.crearPedido(any(CrearPedidoRequest.class)))
				.thenReturn(null);

		this.toolsService.crearPedido("Ana", "Calle 1", "3001234567");

		org.mockito.Mockito.verify(this.supermercadoService)
				.crearPedido(new CrearPedidoRequest("Ana", "Calle 1", "3001234567"));
	}

	@Test
	void listsProductsThroughTheService() {
		when(this.supermercadoService.listarProductos())
				.thenReturn(List.of(new ProductoResponse(1L, "Arroz", "Granos", null, 3)));

		List<ProductoResponse> productos = this.toolsService.listarProductos();

		org.assertj.core.api.Assertions.assertThat(productos).hasSize(1);
	}

}
