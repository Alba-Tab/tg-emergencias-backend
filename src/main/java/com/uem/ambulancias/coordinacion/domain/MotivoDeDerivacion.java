package com.uem.ambulancias.coordinacion.domain;

import java.util.List;

import lombok.Getter;

/**
 * Por qué el sistema le pasa un caso a una persona. Cada motivo trae las opciones que la persona puede elegir y la
 * opción segura: la que se aplica sola si nadie contesta a tiempo.
 */
@Getter
public enum MotivoDeDerivacion {

	/**
	 * Una condición quedó en duda y no hay más a quién preguntarle. Si nadie contesta se da por cumplida: ante la
	 * duda, la norma manda atender como el caso más grave.
	 */
	CONDICION_SIN_RESOLVER(OpcionDeDerivacion.SE_CUMPLE, OpcionDeDerivacion.SE_CUMPLE,
			OpcionDeDerivacion.NO_SE_CUMPLE),

	/** Se cumple una condición que el protocolo marca para que la vea una persona, sin importar lo demás. */
	CONDICION_QUE_DERIVA(OpcionDeDerivacion.ENTERADO, OpcionDeDerivacion.ENTERADO),

	/** No quedó ninguna unidad propia para el incidente, o ninguna del tipo que hace falta. */
	SIN_UNIDAD(OpcionDeDerivacion.SEGUIR_ESPERANDO, OpcionDeDerivacion.PEDIR_APOYO_EXTERNO,
			OpcionDeDerivacion.SEGUIR_ESPERANDO),

	/**
	 * El agente propone algo que no puede hacer solo, como quitar una unidad o cambiarla. Si nadie contesta, no se
	 * hace: el sistema solo suma recursos por su cuenta.
	 */
	PROPUESTA_DEL_AGENTE(OpcionDeDerivacion.RECHAZAR_PROPUESTA, OpcionDeDerivacion.ACEPTAR_PROPUESTA,
			OpcionDeDerivacion.RECHAZAR_PROPUESTA);

	private final OpcionDeDerivacion opcionSegura;
	private final List<OpcionDeDerivacion> opciones;

	MotivoDeDerivacion(OpcionDeDerivacion opcionSegura, OpcionDeDerivacion... opciones) {
		this.opcionSegura = opcionSegura;
		this.opciones = List.of(opciones);
	}

	public boolean admite(OpcionDeDerivacion opcion) {
		return opciones.contains(opcion);
	}

}
