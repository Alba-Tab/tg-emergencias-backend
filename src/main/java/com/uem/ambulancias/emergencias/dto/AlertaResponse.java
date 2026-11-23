package com.uem.ambulancias.emergencias.dto;

import java.time.Instant;

import com.uem.ambulancias.emergencias.domain.Alerta;
import com.uem.ambulancias.emergencias.domain.Incidente;

/**
 * Alerta creada. {@code incidenteId} es el incidente al que quedó vinculada: la app lo usa para el seguimiento.
 * {@code tipoSuceso} es el tipo que quedó anotado en la alerta: con ese código la app pide las preguntas del
 * formulario. Viene nulo si el ciudadano no marcó ninguno o si el protocolo vigente no lo conoce.
 */
public record AlertaResponse(Long alertaId, Long incidenteId, Instant fechaHora, String tipoSuceso) {

	public static AlertaResponse de(Alerta alerta, Incidente incidente) {
		return new AlertaResponse(alerta.getId(), incidente.getId(), alerta.getFechaHora(), alerta.getTipoSuceso());
	}

}
