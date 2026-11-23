package com.uem.ambulancias.emergencias.dto;

import com.uem.ambulancias.emergencias.domain.ModoDeDespacho;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * El modo de despacho y cuánto se espera la confirmación de la tripulación. La espera tiene piso y techo: menos de
 * diez segundos no le da tiempo a nadie de mirar el teléfono, y más de cinco minutos deja al incidente esperando.
 */
public record CambiarAjusteDeDespachoRequest(

		@NotNull(message = "El modo de despacho es obligatorio.")
		ModoDeDespacho modo,

		@NotNull(message = "Los segundos de espera son obligatorios.")
		@Min(value = 10, message = "La espera no puede ser menor a 10 segundos.")
		@Max(value = 300, message = "La espera no puede ser mayor a 300 segundos.")
		Integer segundosConfirmacion) {
}
