package com.uem.ambulancias.coordinacion.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.uem.ambulancias.coordinacion.domain.EstadoEvaluacion;
import com.uem.ambulancias.coordinacion.domain.EvaluacionDelCaso;
import com.uem.ambulancias.coordinacion.domain.MetodoDeEvaluacion;

/**
 * Una evaluación de las condiciones de un incidente, para que la central vea qué se evaluó y con qué. El estado del
 * caso y los veredictos ya son JSON: se escriben tal cual.
 */
public record EvaluacionResponse(
		Long id,
		EstadoEvaluacion estado,
		String pedidaPor,
		MetodoDeEvaluacion metodo,
		Instant creadaEn,
		Instant terminadaEn,
		int intentos,
		String error,
		String proveedor,
		String modelo,
		Integer tokensDeEntrada,
		Double costo,
		Long milisegundosDelModelo,
		Long hastaEvento,
		@JsonRawValue String estadoDelCaso,
		@JsonRawValue String veredictos) {

	public static EvaluacionResponse de(EvaluacionDelCaso evaluacion) {
		return new EvaluacionResponse(evaluacion.getId(), evaluacion.getEstado(), evaluacion.getMotivo(),
				evaluacion.getMetodo(), evaluacion.getCreadaEn(), evaluacion.getTerminadaEn(),
				evaluacion.getIntentos(), evaluacion.getUltimoError(), evaluacion.getProveedor(),
				evaluacion.getModelo(), evaluacion.getTokensDeEntrada(), evaluacion.getCosto(),
				evaluacion.getMilisegundosDelModelo(), evaluacion.getHastaEvento(), evaluacion.getEstadoDelCaso(),
				evaluacion.getVeredictos());
	}

}
