package com.uem.ambulancias.emergencias.domain;

/**
 * Por dónde va una asignación que espera la confirmación de la tripulación. Solo la pendiente está viva; las demás
 * son finales y quedan como registro de a quién se le ofreció el incidente y qué contestó.
 */
public enum EstadoOferta {

	/** Se le asignó a la unidad y se espera que confirme. */
	PENDIENTE,

	/** La tripulación confirmó: la unidad ya va en camino. */
	CONFIRMADA,

	/** La tripulación dijo que no puede ir. */
	RECHAZADA,

	/** Se acabó el tiempo sin respuesta. */
	VENCIDA,

	/** Dejó de hacer falta antes de que contestaran: el incidente se cerró o lo tomó otra unidad. */
	RETIRADA

}
