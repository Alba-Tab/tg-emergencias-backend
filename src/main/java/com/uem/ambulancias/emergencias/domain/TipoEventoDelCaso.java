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
	EVIDENCIA_ANALIZADA,

	/** El ciudadano marcó qué pasó. Se anota también cuando el protocolo vigente no conoce ese código. */
	TIPO_DE_SUCESO_DECLARADO,

	/** El ciudadano contestó una pregunta del formulario de su tipo de suceso. */
	RESPUESTA_DEL_TESTIGO,

	/** El sistema le asignó el incidente a una unidad y espera su confirmación. */
	UNIDAD_ASIGNADA,

	/** La tripulación confirmó la asignación: la unidad va en camino. */
	ASIGNACION_CONFIRMADA,

	/** La tripulación dijo que no puede ir, con su motivo. */
	ASIGNACION_RECHAZADA,

	/** Se acabó el tiempo de espera sin que la tripulación contestara. */
	ASIGNACION_VENCIDA,

	/** La asignación dejó de hacer falta antes de que contestaran. */
	ASIGNACION_RETIRADA,

	/** No quedó ninguna unidad a la que asignarle el incidente: se abrió a todas. */
	SIN_UNIDAD_PARA_ASIGNAR,

	/** El sistema evaluó las condiciones del protocolo con lo que se sabía del caso hasta ese momento. */
	CONDICIONES_EVALUADAS;

	/**
	 * Si el renglón trae algo nuevo sobre lo que pasa en el lugar. Son los que piden volver a evaluar las condiciones
	 * del protocolo; los demás cuentan lo que el sistema o las tripulaciones hicieron con el caso.
	 */
	public boolean aportaDatosDelSuceso() {
		return switch (this) {
			case ALERTA_RECIBIDA, DETALLES_COMPLETADOS, EVIDENCIA_ANALIZADA, TIPO_DE_SUCESO_DECLARADO,
					RESPUESTA_DEL_TESTIGO -> true;
			default -> false;
		};
	}

}
