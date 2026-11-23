package com.uem.ambulancias.emergencias.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.uem.ambulancias.comun.geo.Geo;
import com.uem.ambulancias.protocolo.domain.Protocolo;
import com.uem.ambulancias.protocolo.domain.TipoDeSuceso;

@Tag("PB-30")
class TipoDeSucesoDelIncidenteTest {

	private static final Instant AHORA = Instant.parse("2026-11-22T15:00:00Z");

	private static Protocolo protocolo(int version) {
		Protocolo protocolo = Protocolo.borrador(version, null, AHORA);
		protocolo.cargar(null, List.of(TipoDeSuceso.de("ACCIDENTE", "Accidente", List.of()),
				TipoDeSuceso.de("CAIDA", "Caída", List.of())));
		return protocolo;
	}

	@Test
	@DisplayName("PB-30 · el incidente se queda con el primer tipo de suceso declarado y con su protocolo")
	void valeElPrimero() {
		Incidente incidente = Incidente.crear(Geo.punto(-17.78, -63.18), null, AHORA);
		Protocolo primero = protocolo(1);
		assertThat(incidente.getTipoSuceso()).isNull();
		assertThat(incidente.getProtocolo()).isNull();

		assertThat(incidente.declararTipo(primero, "ACCIDENTE")).isTrue();
		assertThat(incidente.declararTipo(protocolo(2), "CAIDA")).isFalse();

		assertThat(incidente.getTipoSuceso()).isEqualTo("ACCIDENTE");
		assertThat(incidente.getProtocolo()).isSameAs(primero);
	}

	@Test
	@DisplayName("PB-30 · la alerta guarda el tipo de suceso que marcó quien la emitió")
	void laAlertaGuardaSuTipo() {
		Alerta alerta = Alerta.emitir(null, Geo.punto(-17.78, -63.18), OrigenUbicacion.GPS, null, null, AHORA);
		assertThat(alerta.getTipoSuceso()).isNull();

		alerta.declararTipo("CAIDA");

		assertThat(alerta.getTipoSuceso()).isEqualTo("CAIDA");
	}

}
