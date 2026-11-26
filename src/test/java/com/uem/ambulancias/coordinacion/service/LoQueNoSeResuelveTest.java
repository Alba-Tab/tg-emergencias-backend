package com.uem.ambulancias.coordinacion.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.uem.ambulancias.coordinacion.domain.ConclusionDelAgente;
import com.uem.ambulancias.coordinacion.domain.FuenteDelVeredicto;
import com.uem.ambulancias.coordinacion.domain.ResultadoDeCondicion;
import com.uem.ambulancias.coordinacion.service.LoQueNoSeResuelve.Pendiente;
import com.uem.ambulancias.coordinacion.service.LoQueNoSeResuelve.Reparto;
import com.uem.ambulancias.emergencias.domain.RespuestaDelTestigo;
import com.uem.ambulancias.flota.domain.TipoUnidad;
import com.uem.ambulancias.protocolo.domain.Condicion;
import com.uem.ambulancias.protocolo.domain.Prioridad;

/**
 * Qué se hace con una condición que no se resolvió: esperar al testigo, pedírsela al agente o pasársela a una persona,
 * y qué conclusiones del agente se aplican.
 */
@Tag("PB-40")
class LoQueNoSeResuelveTest {

	private static final String ATRAPADA = "PERSONA_ATRAPADA";
	private static final String FUEGO = "HAY_FUEGO";
	private static final Duration ESPERA = Duration.ofSeconds(45);
	private static final Instant AHORA = Instant.parse("2026-11-26T12:00:00Z");

	private static final Condicion CON_PREGUNTA = Condicion.de(ATRAPADA, "Hay una persona atrapada",
			"¿Hay alguien atrapado?", 0.8, 0.4, 1, TipoUnidad.II, false, null);
	private static final Condicion SIN_PREGUNTA = Condicion.de(FUEGO, "Hay fuego", null, 0.8, 0.4, 0, null, false,
			null);

	private static Veredicto veredicto(String condicion, ResultadoDeCondicion resultado, RespuestaDelTestigo respuesta) {
		return new Veredicto(condicion, resultado, resultado == ResultadoDeCondicion.EN_DUDA
				? FuenteDelVeredicto.MODELO : null, resultado == ResultadoDeCondicion.EN_DUDA ? 0.5 : null, respuesta,
				true);
	}

	private static Pendiente esperando(String codigo) {
		return new Pendiente(codigo, LoQueNoSeResuelve.EL_TESTIGO_NO_CONTESTO, AHORA.plusSeconds(30));
	}

	private static Pendiente vencida(String codigo) {
		return new Pendiente(codigo, LoQueNoSeResuelve.EL_TESTIGO_NO_CONTESTO, null);
	}

	@Test
	@Tag("PB-38")
	@DisplayName("PB-38 · una condición con pregunta y sin respuesta espera al testigo hasta su plazo; después, no contestó")
	void esperaAlTestigoHastaSuPlazo() {
		Veredicto enDuda = veredicto(ATRAPADA, ResultadoDeCondicion.EN_DUDA, null);
		Instant preguntada = AHORA.minusSeconds(10);

		Pendiente aTiempo = LoQueNoSeResuelve.pendiente(CON_PREGUNTA, enDuda, false, preguntada, AHORA, ESPERA);
		Pendiente tarde = LoQueNoSeResuelve.pendiente(CON_PREGUNTA, enDuda, false, preguntada, AHORA.plusSeconds(40),
				ESPERA);

		assertThat(aTiempo.esperaHasta()).isEqualTo(preguntada.plus(ESPERA));
		assertThat(aTiempo.porQue()).isEqualTo(LoQueNoSeResuelve.EL_TESTIGO_NO_CONTESTO);
		assertThat(tarde.esperaAlTestigo()).isFalse();
		assertThat(tarde.porQue()).isEqualTo(LoQueNoSeResuelve.EL_TESTIGO_NO_CONTESTO);
		// Si no consta cuándo se le preguntó, el plazo corre desde ahora.
		assertThat(LoQueNoSeResuelve.pendiente(CON_PREGUNTA, enDuda, false, null, AHORA, ESPERA).esperaHasta())
				.isEqualTo(AHORA.plus(ESPERA));
	}

	@Test
	@Tag("PB-38")
	@DisplayName("PB-38 · un «no sé» sin ninguna evidencia ya no queda en duda sin nada: queda sin resolver porque el testigo no sabe")
	void elNoSeSinEvidenciaQuedaSinResolver() {
		Pendiente enDuda = LoQueNoSeResuelve.pendiente(CON_PREGUNTA,
				veredicto(ATRAPADA, ResultadoDeCondicion.EN_DUDA, RespuestaDelTestigo.NO_SE), false, AHORA, AHORA, ESPERA);
		Pendiente sinModelo = LoQueNoSeResuelve.pendiente(CON_PREGUNTA,
				veredicto(ATRAPADA, ResultadoDeCondicion.SIN_EVALUAR, RespuestaDelTestigo.NO_SE), false, AHORA, AHORA,
				ESPERA);

		assertThat(enDuda).isEqualTo(new Pendiente(ATRAPADA, LoQueNoSeResuelve.EL_TESTIGO_NO_SABE, null));
		assertThat(sinModelo).isEqualTo(new Pendiente(ATRAPADA, LoQueNoSeResuelve.EL_TESTIGO_NO_SABE, null));
	}

	@Test
	@Tag("PB-38")
	@DisplayName("PB-38 · sin pregunta, queda sin resolver si el modelo la dejó en duda o hay evidencia; sin evaluar y sin nada, no")
	void sinPregunta() {
		Veredicto enDuda = veredicto(FUEGO, ResultadoDeCondicion.EN_DUDA, null);
		Veredicto sinEvaluar = veredicto(FUEGO, ResultadoDeCondicion.SIN_EVALUAR, null);

		assertThat(LoQueNoSeResuelve.pendiente(SIN_PREGUNTA, enDuda, false, null, AHORA, ESPERA))
				.isEqualTo(new Pendiente(FUEGO, LoQueNoSeResuelve.SIN_PREGUNTA_PARA_EL_TESTIGO, null));
		assertThat(LoQueNoSeResuelve.pendiente(SIN_PREGUNTA, sinEvaluar, true, null, AHORA, ESPERA))
				.isEqualTo(new Pendiente(FUEGO, LoQueNoSeResuelve.SIN_PREGUNTA_PARA_EL_TESTIGO, null));
		assertThat(LoQueNoSeResuelve.pendiente(SIN_PREGUNTA, sinEvaluar, false, null, AHORA, ESPERA)).isNull();
	}

	@Test
	@Tag("PB-38")
	@DisplayName("PB-38 · lo que ya se cumple o ya se descartó no está pendiente")
	void loResueltoNoEstaPendiente() {
		assertThat(LoQueNoSeResuelve.pendiente(CON_PREGUNTA,
				veredicto(ATRAPADA, ResultadoDeCondicion.CUMPLIDA, null), true, null, AHORA, ESPERA)).isNull();
		assertThat(LoQueNoSeResuelve.pendiente(CON_PREGUNTA,
				veredicto(ATRAPADA, ResultadoDeCondicion.NO_CUMPLIDA, null), true, null, AHORA, ESPERA)).isNull();
	}

	@Test
	@DisplayName("PB-40 · con el agente a mano, lo que espera al testigo se le pide igual, sin esperar el plazo, y no va a nadie")
	void elAgenteRazonaMientrasSeEsperaAlTestigo() {
		Pendiente espera = esperando(ATRAPADA);
		Pendiente sinPregunta = new Pendiente(FUEGO, LoQueNoSeResuelve.SIN_PREGUNTA_PARA_EL_TESTIGO, null);

		Reparto reparto = LoQueNoSeResuelve.repartir(List.of(espera, sinPregunta), Set.of(), true);

		assertThat(reparto.aRazonar()).containsExactly(espera, sinPregunta);
		assertThat(reparto.aDerivar()).isEmpty();
		assertThat(reparto.proximaRevision()).isEqualTo(espera.esperaHasta());
	}

	@Test
	@DisplayName("PB-40 · lo que el agente ya miró sin datos nuevos no se le vuelve a pedir: espera al testigo y, vencido el plazo, va a una persona")
	void loYaMiradoNoSeVuelveAPedir() {
		Reparto antes = LoQueNoSeResuelve.repartir(List.of(esperando(ATRAPADA)), Set.of(ATRAPADA), true);
		Reparto despues = LoQueNoSeResuelve.repartir(List.of(vencida(ATRAPADA)), Set.of(ATRAPADA), true);

		assertThat(antes.aRazonar()).isEmpty();
		assertThat(antes.aDerivar()).isEmpty();
		assertThat(antes.proximaRevision()).isNotNull();
		assertThat(despues.aRazonar()).isEmpty();
		assertThat(despues.aDerivar()).containsExactly(vencida(ATRAPADA));
		assertThat(despues.proximaRevision()).isNull();
	}

	@Test
	@DisplayName("PB-40 · sin agente o sin nada que razonar, lo vencido va a una persona y lo que espera al testigo sigue esperando")
	void sinAgente() {
		Reparto reparto = LoQueNoSeResuelve.repartir(List.of(esperando(ATRAPADA), vencida(FUEGO)), Set.of(), false);

		assertThat(reparto.aRazonar()).isEmpty();
		assertThat(reparto.aDerivar()).containsExactly(vencida(FUEGO));
		assertThat(reparto.proximaRevision()).isEqualTo(esperando(ATRAPADA).esperaHasta());
	}

	@Test
	@DisplayName("PB-40 · el agente entra si hay evidencia, más de una alerta o casos del mismo tipo; la memoria se consulta solo si hace falta")
	void hayAlgoQueRazonar() {
		AtomicBoolean consultada = new AtomicBoolean();

		assertThat(LoQueNoSeResuelve.hayAlgoQueRazonar(false, 1, () -> {
			consultada.set(true);
			return false;
		})).isFalse();
		assertThat(consultada).isTrue();
		assertThat(LoQueNoSeResuelve.hayAlgoQueRazonar(false, 1, () -> true)).isTrue();
		assertThat(LoQueNoSeResuelve.hayAlgoQueRazonar(false, 2, () -> false)).isTrue();
		consultada.set(false);
		assertThat(LoQueNoSeResuelve.hayAlgoQueRazonar(true, 1, () -> {
			consultada.set(true);
			return false;
		})).isTrue();
		assertThat(consultada).isFalse();
	}

	@Test
	@DisplayName("PB-40 · en una condición roja el agente solo confirma: su «no se cumple» no se aplica y la decide una persona")
	void enUnaRojaSoloConfirma() {
		assertThat(LoQueNoSeResuelve.seAplica(ConclusionDelAgente.CUMPLE, Prioridad.ROJA)).isTrue();
		assertThat(LoQueNoSeResuelve.seAplica(ConclusionDelAgente.NO_CUMPLE, Prioridad.ROJA)).isFalse();
		assertThat(LoQueNoSeResuelve.seAplica(ConclusionDelAgente.NO_CUMPLE, Prioridad.AMARILLA)).isTrue();
		assertThat(LoQueNoSeResuelve.seAplica(ConclusionDelAgente.NO_CUMPLE, null)).isTrue();
		assertThat(LoQueNoSeResuelve.seAplica(ConclusionDelAgente.NO_SE_SABE, null)).isFalse();
		assertThat(LoQueNoSeResuelve.porQueNoSeAplica(ConclusionDelAgente.NO_CUMPLE, "EL_TESTIGO_NO_SABE"))
				.isEqualTo(LoQueNoSeResuelve.EL_AGENTE_NO_LA_CONFIRMA);
		assertThat(LoQueNoSeResuelve.porQueNoSeAplica(ConclusionDelAgente.NO_SE_SABE, "EL_TESTIGO_NO_SABE"))
				.isEqualTo("EL_TESTIGO_NO_SABE");
	}

	@Test
	@DisplayName("PB-40 · el testigo tiene tiempo de contestar solo antes de que venza su plazo")
	void plazoDelTestigo() {
		assertThat(LoQueNoSeResuelve.esperaAlTestigo(AHORA.plusSeconds(1), AHORA)).isTrue();
		assertThat(LoQueNoSeResuelve.esperaAlTestigo(AHORA, AHORA)).isFalse();
		assertThat(LoQueNoSeResuelve.esperaAlTestigo(null, AHORA)).isFalse();
	}

}
