package com.uem.ambulancias.coordinacion.domain;

/** En qué quedó una condición del protocolo después de evaluarla. */
public enum ResultadoDeCondicion {

	/** Hay certeza suficiente de que se cumple: se aplica la respuesta que el protocolo le asigna. */
	CUMPLIDA,

	/** No alcanza para actuar, pero tampoco para descartarla: hay que conseguir más información. */
	EN_DUDA,

	/** Con lo que se sabe, no se cumple. */
	NO_CUMPLIDA,

	/** Nadie la pudo evaluar: el testigo no la contestó y el modelo no estaba disponible. */
	SIN_EVALUAR

}
