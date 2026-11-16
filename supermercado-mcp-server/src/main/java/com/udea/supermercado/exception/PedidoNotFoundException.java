package com.udea.supermercado.exception;

public final class PedidoNotFoundException extends RuntimeException {

	public PedidoNotFoundException(Long id) {
		super("No existe un pedido con id " + id + ".");
	}

}
