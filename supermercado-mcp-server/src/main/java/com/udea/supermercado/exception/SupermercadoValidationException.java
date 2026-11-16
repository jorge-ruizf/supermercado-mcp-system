package com.udea.supermercado.exception;

public final class SupermercadoValidationException extends RuntimeException {

	public SupermercadoValidationException(String message) {
		super(message);
	}

	public SupermercadoValidationException(String message, Throwable cause) {
		super(message, cause);
	}

}
