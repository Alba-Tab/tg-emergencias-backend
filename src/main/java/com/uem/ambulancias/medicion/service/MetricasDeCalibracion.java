package com.uem.ambulancias.medicion.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Las cuentas de una medición: qué tan bien acierta el modelo de decisión en una condición y con qué umbrales
 * convendría usarlo. Acá no hay base de datos ni llamadas: solo números.
 *
 * <p>La probabilidad que da un modelo no es la probabilidad de acertar. Por eso los umbrales de cada condición no se
 * eligen a ojo: se miden con casos de los que se sabe la verdad, y lo medido vale para una versión del modelo.
 */
public final class MetricasDeCalibracion {

	/** Con menos casos que estos, lo medido no alcanza para fiarse de un umbral. */
	public static final int MINIMO_DE_CASOS = 30;

	/** Con menos casos positivos que estos, tampoco. */
	public static final int MINIMO_DE_POSITIVOS = 5;

	/** Para proponer un umbral de actuar hacen falta al menos estos casos por encima de él. */
	static final int MINIMO_SOBRE_EL_UMBRAL = 5;

	private static final int TRAMOS = 5;

	private MetricasDeCalibracion() {
	}

	/** Lo que respondió el modelo en un caso y lo que era cierto. */
	public record Muestra(double probabilidad, boolean verdad) {
	}

	/**
	 * Lo que pasa con un par de umbrales sobre los casos medidos.
	 *
	 * @param actuan               en cuántos casos el modelo llega al umbral de actuar
	 * @param aciertosAlActuar     en cuántos de esos la condición era cierta
	 * @param precisionAlActuar    la proporción anterior, o {@code null} si no actúa en ninguno
	 * @param enDuda               en cuántos queda entre los dos umbrales
	 * @param descartadas          en cuántos queda por debajo del umbral de preguntar
	 * @param positivosDescartados en cuántos de esos la condición era cierta: los que el sistema dejaría pasar
	 * @param cobertura            de los casos ciertos, la proporción que no se descarta
	 */
	public record ConUmbrales(double umbralActuar, double umbralPreguntar, int actuan, int aciertosAlActuar,
			Double precisionAlActuar, int enDuda, int descartadas, int positivosDescartados, Double cobertura) {
	}

	/**
	 * Un tramo de la curva de calibración: de los casos a los que el modelo les dio una probabilidad en ese tramo,
	 * cuántos eran ciertos de verdad. Bien calibrado, las dos últimas columnas se parecen.
	 */
	public record Tramo(double desde, double hasta, int casos, Double probabilidadMedia, Double frecuenciaReal) {
	}

	/**
	 * @param brier              el error cuadrático medio entre la probabilidad y la verdad: 0 es perfecto, 0,25 es
	 *                           contestar siempre 0,5
	 * @param actuarSugerido     el umbral más bajo con el que, al actuar, se acierta al menos lo pedido; {@code null}
	 *                           si ninguno lo logra
	 * @param preguntarSugerido  el umbral más alto que no descarta más casos ciertos que lo permitido
	 * @param advertencia        por qué lo medido no alcanza para fiarse, si no alcanza
	 */
	public record Resultado(int casos, int positivos, Double brier, ConUmbrales actuales, List<Tramo> curva,
			Double actuarSugerido, Double preguntarSugerido, ConUmbrales conLosSugeridos, String advertencia) {
	}

	/**
	 * @param objetivoDePrecision de las veces que se actúa, la proporción mínima en que la condición tiene que ser
	 *                            cierta
	 * @param objetivoDeCobertura de los casos ciertos, la proporción mínima que no se puede descartar
	 */
	public static Resultado calcular(List<Muestra> muestras, double umbralActuar, double umbralPreguntar,
			double objetivoDePrecision, double objetivoDeCobertura) {
		int positivos = (int) muestras.stream().filter(Muestra::verdad).count();
		if (muestras.isEmpty()) {
			return new Resultado(0, 0, null, null, List.of(), null, null, null, "No hay casos medidos.");
		}
		Double actuar = actuarSugerido(muestras, objetivoDePrecision);
		Double preguntar = preguntarSugerido(muestras, positivos, objetivoDeCobertura);
		if (actuar != null && preguntar != null && preguntar > actuar) {
			preguntar = actuar;
		}
		ConUmbrales conLosSugeridos = actuar == null || preguntar == null ? null
				: conUmbrales(muestras, positivos, actuar, preguntar);
		return new Resultado(muestras.size(), positivos, redondear(brier(muestras)),
				conUmbrales(muestras, positivos, umbralActuar, umbralPreguntar), curva(muestras), actuar, preguntar,
				conLosSugeridos, advertencia(muestras.size(), positivos));
	}

	static ConUmbrales conUmbrales(List<Muestra> muestras, int positivos, double umbralActuar,
			double umbralPreguntar) {
		int actuan = 0;
		int aciertos = 0;
		int enDuda = 0;
		int descartadas = 0;
		int positivosDescartados = 0;
		for (Muestra muestra : muestras) {
			if (muestra.probabilidad() >= umbralActuar) {
				actuan++;
				aciertos += muestra.verdad() ? 1 : 0;
			} else if (muestra.probabilidad() >= umbralPreguntar) {
				enDuda++;
			} else {
				descartadas++;
				positivosDescartados += muestra.verdad() ? 1 : 0;
			}
		}
		return new ConUmbrales(umbralActuar, umbralPreguntar, actuan, aciertos,
				actuan == 0 ? null : redondear((double) aciertos / actuan), enDuda, descartadas, positivosDescartados,
				positivos == 0 ? null : redondear((double) (positivos - positivosDescartados) / positivos));
	}

	private static double brier(List<Muestra> muestras) {
		double suma = 0;
		for (Muestra muestra : muestras) {
			double diferencia = muestra.probabilidad() - (muestra.verdad() ? 1 : 0);
			suma += diferencia * diferencia;
		}
		return suma / muestras.size();
	}

	private static List<Tramo> curva(List<Muestra> muestras) {
		List<Tramo> curva = new ArrayList<>();
		for (int i = 0; i < TRAMOS; i++) {
			double desde = (double) i / TRAMOS;
			double hasta = (double) (i + 1) / TRAMOS;
			boolean ultimo = i == TRAMOS - 1;
			List<Muestra> delTramo = muestras.stream()
					.filter(muestra -> muestra.probabilidad() >= desde
							&& (ultimo ? muestra.probabilidad() <= hasta : muestra.probabilidad() < hasta))
					.toList();
			if (delTramo.isEmpty()) {
				curva.add(new Tramo(desde, hasta, 0, null, null));
				continue;
			}
			double media = delTramo.stream().mapToDouble(Muestra::probabilidad).average().orElse(0);
			double ciertos = delTramo.stream().filter(Muestra::verdad).count();
			curva.add(new Tramo(desde, hasta, delTramo.size(), redondear(media), redondear(ciertos / delTramo.size())));
		}
		return curva;
	}

	/**
	 * Recorre las probabilidades medidas de la más baja a la más alta y se queda con la primera que, usada como
	 * umbral, acierta al menos lo pedido teniendo todavía casos suficientes por encima.
	 */
	private static Double actuarSugerido(List<Muestra> muestras, double objetivoDePrecision) {
		List<Muestra> ordenadas = muestras.stream().sorted(Comparator.comparingDouble(Muestra::probabilidad)).toList();
		for (int i = 0; i < ordenadas.size(); i++) {
			if (i > 0 && ordenadas.get(i).probabilidad() == ordenadas.get(i - 1).probabilidad()) {
				continue;
			}
			List<Muestra> porEncima = ordenadas.subList(i, ordenadas.size());
			if (porEncima.size() < MINIMO_SOBRE_EL_UMBRAL) {
				break;
			}
			double precision = (double) porEncima.stream().filter(Muestra::verdad).count() / porEncima.size();
			if (precision >= objetivoDePrecision) {
				return redondear(ordenadas.get(i).probabilidad());
			}
		}
		return null;
	}

	/**
	 * El umbral más alto por debajo del cual quedan, como mucho, los casos ciertos que se permite descartar. Sin casos
	 * ciertos no hay con qué proponerlo.
	 */
	private static Double preguntarSugerido(List<Muestra> muestras, int positivos, double objetivoDeCobertura) {
		if (positivos == 0) {
			return null;
		}
		List<Double> delosCiertos = muestras.stream().filter(Muestra::verdad).map(Muestra::probabilidad).sorted()
				.toList();
		int permitidos = (int) Math.floor(positivos * (1 - objetivoDeCobertura) + 1e-9);
		// El umbral cae justo en el caso cierto que ya no se puede dejar afuera: con ">=" ese caso queda adentro.
		return redondear(delosCiertos.get(Math.min(permitidos, delosCiertos.size() - 1)));
	}

	private static String advertencia(int casos, int positivos) {
		if (casos < MINIMO_DE_CASOS) {
			return "Hay " + casos + " casos medidos: con menos de " + MINIMO_DE_CASOS
					+ " los umbrales sugeridos son solo una referencia.";
		}
		if (positivos < MINIMO_DE_POSITIVOS) {
			return "Solo en " + positivos + " casos la condición era cierta: con menos de " + MINIMO_DE_POSITIVOS
					+ " no se puede saber cuántos se dejarían pasar.";
		}
		return null;
	}

	private static double redondear(double valor) {
		return Math.round(valor * 1000.0) / 1000.0;
	}

}
