package com.uem.ambulancias.emergencias.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.uem.ambulancias.comun.geo.Geo;
import com.uem.ambulancias.flota.domain.TipoUnidad;

/** Lo que un incidente necesita solo sube: el sistema suma recursos, y quitarlos lo decide una persona. */
@Tag("PB-36")
class RequerimientoDelIncidenteTest {

	private static Incidente incidente() {
		return Incidente.crear(Geo.punto(-17.78329, -63.18210), null, Instant.parse("2026-11-22T15:00:00Z"));
	}

	@Test
	@DisplayName("PB-36 · un incidente nace necesitando una unidad de cualquier tipo")
	void naceConUnaUnidadCualquiera() {
		Incidente incidente = incidente();

		assertThat(incidente.getUnidadesRequeridas()).isEqualTo(1);
		assertThat(incidente.getTipoMinimoRequerido()).isNull();
	}

	@Test
	@DisplayName("PB-36 · pedir más unidades o un tipo de más capacidad sube lo que el incidente necesita")
	void sube() {
		Incidente incidente = incidente();

		assertThat(incidente.requerir(2, TipoUnidad.II)).isTrue();
		assertThat(incidente.getUnidadesRequeridas()).isEqualTo(2);
		assertThat(incidente.getTipoMinimoRequerido()).isEqualTo(TipoUnidad.II);

		assertThat(incidente.requerir(1, TipoUnidad.III)).isTrue();
		assertThat(incidente.getUnidadesRequeridas()).isEqualTo(2);
		assertThat(incidente.getTipoMinimoRequerido()).isEqualTo(TipoUnidad.III);
	}

	@Test
	@DisplayName("PB-36 · pedir menos unidades, un tipo de menor capacidad o lo mismo no cambia nada")
	void nuncaBaja() {
		Incidente incidente = incidente();
		incidente.requerir(3, TipoUnidad.III);

		assertThat(incidente.requerir(1, null)).isFalse();
		assertThat(incidente.requerir(2, TipoUnidad.IA)).isFalse();
		assertThat(incidente.requerir(3, TipoUnidad.III)).isFalse();

		assertThat(incidente.getUnidadesRequeridas()).isEqualTo(3);
		assertThat(incidente.getTipoMinimoRequerido()).isEqualTo(TipoUnidad.III);
	}

}
