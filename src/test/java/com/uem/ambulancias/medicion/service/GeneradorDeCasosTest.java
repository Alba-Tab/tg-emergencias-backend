package com.uem.ambulancias.medicion.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.uem.ambulancias.flota.domain.TipoUnidad;
import com.uem.ambulancias.medicion.service.GeneradorDeCasos.Caso;
import com.uem.ambulancias.protocolo.domain.Condicion;
import com.uem.ambulancias.protocolo.domain.TipoDeSuceso;

/** Los casos inventados para medir: se sabe su verdad, se repiten con la misma semilla y tienen ruido como uno real. */
@Tag("PB-41")
class GeneradorDeCasosTest {

	private static final TipoDeSuceso CHOQUE = TipoDeSuceso.de("CHOQUE", "Choque de vehículos", List.of(
			Condicion.de("PERSONA_ATRAPADA", "Hay una persona atrapada.", "¿Hay alguien atrapado?", 0.8, 0.4, 1,
					TipoUnidad.II, false, null),
			Condicion.de("HAY_FUEGO", "Hay fuego o humo", null, 0.8, 0.4, 0, null, true, null),
			Condicion.de("VARIOS_HERIDOS", "Hay más de un herido", null, 0.8, 0.4, 1, null, false, null, 2)));

	@Test
	@DisplayName("PB-41 · con la misma semilla sale el mismo caso, y con otra, otro")
	void seRepiteConLaSemilla() {
		assertThat(GeneradorDeCasos.generar(CHOQUE, 7)).isEqualTo(GeneradorDeCasos.generar(CHOQUE, 7));
		assertThat(java.util.stream.LongStream.range(0, 20).mapToObj(semilla -> GeneradorDeCasos.generar(CHOQUE, semilla))
				.distinct().count()).isGreaterThan(10);
	}

	@Test
	@DisplayName("PB-41 · cada caso dice la verdad de todas las condiciones y trae el estado con la forma del de un incidente")
	@SuppressWarnings("unchecked")
	void formaDelCaso() {
		Caso caso = GeneradorDeCasos.generar(CHOQUE, 3);

		assertThat(caso.verdades()).containsOnlyKeys("PERSONA_ATRAPADA", "HAY_FUEGO", "VARIOS_HERIDOS");
		assertThat((Map<String, Object>) caso.estado().get("tipoDeSuceso")).containsEntry("codigo", "CHOQUE");
		assertThat(caso.estado()).containsKeys("personasQueAlertaron", "respuestasDelTestigo", "evidencias",
				"afectadosDeclarados");
		assertThat((Map<String, String>) caso.estado().get("respuestasDelTestigo"))
				.allSatisfy((condicion, respuesta) -> {
					// Solo se contesta lo que el protocolo pregunta.
					assertThat(condicion).isEqualTo("PERSONA_ATRAPADA");
					assertThat(respuesta).isIn("SI", "NO", "NO_SE");
				});
	}

	@Test
	@DisplayName("PB-41 · la verdad de una condición por cantidad sale de los afectados declarados en el caso")
	void condicionPorCantidad() {
		for (long semilla = 0; semilla < 50; semilla++) {
			Caso caso = GeneradorDeCasos.generar(CHOQUE, semilla);
			int afectados = (Integer) caso.estado().get("afectadosDeclarados");

			assertThat(caso.verdades().get("VARIOS_HERIDOS")).isEqualTo(afectados >= 2);
		}
	}

	@Test
	@DisplayName("PB-41 · en muchos casos hay de todo: condiciones ciertas y falsas, testigos que contestan bien, mal o no saben")
	@SuppressWarnings("unchecked")
	void hayRuido() {
		int ciertas = 0;
		int contestadas = 0;
		int noSabe = 0;
		int equivocadas = 0;
		int conObservaciones = 0;
		for (long semilla = 0; semilla < 400; semilla++) {
			Caso caso = GeneradorDeCasos.generar(CHOQUE, semilla);
			boolean atrapada = caso.verdades().get("PERSONA_ATRAPADA");
			ciertas += atrapada ? 1 : 0;
			String respuesta = ((Map<String, String>) caso.estado().get("respuestasDelTestigo")).get("PERSONA_ATRAPADA");
			if (respuesta != null) {
				contestadas++;
				noSabe += "NO_SE".equals(respuesta) ? 1 : 0;
				equivocadas += (atrapada && "NO".equals(respuesta)) || (!atrapada && "SI".equals(respuesta)) ? 1 : 0;
			}
			conObservaciones += ((List<Map<String, Object>>) caso.estado().get("evidencias")).stream()
					.anyMatch(evidencia -> evidencia.containsKey("observaciones")) ? 1 : 0;
		}

		assertThat(ciertas).isBetween(120, 200);
		assertThat(contestadas).isBetween(200, 280);
		assertThat(noSabe).isBetween(10, 70);
		assertThat(equivocadas).isBetween(1, 30);
		assertThat(conObservaciones).isBetween(80, 320);
	}

}
