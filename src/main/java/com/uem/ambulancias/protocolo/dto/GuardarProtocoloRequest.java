package com.uem.ambulancias.protocolo.dto;

import java.util.List;

import com.uem.ambulancias.flota.domain.TipoUnidad;
import com.uem.ambulancias.protocolo.domain.Prioridad;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * El contenido completo del borrador. Se guarda entero cada vez: reemplaza lo que había, en el orden en que llega.
 */
public record GuardarProtocoloRequest(

		@Size(max = 1000, message = "La nota es demasiado larga.")
		String nota,

		@NotNull(message = "La lista de tipos de suceso es obligatoria.")
		@Valid
		List<TipoRequest> tipos) {

	/** Los códigos van en mayúsculas, sin espacios ni tildes: son identificadores, no textos para leer. */
	private static final String CODIGO = "[A-Z][A-Z0-9_]{1,59}";

	public record TipoRequest(

			@NotBlank(message = "El código del tipo de suceso es obligatorio.")
			@Pattern(regexp = CODIGO, message = "El código va en mayúsculas, con letras, números y guion bajo.")
			String codigo,

			@NotBlank(message = "El nombre del tipo de suceso es obligatorio.")
			@Size(max = 120, message = "El nombre es demasiado largo.")
			String nombre,

			@Valid
			List<CondicionRequest> condiciones) {
	}

	public record CondicionRequest(

			@NotBlank(message = "El código de la condición es obligatorio.")
			@Pattern(regexp = CODIGO, message = "El código va en mayúsculas, con letras, números y guion bajo.")
			String codigo,

			@NotBlank(message = "El enunciado de la condición es obligatorio.")
			@Size(max = 500, message = "El enunciado es demasiado largo.")
			String enunciado,

			@Size(max = 500, message = "La pregunta es demasiado larga.")
			String pregunta,

			@NotNull(message = "El umbral para actuar es obligatorio.")
			@DecimalMin(value = "0.01", message = "El umbral para actuar debe estar entre 0,01 y 1.")
			@DecimalMax(value = "1", message = "El umbral para actuar debe estar entre 0,01 y 1.")
			Double umbralActuar,

			@NotNull(message = "El umbral para preguntar es obligatorio.")
			@DecimalMin(value = "0.01", message = "El umbral para preguntar debe estar entre 0,01 y 1.")
			@DecimalMax(value = "1", message = "El umbral para preguntar debe estar entre 0,01 y 1.")
			Double umbralPreguntar,

			@PositiveOrZero(message = "Las unidades adicionales no pueden ser negativas.")
			Integer unidadesAdicionales,

			TipoUnidad tipoMinimo,

			Boolean derivaAPersona,

			@Size(max = 1000, message = "La cita es demasiado larga.")
			String cita,

			/** Opcional: la condición se cumple cuando los afectados declarados llegan a esta cantidad. */
			@Min(value = 1, message = "La cantidad de afectados debe ser al menos 1.")
			Integer afectadosDesde,

			/** Opcional: la prioridad que como mínimo toma el caso cuando la condición se cumple. */
			Prioridad prioridad) {
	}

}
