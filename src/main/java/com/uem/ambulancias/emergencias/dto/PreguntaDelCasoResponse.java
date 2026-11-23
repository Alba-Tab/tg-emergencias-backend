package com.uem.ambulancias.emergencias.dto;

import com.uem.ambulancias.emergencias.domain.RespuestaDelTestigo;
import com.uem.ambulancias.emergencias.service.PreguntaDelCaso;

/**
 * Una pregunta del formulario de una alerta, con lo que el ciudadano ya contestó. {@code prioritaria} marca las que
 * el sistema necesita ahora para decidir qué mandar: la app las muestra primero.
 */
public record PreguntaDelCasoResponse(String condicion, String pregunta, RespuestaDelTestigo respuesta,
		boolean prioritaria) {

	public static PreguntaDelCasoResponse de(PreguntaDelCaso pregunta) {
		return new PreguntaDelCasoResponse(pregunta.condicion(), pregunta.pregunta(), pregunta.respuesta(),
				pregunta.prioritaria());
	}

}
