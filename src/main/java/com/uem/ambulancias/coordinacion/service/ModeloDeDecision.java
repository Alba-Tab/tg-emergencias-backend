package com.uem.ambulancias.coordinacion.service;

/**
 * El modelo que estima, para cada condición del protocolo, qué tan probable es que se cumpla en un caso. No decide
 * nada: devuelve probabilidades, y qué se hace con ellas lo resuelven los umbrales del protocolo.
 *
 * <p>Se llama solo desde el evaluador, nunca desde una petición de una app, y siempre fuera de una transacción. Si no
 * responde bien, lanza {@link com.uem.ambulancias.evidencias.service.FalloDelServicioIa} con lo que conviene hacer.
 */
public interface ModeloDeDecision {

	ProbabilidadesRecibidas evaluar(PedidoDeEvaluacion pedido);

}
