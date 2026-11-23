package com.uem.ambulancias.coordinacion.domain;

import static com.uem.ambulancias.soporte.Conflictos.rechazaCon;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.uem.ambulancias.comun.error.CodigoError;
import com.uem.ambulancias.comun.geo.Geo;
import com.uem.ambulancias.emergencias.domain.Incidente;

/** Lo que el sistema le pasa a una persona: se contesta una sola vez, y si nadie contesta vale la opción segura. */
@Tag("PB-38")
class DerivacionTest {

	private static final Instant AHORA = Instant.parse("2026-11-22T15:00:00Z");
	private static final Duration ESPERA = Duration.ofSeconds(60);

	private static Derivacion abierta(MotivoDeDerivacion motivo) {
		Incidente incidente = Incidente.crear(Geo.punto(-17.78329, -63.18210), null, AHORA);
		return Derivacion.abrir(incidente, motivo, motivo.name() + ":PERSONA_ATRAPADA", "PERSONA_ATRAPADA", "{}", null,
				AHORA, ESPERA);
	}

	@Test
	@DisplayName("PB-38 · una derivación nace abierta, con su tiempo límite, y necesita la atención de una persona")
	void naceAbierta() {
		Derivacion derivacion = abierta(MotivoDeDerivacion.CONDICION_SIN_RESOLVER);

		assertThat(derivacion.isAbierta()).isTrue();
		assertThat(derivacion.necesitaAtencion()).isTrue();
		assertThat(derivacion.getVenceEn()).isEqualTo(AHORA.plus(ESPERA));
		assertThat(derivacion.vencio(AHORA.plusSeconds(59))).isFalse();
		assertThat(derivacion.vencio(AHORA.plusSeconds(60))).isTrue();
	}

	@Test
	@DisplayName("PB-38 · cada motivo trae sus opciones y una opción segura que está entre ellas")
	void opcionesDeCadaMotivo() {
		assertThat(MotivoDeDerivacion.values()).allSatisfy(motivo -> {
			assertThat(motivo.getOpciones()).isNotEmpty();
			assertThat(motivo.admite(motivo.getOpcionSegura())).isTrue();
		});
		// Ante la duda se atiende como el caso más grave; lo que propone el agente no se hace sin una persona.
		assertThat(MotivoDeDerivacion.CONDICION_SIN_RESOLVER.getOpcionSegura()).isEqualTo(OpcionDeDerivacion.SE_CUMPLE);
		assertThat(MotivoDeDerivacion.PROPUESTA_DEL_AGENTE.getOpcionSegura())
				.isEqualTo(OpcionDeDerivacion.RECHAZAR_PROPUESTA);
		assertThat(MotivoDeDerivacion.SIN_UNIDAD.getOpcionSegura()).isEqualTo(OpcionDeDerivacion.SEGUIR_ESPERANDO);
	}

	@Test
	@DisplayName("PB-38 · la persona contesta con una opción del motivo, una sola vez, y deja de necesitar atención")
	void seContestaUnaVez() {
		Derivacion derivacion = abierta(MotivoDeDerivacion.CONDICION_SIN_RESOLVER);

		rechazaCon(CodigoError.OPCION_NO_PERMITIDA,
				() -> derivacion.resolver(OpcionDeDerivacion.PEDIR_APOYO_EXTERNO, null, null, AHORA));
		derivacion.resolver(OpcionDeDerivacion.NO_SE_CUMPLE, null, "Lo confirmé por teléfono", AHORA.plusSeconds(20));

		assertThat(derivacion.getEstado()).isEqualTo(EstadoDerivacion.RESUELTA);
		assertThat(derivacion.getOpcionElegida()).isEqualTo(OpcionDeDerivacion.NO_SE_CUMPLE);
		assertThat(derivacion.getNota()).isEqualTo("Lo confirmé por teléfono");
		assertThat(derivacion.necesitaAtencion()).isFalse();
		rechazaCon(CodigoError.DERIVACION_CERRADA,
				() -> derivacion.resolver(OpcionDeDerivacion.SE_CUMPLE, null, null, AHORA));
		rechazaCon(CodigoError.DERIVACION_CERRADA, () -> derivacion.vencer(AHORA));
	}

	@Test
	@DisplayName("PB-38 · si nadie contesta queda la opción segura, y sigue a la vista hasta que alguien la marca como vista")
	void venceConLaOpcionSegura() {
		Derivacion derivacion = abierta(MotivoDeDerivacion.CONDICION_SIN_RESOLVER);

		assertThat(derivacion.vencer(AHORA.plus(ESPERA))).isEqualTo(OpcionDeDerivacion.SE_CUMPLE);

		assertThat(derivacion.getEstado()).isEqualTo(EstadoDerivacion.VENCIDA);
		assertThat(derivacion.getOpcionElegida()).isEqualTo(OpcionDeDerivacion.SE_CUMPLE);
		assertThat(derivacion.necesitaAtencion()).isTrue();
		rechazaCon(CodigoError.DERIVACION_CERRADA,
				() -> derivacion.resolver(OpcionDeDerivacion.NO_SE_CUMPLE, null, null, AHORA));

		derivacion.marcarVista(null, AHORA.plusSeconds(90));

		assertThat(derivacion.getVistaEn()).isEqualTo(AHORA.plusSeconds(90));
		assertThat(derivacion.necesitaAtencion()).isFalse();
		// Lo ya aplicado no cambia.
		assertThat(derivacion.getOpcionElegida()).isEqualTo(OpcionDeDerivacion.SE_CUMPLE);
	}

	@Test
	@DisplayName("PB-38 · una derivación retirada ya no necesita atención y no se puede marcar como vista")
	void retirada() {
		Derivacion derivacion = abierta(MotivoDeDerivacion.SIN_UNIDAD);

		derivacion.retirar("incidente_cerrado", AHORA.plusSeconds(5));

		assertThat(derivacion.getEstado()).isEqualTo(EstadoDerivacion.RETIRADA);
		assertThat(derivacion.necesitaAtencion()).isFalse();
		assertThat(derivacion.getNota()).isEqualTo("incidente_cerrado");
		rechazaCon(CodigoError.DERIVACION_CERRADA, () -> derivacion.marcarVista(null, AHORA));
	}

}
