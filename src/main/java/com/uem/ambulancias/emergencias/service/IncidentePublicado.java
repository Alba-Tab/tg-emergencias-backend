package com.uem.ambulancias.emergencias.service;

import java.time.Instant;
import java.util.List;

import com.uem.ambulancias.emergencias.domain.EstadoIncidente;
import com.uem.ambulancias.emergencias.domain.Incidente;
import com.uem.ambulancias.flota.domain.TipoUnidad;

/**
 * Lo que ven las unidades de un incidente abierto antes de tomarlo: ubicación, tiempo desde la creación,
 * afectados, descripciones de sus alertas, cuántas unidades ya acuden y cuántas necesita, con el tipo mínimo si el
 * protocolo pidió uno.
 */
public record IncidentePublicado(
		Long id,
		double latitud,
		double longitud,
		EstadoIncidente estado,
		Instant fechaHoraCreacion,
		Integer cantidadAfectados,
		List<String> descripciones,
		long unidadesAcudiendo,
		int unidadesRequeridas,
		TipoUnidad tipoMinimoRequerido) {

	public static IncidentePublicado de(Incidente incidente, List<String> descripciones, long unidadesAcudiendo) {
		return new IncidentePublicado(
				incidente.getId(),
				incidente.getUbicacion().getY(),
				incidente.getUbicacion().getX(),
				incidente.getEstado(),
				incidente.getFechaHoraCreacion(),
				incidente.getCantidadAfectados(),
				descripciones,
				unidadesAcudiendo,
				incidente.getUnidadesRequeridas(),
				incidente.getTipoMinimoRequerido());
	}

}
