package com.uem.ambulancias.emergencias.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.uem.ambulancias.comun.geo.Geo;
import com.uem.ambulancias.emergencias.domain.EventoDelCaso;
import com.uem.ambulancias.emergencias.domain.Incidente;
import com.uem.ambulancias.emergencias.domain.OrigenDelEvento;
import com.uem.ambulancias.emergencias.domain.TipoEventoDelCaso;

@Tag("PB-32")
class BitacoraDelCasoTest {

	private static final Instant AHORA = Instant.parse("2026-11-22T15:00:00Z");

	@Test
	@DisplayName("PB-32 · en los datos de un evento, lo que no se sabe no figura")
	void datosSinNulos() {
		Map<String, Object> datos = BitacoraDelCaso.datos("cantidadAfectados", 2, "descripcion", null, "nuevo", true);

		assertThat(datos).containsExactly(Map.entry("cantidadAfectados", 2), Map.entry("nuevo", true));
		assertThat(BitacoraDelCaso.datos()).isEmpty();
	}

	@Test
	@DisplayName("PB-32 · los datos van de a pares clave, valor")
	void datosDeAPares() {
		assertThatThrownBy(() -> BitacoraDelCaso.datos("cantidadAfectados"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("PB-32 · un renglón guarda qué pasó, quién lo aportó y cuándo")
	void renglon() {
		Incidente incidente = Incidente.crear(Geo.punto(-17.78, -63.18), null, AHORA);

		EventoDelCaso evento = EventoDelCaso.anotar(incidente, null, TipoEventoDelCaso.EVIDENCIA_ANALIZADA,
				OrigenDelEvento.ANALISIS, "{\"evidenciaId\":7}", AHORA, AHORA.plusSeconds(2));

		assertThat(evento.getIncidente()).isSameAs(incidente);
		assertThat(evento.getAlerta()).isNull();
		assertThat(evento.getTipo()).isEqualTo(TipoEventoDelCaso.EVIDENCIA_ANALIZADA);
		assertThat(evento.getOrigen()).isEqualTo(OrigenDelEvento.ANALISIS);
		assertThat(evento.getDatos()).isEqualTo("{\"evidenciaId\":7}");
		assertThat(evento.getOcurridoEn()).isEqualTo(AHORA);
		assertThat(evento.getRegistradoEn()).isEqualTo(AHORA.plusSeconds(2));
	}

}
