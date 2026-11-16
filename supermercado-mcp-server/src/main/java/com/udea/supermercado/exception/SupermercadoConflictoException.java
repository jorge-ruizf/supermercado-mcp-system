package com.udea.supermercado.exception;

public final class SupermercadoConflictoException extends RuntimeException {

	public static final String MESSAGE =
			"El registro fue modificado por otro proceso. Vuelve a intentarlo.";

	public SupermercadoConflictoException(Throwable cause) {
		super(MESSAGE, cause);
	}

}
