package com.uem.ambulancias.coordinacion.service;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Cómo trabaja el evaluador de casos ({@code sga.coordinacion.*}). Los tiempos son cortos a propósito: una evaluación
 * que tarda minutos ya no sirve para decidir qué mandar.
 */
@ConfigurationProperties(prefix = "sga.coordinacion")
public record CoordinacionProperties(

		/** Cuántas veces se consulta al modelo por una evaluación antes de quedarse solo con las reglas. */
		@DefaultValue("3") int maximoIntentos,

		/** Espera antes de volver a consultar al modelo. Se duplica en cada intento, hasta la máxima. */
		@DefaultValue("5") int esperaInicialSeg,

		@DefaultValue("30") int esperaMaximaSeg,

		/**
		 * Cuánto tiempo es de un evaluador la evaluación que tomó. Tiene que pasar la llamada más larga al modelo: si
		 * no, otro la tomaría mientras el primero todavía espera la respuesta.
		 */
		@DefaultValue("90") int segundosBloqueo) {

	public Duration bloqueo() {
		return Duration.ofSeconds(segundosBloqueo);
	}

	/** Espera antes del próximo intento, sabiendo cuántos ya se hicieron. */
	public Duration esperaTras(int intentos) {
		long segundos = (long) esperaInicialSeg << Math.min(Math.max(intentos - 1, 0), 20);
		return Duration.ofSeconds(Math.min(segundos, esperaMaximaSeg));
	}

}
