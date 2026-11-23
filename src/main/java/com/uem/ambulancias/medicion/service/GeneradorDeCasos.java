package com.uem.ambulancias.medicion.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import com.uem.ambulancias.protocolo.domain.Condicion;
import com.uem.ambulancias.protocolo.domain.TipoDeSuceso;

/**
 * Inventa casos de los que se sabe la verdad, a partir de un tipo de suceso del protocolo. Primero sortea qué
 * condiciones son ciertas y después arma un estado que se parezca a eso, con el ruido de un caso de verdad: un
 * testigo que a veces no contesta, a veces no sabe y cada tanto se equivoca, y evidencias que muestran algunas cosas
 * y otras no.
 *
 * <p>Con la misma semilla sale siempre el mismo caso. No hay base de datos ni llamadas: los casos que arma son
 * inventados y así quedan marcados en el banco.
 */
public final class GeneradorDeCasos {

	/** Qué tan seguido es cierta una condición. */
	private static final double CIERTA = 0.4;

	/** Qué tan seguido el testigo contesta una pregunta, y cómo: bien, «no sé» o equivocado. */
	private static final double CONTESTA = 0.6;
	private static final double NO_SABE = 0.15;
	private static final double SE_EQUIVOCA = 0.05;

	/** Qué tan seguido una evidencia muestra una condición cierta, o sugiere una que no lo es. */
	private static final double SE_VE = 0.7;
	private static final double PARECE = 0.08;

	private GeneradorDeCasos() {
	}

	/**
	 * @param estado   con la misma forma que el estado que se arma al evaluar un incidente
	 * @param verdades por código de condición, si es cierta
	 */
	public record Caso(Map<String, Object> estado, Map<String, Boolean> verdades) {
	}

	public static Caso generar(TipoDeSuceso tipo, long semilla) {
		Random azar = new Random(semilla);
		// La cantidad de afectados se sortea primero: de ella sale la verdad de las condiciones por cantidad.
		Integer afectados = azar.nextDouble() < 0.7 ? 1 + azar.nextInt(5) : null;
		Map<String, Boolean> verdades = new LinkedHashMap<>();
		for (Condicion condicion : tipo.getCondiciones()) {
			if (condicion.getAfectadosDesde() != null) {
				if (afectados == null) {
					afectados = 1 + azar.nextInt(5);
				}
				verdades.put(condicion.getCodigo(), afectados >= condicion.getAfectadosDesde());
			} else {
				verdades.put(condicion.getCodigo(), azar.nextDouble() < CIERTA);
			}
		}

		Map<String, String> respuestas = new LinkedHashMap<>();
		for (Condicion condicion : tipo.conPregunta()) {
			if (azar.nextDouble() >= CONTESTA) {
				continue;
			}
			boolean cierta = verdades.get(condicion.getCodigo());
			double como = azar.nextDouble();
			if (como < NO_SABE) {
				respuestas.put(condicion.getCodigo(), "NO_SE");
			} else if (como < NO_SABE + SE_EQUIVOCA) {
				respuestas.put(condicion.getCodigo(), cierta ? "NO" : "SI");
			} else {
				respuestas.put(condicion.getCodigo(), cierta ? "SI" : "NO");
			}
		}

		List<Map<String, Object>> evidencias = new ArrayList<>();
		int cuantas = azar.nextInt(3);
		for (int i = 0; i < cuantas; i++) {
			List<String> observaciones = new ArrayList<>();
			for (Condicion condicion : tipo.getCondiciones()) {
				if (condicion.getAfectadosDesde() != null) {
					continue;
				}
				boolean cierta = verdades.get(condicion.getCodigo());
				if (cierta && azar.nextDouble() < SE_VE) {
					observaciones.add("Se observa que " + enMinuscula(condicion.getEnunciado()) + ".");
				} else if (!cierta && azar.nextDouble() < PARECE) {
					observaciones.add("No se distingue bien si " + enMinuscula(condicion.getEnunciado()) + ".");
				}
			}
			Map<String, Object> evidencia = new LinkedHashMap<>();
			evidencia.put("modalidad", azar.nextBoolean() ? "image" : "audio");
			evidencia.put("resumen", "Suceso de tipo " + enMinuscula(tipo.getNombre()) + ".");
			if (!observaciones.isEmpty()) {
				evidencia.put("observaciones", observaciones);
			}
			evidencias.add(evidencia);
		}

		Map<String, Object> estado = new LinkedHashMap<>();
		estado.put("tipoDeSuceso", Map.of("codigo", tipo.getCodigo(), "nombre", tipo.getNombre()));
		if (afectados != null) {
			estado.put("afectadosDeclarados", afectados);
		}
		estado.put("personasQueAlertaron", 1 + azar.nextInt(3));
		estado.put("respuestasDelTestigo", respuestas);
		estado.put("evidencias", evidencias);
		return new Caso(estado, verdades);
	}

	private static String enMinuscula(String texto) {
		String limpio = texto.strip();
		if (limpio.endsWith(".")) {
			limpio = limpio.substring(0, limpio.length() - 1);
		}
		return limpio.isEmpty() ? limpio : limpio.substring(0, 1).toLowerCase(Locale.ROOT) + limpio.substring(1);
	}

}
