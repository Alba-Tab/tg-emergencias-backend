package com.uem.ambulancias.coordinacion.service;

import java.util.List;
import java.util.UUID;

/** Lo que se le manda al agente: el estado del caso, ya armado como JSON, y las condiciones que quedaron en duda. */
public record PedidoDeRazonamiento(UUID trabajo, Long incidenteId, String estadoJson,
		List<PedidoDeEvaluacion.Pregunta> preguntas) {
}
