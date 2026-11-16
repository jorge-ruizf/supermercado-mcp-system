package com.udea.supermercado.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonFormat;

public record EstadoPedidoResponse(
		Long id,
		String estado,
		BigDecimal total,
		@JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
		LocalDateTime fechaCreacion) {
}
