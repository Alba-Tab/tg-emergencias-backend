package com.uem.ambulancias.coordinacion.dto;

import com.uem.ambulancias.coordinacion.domain.OpcionDeDerivacion;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Lo que la persona de la central contesta a una derivación, con una nota opcional. */
public record DecidirDerivacionRequest(

		@NotNull(message = "La opción es obligatoria.")
		OpcionDeDerivacion opcion,

		@Size(max = 1000, message = "La nota es demasiado larga.")
		String nota) {
}
