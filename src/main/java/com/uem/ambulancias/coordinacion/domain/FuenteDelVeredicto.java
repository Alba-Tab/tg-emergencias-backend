package com.uem.ambulancias.coordinacion.domain;

/**
 * De dónde salió el veredicto de una condición. Van de la más directa a la menos: lo que una regla resuelve no se le
 * pregunta al modelo.
 */
public enum FuenteDelVeredicto {

	/** Lo contestó quien está en el lugar. */
	TESTIGO,

	/** Lo resolvió una regla del protocolo sobre un dato declarado, como la cantidad de afectados. */
	REGLA,

	/** Lo estimó el modelo de decisión, y se comparó su probabilidad con los umbrales de la condición. */
	MODELO,

	/** Lo decidió una persona de la central a la que el sistema le pasó la condición. Manda sobre todo lo demás. */
	CENTRAL,

	/** Nadie contestó a tiempo y la condición se dio por cumplida: ante la duda, se atiende como el caso más grave. */
	ANTE_LA_DUDA,

	/**
	 * Lo resolvió el agente razonando con sus herramientas, y dejó su porqué. Vale mientras no llegue un dato nuevo:
	 * con uno, la condición se vuelve a evaluar desde el principio.
	 */
	AGENTE

}
