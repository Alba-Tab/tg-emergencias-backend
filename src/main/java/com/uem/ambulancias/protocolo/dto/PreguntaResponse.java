package com.uem.ambulancias.protocolo.dto;

import com.uem.ambulancias.protocolo.domain.Condicion;

/** Una pregunta del formulario. {@code condicion} es el código con el que se manda la respuesta. */
public record PreguntaResponse(String condicion, String pregunta) {

	public static PreguntaResponse de(Condicion condicion) {
		return new PreguntaResponse(condicion.getCodigo(), condicion.getPregunta());
	}

}
