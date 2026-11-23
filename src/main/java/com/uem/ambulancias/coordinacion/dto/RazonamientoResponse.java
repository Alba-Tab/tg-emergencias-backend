package com.uem.ambulancias.coordinacion.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.uem.ambulancias.coordinacion.domain.EstadoEvaluacion;
import com.uem.ambulancias.coordinacion.domain.RazonamientoDelCaso;

/**
 * Un razonamiento del agente sobre un incidente, para que la central vea qué se le pidió, qué miró y a qué llegó.
 * Las condiciones, las conclusiones y las herramientas ya son JSON: se escriben tal cual.
 */
public record RazonamientoResponse(
		Long id,
		EstadoEvaluacion estado,
		Instant creadoEn,
		Instant terminadoEn,
		int intentos,
		String error,
		String proveedor,
		String modelo,
		String versionDelPrompt,
		Integer tokensDeEntrada,
		Integer tokensDeSalida,
		Integer pedidosAlModelo,
		Long milisegundos,
		@JsonRawValue String condiciones,
		@JsonRawValue String conclusiones,
		@JsonRawValue String herramientas) {

	public static RazonamientoResponse de(RazonamientoDelCaso razonamiento) {
		return new RazonamientoResponse(razonamiento.getId(), razonamiento.getEstado(), razonamiento.getCreadoEn(),
				razonamiento.getTerminadoEn(), razonamiento.getIntentos(), razonamiento.getUltimoError(),
				razonamiento.getProveedor(), razonamiento.getModelo(), razonamiento.getVersionDelPrompt(),
				razonamiento.getTokensDeEntrada(), razonamiento.getTokensDeSalida(),
				razonamiento.getPedidosAlModelo(), razonamiento.getMilisegundos(), razonamiento.getCondiciones(),
				razonamiento.getConclusiones(), razonamiento.getHerramientas());
	}

}
