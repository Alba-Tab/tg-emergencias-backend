package com.uem.ambulancias.coordinacion.domain;

/** Con qué se terminó resolviendo una evaluación. */
public enum MetodoDeEvaluacion {

	/** Las reglas alcanzaron: no hizo falta consultar al modelo. */
	REGLAS,

	/** Lo que las reglas no resolvieron lo estimó el modelo de decisión. */
	MODELO,

	/** Había condiciones para el modelo, pero no respondió: quedaron las reglas como respaldo. */
	REGLAS_SIN_MODELO

}
