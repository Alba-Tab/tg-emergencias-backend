package com.uem.ambulancias.emergencias.dto;

import java.time.Instant;

import com.uem.ambulancias.emergencias.domain.Incidente;
import com.uem.ambulancias.emergencias.domain.OfertaDeIncidente;

/**
 * La asignación que la unidad tiene esperando respuesta: a qué incidente la mandan, dónde es y hasta cuándo puede
 * confirmar. {@code minutosDeLlegada} es lo que el sistema estimó al asignarla.
 */
public record OfertaResponse(Long id, Long incidenteId, double latitud, double longitud, Integer cantidadAfectados,
		String tipoSuceso, Instant ofrecidaEn, Instant venceEn, Integer minutosDeLlegada) {

	public static OfertaResponse de(OfertaDeIncidente oferta) {
		Incidente incidente = oferta.getIncidente();
		return new OfertaResponse(oferta.getId(), incidente.getId(), incidente.getUbicacion().getY(),
				incidente.getUbicacion().getX(), incidente.getCantidadAfectados(), incidente.getTipoSuceso(),
				oferta.getOfrecidaEn(), oferta.getVenceEn(), oferta.getMinutosDeLlegada());
	}

}
