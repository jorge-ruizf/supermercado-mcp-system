package com.udea.supermercado.exception;

public final class StockInsuficienteException extends RuntimeException {

	public StockInsuficienteException(String producto, int disponible) {
		super("No hay stock suficiente para el producto '" + producto + "'. Disponible: " + disponible + ".");
	}

}
