package com.uem.ambulancias.coordinacion.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.uem.ambulancias.coordinacion.domain.FuenteDelVeredicto;
import com.uem.ambulancias.coordinacion.domain.ResultadoDeCondicion;
import com.uem.ambulancias.emergencias.domain.RespuestaDelTestigo;
import com.uem.ambulancias.flota.domain.TipoUnidad;
import com.uem.ambulancias.protocolo.domain.Condicion;
import com.uem.ambulancias.protocolo.domain.TipoDeSuceso;

/**
 * Cómo se decide cada condición del protocolo. Va por escalones, del más directo al menos:
 *
 * <ol>
 * <li>Lo que ya decidió una persona de la central, o lo que se dio por cumplido ante la duda. Manda sobre todo lo
 * demás y no se vuelve a consultar.</li>
 * <li>Lo que contestó el testigo. Un «sí» cierra la condición: se cumple y no se consulta a nadie más. Un «no» la
 * descarta, salvo que haya evidencia analizada que pueda decir otra cosa.</li>
 * <li>La regla por cantidad, en las condiciones que la tienen: se cumple cuando los afectados declarados llegan al
 * número del protocolo. Esas condiciones nunca van al modelo, que no cuenta bien.</li>
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
	 * @param afectados        cuántos afectados se declararon, o {@code null} si nadie dijo cuántos
	 * @param hayEvidenciaUtil si alguna foto o audio analizado sirve para describir lo que pasa
	 * @param decididas        las condiciones que ya decidió una persona o que se dieron por cumplidas ante la duda
	 */
	public record Hechos(Map<String, RespuestaDelTestigo> respuestas, Integer afectados, boolean hayEvidenciaUtil,
			Map<String, Decidida> decididas) {

		public Hechos(Map<String, RespuestaDelTestigo> respuestas, Integer afectados, boolean hayEvidenciaUtil) {
			this(respuestas, afectados, hayEvidenciaUtil, Map.of());
		}

	}

	/**
	 * Una condición que ya se decidió fuera de las reglas y del modelo.
	 *
	 * @param fuente quién la decidió: la central, o nadie y se dio por cumplida ante la duda
	 */
	public record Decidida(boolean cumple, FuenteDelVeredicto fuente) {
	}

	/** El primer escalón: un veredicto por condición, en el orden del protocolo, y cuáles siguen para el modelo. */
	public static List<Veredicto> porReglas(TipoDeSuceso tipo, Hechos hechos) {
		return tipo.getCondiciones().stream().map(condicion -> porReglas(condicion, hechos)).toList();
	}

	private static Veredicto porReglas(Condicion condicion, Hechos hechos) {
		RespuestaDelTestigo respuesta = hechos.respuestas().get(condicion.getCodigo());
		Decidida decidida = hechos.decididas().get(condicion.getCodigo());
		if (decidida != null) {
			return new Veredicto(condicion.getCodigo(), decidida.cumple() ? ResultadoDeCondicion.CUMPLIDA
					: ResultadoDeCondicion.NO_CUMPLIDA, decidida.fuente(), null, respuesta, false);
		}
		if (respuesta == RespuestaDelTestigo.SI) {
			return new Veredicto(condicion.getCodigo(), ResultadoDeCondicion.CUMPLIDA, FuenteDelVeredicto.TESTIGO,
					null, respuesta, false);
		}
		if (condicion.getAfectadosDesde() != null) {
			return porCantidad(condicion, hechos.afectados(), respuesta);
		}
		if (respuesta == RespuestaDelTestigo.NO) {
			// Quien está en el lugar dijo que no. Solo se vuelve a mirar si hay una evidencia que pueda contradecirlo.
			return new Veredicto(condicion.getCodigo(), ResultadoDeCondicion.NO_CUMPLIDA, FuenteDelVeredicto.TESTIGO,
					null, respuesta, hechos.hayEvidenciaUtil());
		}
		return new Veredicto(condicion.getCodigo(), ResultadoDeCondicion.SIN_EVALUAR, null, null, respuesta, true);
	}

	/**
	 * Una condición que depende de la cantidad de afectados. Con la cantidad declarada decide la regla; sin ella vale
	 * el «no» del testigo, si lo dio, y si no queda sin evaluar. En ningún caso sigue para el modelo.
	 */
	private static Veredicto porCantidad(Condicion condicion, Integer afectados, RespuestaDelTestigo respuesta) {
		if (afectados != null) {
			ResultadoDeCondicion resultado = afectados >= condicion.getAfectadosDesde()
					? ResultadoDeCondicion.CUMPLIDA
					: ResultadoDeCondicion.NO_CUMPLIDA;
			return new Veredicto(condicion.getCodigo(), resultado, FuenteDelVeredicto.REGLA, null, respuesta, false);
		}
		if (respuesta == RespuestaDelTestigo.NO) {
			return new Veredicto(condicion.getCodigo(), ResultadoDeCondicion.NO_CUMPLIDA, FuenteDelVeredicto.TESTIGO,
					null, respuesta, false);
		}
		return new Veredicto(condicion.getCodigo(), ResultadoDeCondicion.SIN_EVALUAR, null, null, respuesta, false);
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

	/**
	 * Lo que el caso necesita según las condiciones que se cumplen. Las unidades no se suman entre condiciones: vale
	 * la que más pide, como en un plan de despacho por determinantes. El tipo mínimo es el mayor de los que piden.
	 */
	public static PlanDeRespuesta plan(TipoDeSuceso tipo, List<Veredicto> veredictos) {
		int adicionales = 0;
		TipoUnidad tipoMinimo = null;
		List<String> porCondiciones = new ArrayList<>();
		for (Veredicto veredicto : veredictos) {
			if (!veredicto.cumplida()) {
				continue;
			}
			Condicion condicion = tipo.buscarCondicion(veredicto.condicion()).orElseThrow();
			adicionales = Math.max(adicionales, condicion.getUnidadesAdicionales());
			if (condicion.getTipoMinimo() != null) {
				tipoMinimo = tipoMinimo == null ? condicion.getTipoMinimo()
						: TipoUnidad.elMayor(tipoMinimo, condicion.getTipoMinimo());
			}
			if (condicion.getUnidadesAdicionales() > 0 || condicion.getTipoMinimo() != null) {
				porCondiciones.add(condicion.getCodigo());
			}
		}
		return new PlanDeRespuesta(1 + adicionales, tipoMinimo, List.copyOf(porCondiciones));
	}

	static ResultadoDeCondicion segunUmbrales(Condicion condicion, double probabilidad) {
		if (probabilidad >= condicion.getUmbralActuar()) {
			return ResultadoDeCondicion.CUMPLIDA;
		}
		return probabilidad >= condicion.getUmbralPreguntar() ? ResultadoDeCondicion.EN_DUDA
				: ResultadoDeCondicion.NO_CUMPLIDA;
	}

}
