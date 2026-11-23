package com.uem.ambulancias.agente.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * El servidor de herramientas del agente ({@code sga.agente.*}). Sin token, el servidor está apagado: nadie de
 * afuera puede consultar ni actuar sobre la central.
 */
@ConfigurationProperties(prefix = "sga.agente")
public record AgenteProperties(

		/**
		 * El token con el que se presenta el servicio de IA al pedir herramientas. Es el {@code AI_BACKEND_MCP_TOKEN}
		 * de ese servicio. Nunca se versiona.
		 */
		String token,

		/** Cuántos casos anteriores devuelve como máximo la búsqueda de casos parecidos. */
		@DefaultValue("5") int casosParecidos,

		/** Hasta cuántas horas hacia adelante se pueden pedir los traslados próximos. */
		@DefaultValue("24") int horasDeTraslados) {

	public boolean habilitado() {
		return token != null && !token.isBlank();
	}

}
