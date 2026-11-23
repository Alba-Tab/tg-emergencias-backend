package com.uem.ambulancias.protocolo.domain;

/**
 * Por dónde va una versión del protocolo. Solo el borrador se puede editar, y solo hay uno a la vez; al publicarlo
 * pasa a ser el vigente y el que lo era queda retirado.
 */
public enum EstadoProtocolo {

	BORRADOR,
	VIGENTE,
	RETIRADO

}
