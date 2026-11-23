package com.uem.ambulancias.emergencias.dto;

import java.util.List;

import com.uem.ambulancias.emergencias.domain.RespuestaDelTestigo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Las respuestas del ciudadano al formulario de su tipo de suceso. Puede mandar una sola o varias, y volver a mandar
 * una condición ya contestada para corregirla: vale la última.
 */
public record ResponderFormularioRequest(

		@NotEmpty(message = "Hay que mandar al menos una respuesta.")
		@Size(max = 50, message = "Son demasiadas respuestas.")
		@Valid
		List<RespuestaRequest> respuestas) {

	public record RespuestaRequest(

			@NotBlank(message = "La condición es obligatoria.")
			String condicion,

			@NotNull(message = "La respuesta es obligatoria.")
			RespuestaDelTestigo valor) {
	}

}
