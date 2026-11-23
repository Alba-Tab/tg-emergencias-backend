package com.uem.ambulancias.coordinacion.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.uem.ambulancias.coordinacion.domain.FuenteDelVeredicto;
import com.uem.ambulancias.coordinacion.domain.ResultadoDeCondicion;
import com.uem.ambulancias.coordinacion.service.JuicioDelProtocolo.Hechos;
import com.uem.ambulancias.emergencias.domain.RespuestaDelTestigo;
import com.uem.ambulancias.flota.domain.TipoUnidad;
import com.uem.ambulancias.protocolo.domain.Condicion;
import com.uem.ambulancias.protocolo.domain.TipoDeSuceso;

/** Cómo se decide cada condición: primero lo que contestó el testigo, después el modelo contra los umbrales. */
@Tag("PB-35")
class JuicioDelProtocoloTest {

	private static final String ATRAPADO = "PERSONA_ATRAPADA";
	private static final String FUEGO = "HAY_FUEGO";

	/** Actúa desde 0,8 y pregunta desde 0,4. */
	private static final TipoDeSuceso ACCIDENTE = TipoDeSuceso.de("ACCIDENTE", "Accidente", List.of(
			Condicion.de(ATRAPADO, "Hay una persona atrapada", "¿Hay alguien atrapado?", 0.8, 0.4, 1, TipoUnidad.II,
					false, null),
			Condicion.de(FUEGO, "Hay fuego", null, 0.8, 0.4, 0, null, true, null)));

	private static Hechos hechos(boolean hayEvidencia, Object... condicionesYRespuestas) {
		return hechos(null, hayEvidencia, condicionesYRespuestas);
	}

	private static Hechos hechos(Integer afectados, boolean hayEvidencia, Object... condicionesYRespuestas) {
		Map<String, RespuestaDelTestigo> respuestas = new LinkedHashMap<>();
		for (int i = 0; i < condicionesYRespuestas.length; i += 2) {
			respuestas.put((String) condicionesYRespuestas[i], (RespuestaDelTestigo) condicionesYRespuestas[i + 1]);
		}
		return new Hechos(respuestas, afectados, hayEvidencia);
	}

	private static final String VARIOS = "VARIOS_HERIDOS";
	private static final String GRAVE = "HERIDO_GRAVE";

	/** VARIOS_HERIDOS se resuelve por cantidad, desde 2 afectados, y pide una unidad más. */
	private static final TipoDeSuceso CHOQUE = TipoDeSuceso.de("CHOQUE", "Choque", List.of(
			Condicion.de(VARIOS, "Hay más de un herido", "¿Hay más de un herido?", 0.8, 0.4, 1, null, false, null, 2),
			Condicion.de(ATRAPADO, "Hay una persona atrapada", null, 0.8, 0.4, 2, TipoUnidad.II, false, null),
			Condicion.de(GRAVE, "Hay un herido grave", null, 0.8, 0.4, 0, TipoUnidad.III, false, null),
			Condicion.de(FUEGO, "Hay fuego", null, 0.8, 0.4, 0, null, true, null)));

	private static Veredicto cumplida(String condicion) {
		return new Veredicto(condicion, ResultadoDeCondicion.CUMPLIDA, FuenteDelVeredicto.TESTIGO, null,
				RespuestaDelTestigo.SI, false);
	}

	private static Veredicto de(List<Veredicto> veredictos, String condicion) {
		return veredictos.stream().filter(veredicto -> veredicto.condicion().equals(condicion)).findFirst()
				.orElseThrow();
	}

	@Test
	@DisplayName("PB-35 · hay un veredicto por condición, en el orden del protocolo")
	void unoPorCondicion() {
		List<Veredicto> veredictos = JuicioDelProtocolo.porReglas(ACCIDENTE, hechos(false));

		assertThat(veredictos).extracting(Veredicto::condicion).containsExactly(ATRAPADO, FUEGO);
	}

	@Test
	@DisplayName("PB-35 · un «sí» del testigo cierra la condición: se cumple y no se le consulta al modelo")
	void elSiDelTestigoCierra() {
		Veredicto veredicto = de(JuicioDelProtocolo.porReglas(ACCIDENTE,
				hechos(true, ATRAPADO, RespuestaDelTestigo.SI)), ATRAPADO);

		assertThat(veredicto.resultado()).isEqualTo(ResultadoDeCondicion.CUMPLIDA);
		assertThat(veredicto.fuente()).isEqualTo(FuenteDelVeredicto.TESTIGO);
		assertThat(veredicto.paraElModelo()).isFalse();
		assertThat(veredicto.cumplida()).isTrue();
	}

	@Test
	@DisplayName("PB-35 · un «no» del testigo descarta la condición, y solo se vuelve a mirar si hay evidencia que sirva")
	void elNoDelTestigo() {
		Veredicto sinEvidencia = de(JuicioDelProtocolo.porReglas(ACCIDENTE,
				hechos(false, ATRAPADO, RespuestaDelTestigo.NO)), ATRAPADO);
		Veredicto conEvidencia = de(JuicioDelProtocolo.porReglas(ACCIDENTE,
				hechos(true, ATRAPADO, RespuestaDelTestigo.NO)), ATRAPADO);

		assertThat(sinEvidencia.resultado()).isEqualTo(ResultadoDeCondicion.NO_CUMPLIDA);
		assertThat(sinEvidencia.fuente()).isEqualTo(FuenteDelVeredicto.TESTIGO);
		assertThat(sinEvidencia.paraElModelo()).isFalse();
		assertThat(conEvidencia.resultado()).isEqualTo(ResultadoDeCondicion.NO_CUMPLIDA);
		assertThat(conEvidencia.paraElModelo()).isTrue();
	}

	@Test
	@DisplayName("PB-35 · lo que el testigo no contestó o no sabe queda sin evaluar y va al modelo")
	void loQueNoSeSabeVaAlModelo() {
		List<Veredicto> veredictos = JuicioDelProtocolo.porReglas(ACCIDENTE,
				hechos(false, ATRAPADO, RespuestaDelTestigo.NO_SE));

		assertThat(veredictos).allSatisfy(veredicto -> {
			assertThat(veredicto.resultado()).isEqualTo(ResultadoDeCondicion.SIN_EVALUAR);
			assertThat(veredicto.fuente()).isNull();
			assertThat(veredicto.paraElModelo()).isTrue();
		});
		assertThat(de(veredictos, ATRAPADO).respuestaDelTestigo()).isEqualTo(RespuestaDelTestigo.NO_SE);
		assertThat(de(veredictos, FUEGO).respuestaDelTestigo()).isNull();
	}

	@ParameterizedTest(name = "probabilidad {0} → {1}")
	@CsvSource({ "0.95, CUMPLIDA", "0.8, CUMPLIDA", "0.79, EN_DUDA", "0.4, EN_DUDA", "0.39, NO_CUMPLIDA",
			"0.0, NO_CUMPLIDA" })
	@DisplayName("PB-35 · la probabilidad del modelo se compara con los dos umbrales: actuar, preguntar o descartar")
	void umbrales(double probabilidad, ResultadoDeCondicion esperado) {
		List<Veredicto> porReglas = JuicioDelProtocolo.porReglas(ACCIDENTE, hechos(false));

		Veredicto veredicto = de(JuicioDelProtocolo.conModelo(ACCIDENTE, porReglas, Map.of(ATRAPADO, probabilidad,
				FUEGO, 0.1)), ATRAPADO);

		assertThat(veredicto.resultado()).isEqualTo(esperado);
		assertThat(veredicto.fuente()).isEqualTo(FuenteDelVeredicto.MODELO);
		assertThat(veredicto.probabilidad()).isEqualTo(probabilidad);
	}

	@Test
	@DisplayName("PB-35 · el modelo no pisa lo que el testigo cerró, aunque mande una probabilidad para esa condición")
	void elModeloNoPisaAlTestigo() {
		List<Veredicto> porReglas = JuicioDelProtocolo.porReglas(ACCIDENTE,
				hechos(false, ATRAPADO, RespuestaDelTestigo.SI));

		Veredicto veredicto = de(JuicioDelProtocolo.conModelo(ACCIDENTE, porReglas, Map.of(ATRAPADO, 0.01)),
				ATRAPADO);

		assertThat(veredicto.resultado()).isEqualTo(ResultadoDeCondicion.CUMPLIDA);
		assertThat(veredicto.fuente()).isEqualTo(FuenteDelVeredicto.TESTIGO);
		assertThat(veredicto.probabilidad()).isNull();
	}

	@Test
	@DisplayName("PB-35 · con evidencia, el modelo puede contradecir el «no» del testigo")
	void laEvidenciaContradiceAlTestigo() {
		List<Veredicto> porReglas = JuicioDelProtocolo.porReglas(ACCIDENTE,
				hechos(true, ATRAPADO, RespuestaDelTestigo.NO));

		Veredicto veredicto = de(JuicioDelProtocolo.conModelo(ACCIDENTE, porReglas, Map.of(ATRAPADO, 0.9)),
				ATRAPADO);

		assertThat(veredicto.resultado()).isEqualTo(ResultadoDeCondicion.CUMPLIDA);
		assertThat(veredicto.fuente()).isEqualTo(FuenteDelVeredicto.MODELO);
		assertThat(veredicto.respuestaDelTestigo()).isEqualTo(RespuestaDelTestigo.NO);
	}

	@Test
	@DisplayName("PB-35 · una condición que el modelo no contestó se queda con lo que dejaron las reglas")
	void sinRespuestaDelModelo() {
		List<Veredicto> porReglas = JuicioDelProtocolo.porReglas(ACCIDENTE,
				hechos(true, ATRAPADO, RespuestaDelTestigo.NO));

		List<Veredicto> finales = JuicioDelProtocolo.conModelo(ACCIDENTE, porReglas, Map.of());

		assertThat(de(finales, ATRAPADO).resultado()).isEqualTo(ResultadoDeCondicion.NO_CUMPLIDA);
		assertThat(de(finales, ATRAPADO).fuente()).isEqualTo(FuenteDelVeredicto.TESTIGO);
		assertThat(de(finales, FUEGO).resultado()).isEqualTo(ResultadoDeCondicion.SIN_EVALUAR);
	}

	@Test
	@Tag("PB-36")
	@DisplayName("PB-36 · una condición por cantidad la resuelve la regla con los afectados declarados y nunca va al modelo")
	void reglaPorCantidad() {
		Veredicto llega = de(JuicioDelProtocolo.porReglas(CHOQUE, hechos(3, true)), VARIOS);
		Veredicto justo = de(JuicioDelProtocolo.porReglas(CHOQUE, hechos(2, false)), VARIOS);
		Veredicto noLlega = de(JuicioDelProtocolo.porReglas(CHOQUE, hechos(1, true)), VARIOS);

		assertThat(llega.resultado()).isEqualTo(ResultadoDeCondicion.CUMPLIDA);
		assertThat(llega.fuente()).isEqualTo(FuenteDelVeredicto.REGLA);
		assertThat(justo.resultado()).isEqualTo(ResultadoDeCondicion.CUMPLIDA);
		assertThat(noLlega.resultado()).isEqualTo(ResultadoDeCondicion.NO_CUMPLIDA);
		assertThat(noLlega.fuente()).isEqualTo(FuenteDelVeredicto.REGLA);
		assertThat(List.of(llega, justo, noLlega)).noneMatch(Veredicto::paraElModelo);
	}

	@Test
	@Tag("PB-36")
	@DisplayName("PB-36 · sin la cantidad declarada, la condición por cantidad queda con lo que dijo el testigo o sin evaluar")
	void reglaPorCantidadSinDato() {
		Veredicto sinNada = de(JuicioDelProtocolo.porReglas(CHOQUE, hechos(null, true)), VARIOS);
		Veredicto conNo = de(JuicioDelProtocolo.porReglas(CHOQUE, hechos(null, true, VARIOS, RespuestaDelTestigo.NO)),
				VARIOS);
		Veredicto conSi = de(JuicioDelProtocolo.porReglas(CHOQUE, hechos(1, false, VARIOS, RespuestaDelTestigo.SI)),
				VARIOS);

		assertThat(sinNada.resultado()).isEqualTo(ResultadoDeCondicion.SIN_EVALUAR);
		assertThat(conNo.resultado()).isEqualTo(ResultadoDeCondicion.NO_CUMPLIDA);
		assertThat(conNo.fuente()).isEqualTo(FuenteDelVeredicto.TESTIGO);
		// El «sí» del testigo cierra la condición aunque el número declarado diga otra cosa.
		assertThat(conSi.resultado()).isEqualTo(ResultadoDeCondicion.CUMPLIDA);
		assertThat(conSi.fuente()).isEqualTo(FuenteDelVeredicto.TESTIGO);
		assertThat(List.of(sinNada, conNo, conSi)).noneMatch(Veredicto::paraElModelo);
	}

	@Test
	@Tag("PB-36")
	@DisplayName("PB-36 · sin condiciones cumplidas el plan es la respuesta básica: una unidad de cualquier tipo")
	void planBasico() {
		PlanDeRespuesta plan = JuicioDelProtocolo.plan(CHOQUE, JuicioDelProtocolo.porReglas(CHOQUE, hechos(false)));

		assertThat(plan.unidades()).isEqualTo(1);
		assertThat(plan.tipoMinimo()).isNull();
		assertThat(plan.porCondiciones()).isEmpty();
	}

	@Test
	@Tag("PB-36")
	@DisplayName("PB-36 · entre varias condiciones cumplidas vale la que más unidades pide, sin sumarlas, y el tipo de mayor capacidad")
	void planConVariasCondiciones() {
		PlanDeRespuesta plan = JuicioDelProtocolo.plan(CHOQUE,
				List.of(cumplida(VARIOS), cumplida(ATRAPADO), cumplida(GRAVE), cumplida(FUEGO)));

		assertThat(plan.unidades()).isEqualTo(3);
		assertThat(plan.tipoMinimo()).isEqualTo(TipoUnidad.III);
		// La que solo deriva a una persona no pide recursos: no figura como motivo del plan.
		assertThat(plan.porCondiciones()).containsExactly(VARIOS, ATRAPADO, GRAVE);
	}

	@Test
	@Tag("PB-36")
	@DisplayName("PB-36 · una condición en duda o descartada no suma nada al plan")
	void soloLasCumplidasSuman() {
		List<Veredicto> porReglas = JuicioDelProtocolo.porReglas(CHOQUE, hechos(1, false));
		List<Veredicto> finales = JuicioDelProtocolo.conModelo(CHOQUE, porReglas,
				Map.of(ATRAPADO, 0.6, GRAVE, 0.9, FUEGO, 0.1));

		PlanDeRespuesta plan = JuicioDelProtocolo.plan(CHOQUE, finales);

		assertThat(plan.unidades()).isEqualTo(1);
		assertThat(plan.tipoMinimo()).isEqualTo(TipoUnidad.III);
		assertThat(plan.porCondiciones()).containsExactly(GRAVE);
	}

}
