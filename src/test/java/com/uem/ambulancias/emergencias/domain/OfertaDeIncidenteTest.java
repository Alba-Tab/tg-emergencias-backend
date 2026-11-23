package com.uem.ambulancias.emergencias.domain;

import static com.uem.ambulancias.soporte.Conflictos.rechazaCon;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.uem.ambulancias.comun.error.CodigoError;
import com.uem.ambulancias.comun.geo.Geo;

@Tag("PB-34")
class OfertaDeIncidenteTest {

	private static final Instant AHORA = Instant.parse("2026-11-22T15:00:00Z");

	private static OfertaDeIncidente pendiente() {
		Incidente incidente = Incidente.crear(Geo.punto(-17.78, -63.18), null, AHORA);
		return OfertaDeIncidente.ofrecer(incidente, null, 1, 1, 6, AHORA, Duration.ofSeconds(30));
	}

	@Test
	@DisplayName("PB-34 · la asignación nace pendiente y vence cuando pasa el tiempo de espera")
	void nacePendiente() {
		OfertaDeIncidente oferta = pendiente();

		assertThat(oferta.getEstado()).isEqualTo(EstadoOferta.PENDIENTE);
		assertThat(oferta.isPendiente()).isTrue();
		assertThat(oferta.getVenceEn()).isEqualTo(AHORA.plusSeconds(30));
		assertThat(oferta.getMinutosDeLlegada()).isEqualTo(6);
		assertThat(oferta.vencio(AHORA.plusSeconds(29))).isFalse();
		assertThat(oferta.vencio(AHORA.plusSeconds(30))).isTrue();
	}

	@Test
	@DisplayName("PB-34 · confirmar vale aunque ya haya pasado el tiempo, mientras nadie la haya dado por vencida")
	void confirma() {
		OfertaDeIncidente oferta = pendiente();

		oferta.confirmar(null, null, AHORA.plusSeconds(40));

		assertThat(oferta.getEstado()).isEqualTo(EstadoOferta.CONFIRMADA);
		assertThat(oferta.getResueltaEn()).isEqualTo(AHORA.plusSeconds(40));
		assertThat(oferta.vencio(AHORA.plusSeconds(60))).isFalse();
	}

	@Test
	@DisplayName("PB-34 · el rechazo guarda el motivo que escribió la tripulación")
	void rechaza() {
		OfertaDeIncidente oferta = pendiente();

		oferta.rechazar(null, "Estamos cargando combustible", AHORA.plusSeconds(5));

		assertThat(oferta.getEstado()).isEqualTo(EstadoOferta.RECHAZADA);
		assertThat(oferta.getMotivoRechazo()).isEqualTo("Estamos cargando combustible");
	}

	@Test
	@DisplayName("PB-34 · una asignación que ya tuvo desenlace no se vuelve a resolver")
	void unSoloDesenlace() {
		OfertaDeIncidente vencida = pendiente();
		vencida.vencer(AHORA.plusSeconds(30));
		assertThat(vencida.getEstado()).isEqualTo(EstadoOferta.VENCIDA);
		rechazaCon(CodigoError.SIN_OFERTA, () -> vencida.confirmar(null, null, AHORA.plusSeconds(31)));
		rechazaCon(CodigoError.SIN_OFERTA, () -> vencida.rechazar(null, "Tarde", AHORA.plusSeconds(31)));

		OfertaDeIncidente retirada = pendiente();
		retirada.retirar(AHORA.plusSeconds(10));
		assertThat(retirada.getEstado()).isEqualTo(EstadoOferta.RETIRADA);
		rechazaCon(CodigoError.SIN_OFERTA, () -> retirada.vencer(AHORA.plusSeconds(30)));
		rechazaCon(CodigoError.SIN_OFERTA, () -> retirada.retirar(AHORA.plusSeconds(30)));
	}

}
