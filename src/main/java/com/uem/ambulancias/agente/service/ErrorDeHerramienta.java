package com.uem.ambulancias.agente.service;

/**
 * Una herramienta no pudo hacer lo que se le pidió, y el motivo le sirve al agente para corregirse: un incidente
 * que no existe, un dato que falta, una acción que ya no corresponde. El mensaje le llega tal cual.
 */
public class ErrorDeHerramienta extends RuntimeException {

	public ErrorDeHerramienta(String mensaje) {
		super(mensaje);
	}

}
