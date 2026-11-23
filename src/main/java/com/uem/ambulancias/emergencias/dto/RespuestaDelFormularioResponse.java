package com.uem.ambulancias.emergencias.dto;

import java.util.List;
import java.util.Map;

import com.uem.ambulancias.emergencias.domain.RespuestaDelTestigo;

/** Lo que quedó contestado para una condición del formulario: la última respuesta que mandó el ciudadano. */
public record RespuestaDelFormularioResponse(String condicion, RespuestaDelTestigo valor) {

	public static List<RespuestaDelFormularioResponse> de(Map<String, RespuestaDelTestigo> respuestas) {
		return respuestas.entrySet().stream()
				.map(respuesta -> new RespuestaDelFormularioResponse(respuesta.getKey(), respuesta.getValue()))
				.toList();
	}

}
