package com.uem.ambulancias.coordinacion.domain;

/** En qué anda un caso que el sistema le pasó a una persona. Se guarda como texto, sin restricción en la base. */
public enum EstadoDerivacion {

	/** Espera la respuesta de una persona. */
	ABIERTA,

	/** Una persona contestó a tiempo. */
	RESUELTA,

	/** Nadie contestó a tiempo y se aplicó la opción segura. Sigue a la vista hasta que alguien la marque como vista. */
	VENCIDA,

	/** Dejó de hacer falta antes de que contestaran: el incidente se cerró o una unidad llegó al lugar. */
	RETIRADA

}
