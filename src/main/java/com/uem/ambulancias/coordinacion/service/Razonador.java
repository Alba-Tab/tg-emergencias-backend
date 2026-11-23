package com.uem.ambulancias.coordinacion.service;

/**
 * El agente que razona sobre las condiciones que quedaron en duda después de las reglas y del modelo de decisión.
 * Puede dar varios pasos y consultar las herramientas de la central antes de concluir. Sus conclusiones traen su
 * porqué; lo que no puede resolver vuelve como «no se sabe» y lo decide una persona.
 *
 * <p>Se llama solo desde su worker, nunca desde una petición de una app, y siempre fuera de una transacción: tarda
 * decenas de segundos y, mientras tanto, pide datos a la central por otra puerta. Si no responde bien, lanza
 * {@link com.uem.ambulancias.evidencias.service.FalloDelServicioIa}.
 */
public interface Razonador {

	/** Si hay un agente al que pedirle razonamientos. Apagado, lo que queda en duda va directo a una persona. */
	boolean disponible();

	ConclusionesRecibidas razonar(PedidoDeRazonamiento pedido);

}
