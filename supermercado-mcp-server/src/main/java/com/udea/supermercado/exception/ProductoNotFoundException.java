package com.udea.supermercado.exception;

public final class ProductoNotFoundException extends RuntimeException {

	public ProductoNotFoundException(Long id) {
		super("No existe un producto con id " + id + ".");
	}

}
