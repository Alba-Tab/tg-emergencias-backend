package com.uem.ambulancias.coordinacion.service;

import java.util.List;
import java.util.Map;

import com.uem.ambulancias.coordinacion.domain.FuenteDelVeredicto;
import com.uem.ambulancias.coordinacion.domain.ResultadoDeCondicion;
import com.uem.ambulancias.emergencias.domain.RespuestaDelTestigo;
import com.uem.ambulancias.protocolo.domain.Condicion;
import com.uem.ambulancias.protocolo.domain.TipoDeSuceso;

/**
 * Cómo se decide cada condición del protocolo. Va por escalones, del más directo al menos:
 *
 * <ol>
 * <li>Lo que contestó el testigo. Un «sí» cierra la condición: se cumple y no se consulta a nadie más. Un «no» la
 * descarta, salvo que haya evidencia analizada que pueda decir otra cosa.</li>
 * <li>El modelo de decisión, para lo que quedó abierto. Su probabilidad se compara con los dos umbrales de la
 * condición: desde el de actuar se cumple, desde el de preguntar queda en duda y por debajo no se cumple.</li>
 * </ol>
 *
 * <p>Si el modelo no responde, vale lo que dejaron las reglas. Acá no hay base de datos ni llamadas: solo la regla.
 */
public final class JuicioDelProtocolo {

	private JuicioDelProtocolo() {
	}

	/**
	 * Lo que se sabe del caso sin interpretar nada.
	 *
	 * @param respuestas       lo que contestó el testigo, por código de condición
	 * @param hayEvidenciaUtil si alguna foto o audio analizado sirve para describir lo que pasa
	 */
	public record Hechos(Map<String, RespuestaDelTestigo> respuestas, boolean hayEvidenciaUtil) {
	}

	/** El primer escalón: un veredicto por condición, en el orden del protocolo, y cuáles siguen para el modelo. */
	public static List<Veredicto> porReglas(TipoDeSuceso tipo, Hechos hechos) {
		return tipo.getCondiciones().stream().map(condicion -> porReglas(condicion, hechos)).toList();
	}

	private static Veredicto porReglas(Condicion condicion, Hechos hechos) {
		RespuestaDelTestigo respuesta = hechos.respuestas().get(condicion.getCodigo());
		if (respuesta == RespuestaDelTestigo.SI) {
			return new Veredicto(condicion.getCodigo(), ResultadoDeCondicion.CUMPLIDA, FuenteDelVeredicto.TESTIGO,
					null, respuesta, false);
		}
		if (respuesta == RespuestaDelTestigo.NO) {
			// Quien está en el lugar dijo que no. Solo se vuelve a mirar si hay una evidencia que pueda contradecirlo.
			return new Veredicto(condicion.getCodigo(), ResultadoDeCondicion.NO_CUMPLIDA, FuenteDelVeredicto.TESTIGO,
					null, respuesta, hechos.hayEvidenciaUtil());
		}
		return new Veredicto(condicion.getCodigo(), ResultadoDeCondicion.SIN_EVALUAR, null, null, respuesta, true);
	}

	/**
	 * El segundo escalón: las condiciones que seguían para el modelo toman su veredicto de la probabilidad que dio.
	 * Una condición que el modelo no contestó se queda con lo que dejaron las reglas.
	 */
	public static List<Veredicto> conModelo(TipoDeSuceso tipo, List<Veredicto> porReglas,
			Map<String, Double> probabilidades) {
		return porReglas.stream().map(veredicto -> {
			Double probabilidad = probabilidades.get(veredicto.condicion());
			if (!veredicto.paraElModelo() || probabilidad == null) {
				return veredicto;
			}
			Condicion condicion = tipo.buscarCondicion(veredicto.condicion()).orElseThrow();
			return new Veredicto(veredicto.condicion(), segunUmbrales(condicion, probabilidad),
					FuenteDelVeredicto.MODELO, probabilidad, veredicto.respuestaDelTestigo(), true);
		}).toList();
	}

	static ResultadoDeCondicion segunUmbrales(Condicion condicion, double probabilidad) {
		if (probabilidad >= condicion.getUmbralActuar()) {
			return ResultadoDeCondicion.CUMPLIDA;
		}
		return probabilidad >= condicion.getUmbralPreguntar() ? ResultadoDeCondicion.EN_DUDA
				: ResultadoDeCondicion.NO_CUMPLIDA;
	}

}
