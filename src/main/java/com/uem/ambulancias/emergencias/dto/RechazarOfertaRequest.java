package com.uem.ambulancias.emergencias.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Por qué la tripulación no puede ir. Es obligatorio: la central tiene que poder leerlo después. */
public record RechazarOfertaRequest(

		@NotBlank(message = "El motivo es obligatorio.")
		@Size(max = 300, message = "El motivo es demasiado largo.")
		String motivo) {
}
