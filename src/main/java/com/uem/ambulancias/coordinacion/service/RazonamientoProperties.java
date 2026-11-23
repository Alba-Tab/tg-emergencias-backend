package com.uem.ambulancias.coordinacion.service;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Cómo trabaja el worker de los razonamientos ({@code sga.razonamiento.*}). */
@ConfigurationProperties(prefix = "sga.razonamiento")
public record RazonamientoProperties(

		/** Cuántas veces se le pide un razonamiento al agente antes de pasarle las condiciones a una persona. */
		@DefaultValue("2") int maximoIntentos,

		/** Espera antes de volver a pedirlo. */
		@DefaultValue("5") int esperaSeg,

		/** Cuánto tiempo es del worker el razonamiento que tomó. Mayor que la llamada más larga al agente. */
		@DefaultValue("180") int segundosBloqueo) {

	public Duration espera() {
		return Duration.ofSeconds(esperaSeg);
	}

	public Duration bloqueo() {
		return Duration.ofSeconds(segundosBloqueo);
	}

}
