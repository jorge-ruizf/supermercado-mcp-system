package com.udea.supermercado.dto;

public record AgregarProductoRequest(
		Long pedidoId,
		Long productoId,
		Integer cantidad) {
}
