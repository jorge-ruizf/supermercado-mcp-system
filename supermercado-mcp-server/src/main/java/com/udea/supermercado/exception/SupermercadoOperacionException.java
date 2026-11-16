package com.udea.supermercado.exception;

public final class SupermercadoOperacionException extends RuntimeException {

	public static final String MESSAGE =
			"No se pudo completar la operación solicitada. Inténtalo de nuevo más tarde.";

	public SupermercadoOperacionException(Throwable cause) {
		super(MESSAGE, cause);
	}

}
