package com.uem.ambulancias.coordinacion.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** Lo que la persona de la central puede contestar a un caso que el sistema le pasó. */
@Getter
@RequiredArgsConstructor
public enum OpcionDeDerivacion {

	/** La condición se da por cumplida: el sistema aplica la respuesta que el protocolo le asigna. */
	SE_CUMPLE("Sí, se cumple"),

	/** La condición se descarta. */
	NO_SE_CUMPLE("No se cumple"),

	/** La persona queda a cargo y resuelve con las herramientas del panel. */
	ENTERADO("Lo tomo yo"),

	/** La persona va a pedir una unidad a otra institución. El sistema sigue buscando una propia. */
	PEDIR_APOYO_EXTERNO("Pido apoyo externo"),

	/** No se pide nada afuera: se espera a que quede libre una unidad propia. */
	SEGUIR_ESPERANDO("Seguir esperando una unidad propia"),

	/** Se hace lo que el agente propuso. */
	ACEPTAR_PROPUESTA("Aceptar la propuesta"),

	/** No se hace lo que el agente propuso. */
	RECHAZAR_PROPUESTA("Rechazar la propuesta");

	private final String texto;

}
