package com.udea.supermercado.dto;

public record CrearPedidoRequest(
		String clienteNombre,
		String direccionEntrega,
		String telefono) {
}
