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
	MODELO

}
