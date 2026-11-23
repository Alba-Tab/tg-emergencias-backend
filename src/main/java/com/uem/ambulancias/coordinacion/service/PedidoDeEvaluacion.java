package com.uem.ambulancias.coordinacion.service;

import java.util.List;
import java.util.UUID;

/**
 * Lo que se le manda al modelo de decisión: el estado del caso, ya armado como JSON, y las condiciones que quedaron
 * abiertas después de las reglas.
 */
public record PedidoDeEvaluacion(UUID trabajo, Long incidenteId, String estadoJson, List<Pregunta> preguntas) {

	/** Una condición escrita como afirmación de sí o no. */
	public record Pregunta(String codigo, String enunciado) {
	}

}
