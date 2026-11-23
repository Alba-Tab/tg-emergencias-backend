package com.uem.ambulancias.emergencias.domain;

/**
 * Qué se anotó en la bitácora del caso. Se guarda como texto, sin restricción en la base, porque la lista crece con
 * cada cosa nueva que el sistema aprende a anotar.
 */
public enum TipoEventoDelCaso {

	/** Llegó una alerta al incidente, con lo que el ciudadano declaró al emitirla. */
	ALERTA_RECIBIDA,

	/** El ciudadano completó la cantidad de afectados o la descripción mientras esperaba. */
	DETALLES_COMPLETADOS,

	/** El ciudadano retiró su pedido. */
	ALERTA_CANCELADA,

	/** El servicio de análisis terminó de describir una foto o un audio. */
	EVIDENCIA_ANALIZADA

}
