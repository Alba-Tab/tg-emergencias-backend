package com.uem.ambulancias.coordinacion.domain;

/** En qué anda una evaluación del caso. Se guarda como texto, sin restricción en la base. */
public enum EstadoEvaluacion {

	/** Espera a que el evaluador la tome. */
	PENDIENTE,

	/** El evaluador la tomó y está juntando lo que se sabe o consultando al modelo. */
	EN_CURSO,

	/** Terminó y dejó sus veredictos, con el modelo o solo con las reglas. */
	HECHA,

	/** Ya no hacía falta: el incidente se cerró o una unidad llegó al lugar antes de evaluarla. */
	DESCARTADA,

	/** No se pudo terminar por un error de este lado. */
	FALLIDA

}
