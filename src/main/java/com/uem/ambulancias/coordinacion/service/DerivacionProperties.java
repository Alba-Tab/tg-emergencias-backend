package com.uem.ambulancias.coordinacion.service;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Los tiempos de la derivación a una persona ({@code sga.derivacion.*}). */
@ConfigurationProperties(prefix = "sga.derivacion")
public record DerivacionProperties(

		/** Cuánto se espera la respuesta de la persona antes de aplicar la opción segura. */
		@DefaultValue("60") int segundosRespuesta,

		/**
		 * Cuánto se espera que el testigo conteste una pregunta antes de pasarle la condición a una persona. Corto: el
		 * testigo tiene el teléfono en la mano.
		 */
		@DefaultValue("45") int segundosDelTestigo) {

	public Duration respuesta() {
		return Duration.ofSeconds(segundosRespuesta);
	}

	public Duration esperaDelTestigo() {
		return Duration.ofSeconds(segundosDelTestigo);
	}

}
