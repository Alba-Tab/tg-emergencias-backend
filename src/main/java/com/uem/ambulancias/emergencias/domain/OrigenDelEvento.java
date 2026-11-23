package com.uem.ambulancias.emergencias.domain;

/**
 * De quién viene lo anotado en la bitácora del caso. Importa porque no todas las fuentes valen lo mismo: lo que dice
 * quien está en el lugar pesa más que lo que se dedujo de una foto.
 */
public enum OrigenDelEvento {

	/** Lo declaró quien alertó. */
	CIUDADANO,

	/** Lo describió el servicio de análisis a partir de una evidencia. */
	ANALISIS

}
