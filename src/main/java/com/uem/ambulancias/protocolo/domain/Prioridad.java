package com.uem.ambulancias.protocolo.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Qué tan urgente es un incidente. Son las prioridades del triaje telefónico de la Norma Nacional de Caracterización
 * del Sistema Nacional de Emergencias en Salud (Ministerio de Salud, 2017): P1 código rojo, P2 código amarillo y P3
 * código verde. La norma tiene además un P4, código negro, en el que no se movilizan recursos: el sistema no lo usa,
 * porque no mandar a nadie lo decide siempre una persona.
 *
 * <p>Van de la menos urgente a la más urgente, y ese orden se usa para comparar.
 */
@Getter
@RequiredArgsConstructor
public enum Prioridad {

	/** El recurso puede ordenar la asistencia por cercanía u otros criterios. */
	VERDE("P3"),

	/** El recurso sale rápido; si está en otra asistencia, la termina. */
	AMARILLA("P2"),

	/** El recurso sale de inmediato. */
	ROJA("P1");

	private final String codigo;

	/** Un caso sin prioridad no es más urgente que ninguno: para comparar vale lo mismo que uno verde. */
	public static int nivelDe(Prioridad prioridad) {
		return prioridad == null ? VERDE.ordinal() : prioridad.ordinal();
	}

	public boolean masUrgenteQue(Prioridad otra) {
		return ordinal() > nivelDe(otra);
	}

	/** La más urgente de las dos. Si falta una, la otra. */
	public static Prioridad laMayor(Prioridad una, Prioridad otra) {
		if (una == null) {
			return otra;
		}
		return otra == null || una.compareTo(otra) >= 0 ? una : otra;
	}

}
