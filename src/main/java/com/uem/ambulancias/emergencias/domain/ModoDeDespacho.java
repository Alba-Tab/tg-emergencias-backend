package com.uem.ambulancias.emergencias.domain;

/**
 * Cómo consigue una unidad un incidente que la necesita. Lo elige la central y se puede cambiar en cualquier
 * momento.
 */
public enum ModoDeDespacho {

	/** El incidente se avisa a todas las unidades de turno y lo toma la que quiera. Es como funcionó siempre. */
	DIFUSION,

	/**
	 * El sistema le asigna el incidente a la unidad libre que llega antes y espera su confirmación; si no confirma a
	 * tiempo o dice que no puede, pasa a la siguiente. Si se queda sin a quién asignar, lo avisa a todas.
	 */
	ASIGNACION

}
