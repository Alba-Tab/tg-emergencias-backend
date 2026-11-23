package com.uem.ambulancias.coordinacion.domain;

/** A qué llegó el agente sobre una condición. */
public enum ConclusionDelAgente {

	/** Con lo que vio, la condición se cumple. */
	CUMPLE,

	/** Con lo que vio, la condición no se cumple. */
	NO_CUMPLE,

	/** La información no alcanza: lo decide una persona. */
	NO_SE_SABE;

	/** Como lo nombra el servicio de IA. Un valor desconocido se toma como «no se sabe». */
	public static ConclusionDelAgente de(String veredicto) {
		if ("met".equals(veredicto)) {
			return CUMPLE;
		}
		return "not_met".equals(veredicto) ? NO_CUMPLE : NO_SE_SABE;
	}

}
