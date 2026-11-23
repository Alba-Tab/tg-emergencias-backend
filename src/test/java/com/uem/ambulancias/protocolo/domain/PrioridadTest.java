package com.uem.ambulancias.protocolo.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Las prioridades del triaje telefónico: verde, amarilla y roja, de la menos urgente a la más urgente. */
@Tag("PB-43")
class PrioridadTest {

	@Test
	@DisplayName("PB-43 · cada prioridad lleva el código de la norma: P1 roja, P2 amarilla y P3 verde")
	void codigosDeLaNorma() {
		assertThat(Prioridad.ROJA.getCodigo()).isEqualTo("P1");
		assertThat(Prioridad.AMARILLA.getCodigo()).isEqualTo("P2");
		assertThat(Prioridad.VERDE.getCodigo()).isEqualTo("P3");
	}

	@Test
	@DisplayName("PB-43 · la roja urge más que la amarilla, y la amarilla más que la verde")
	void orden() {
		assertThat(Prioridad.ROJA.masUrgenteQue(Prioridad.AMARILLA)).isTrue();
		assertThat(Prioridad.AMARILLA.masUrgenteQue(Prioridad.VERDE)).isTrue();
		assertThat(Prioridad.VERDE.masUrgenteQue(Prioridad.AMARILLA)).isFalse();
		assertThat(Prioridad.ROJA.masUrgenteQue(Prioridad.ROJA)).isFalse();
	}

	@Test
	@DisplayName("PB-43 · un caso sin prioridad vale lo mismo que uno verde: no urge más que ninguno")
	void sinPrioridad() {
		assertThat(Prioridad.nivelDe(null)).isEqualTo(Prioridad.nivelDe(Prioridad.VERDE));
		assertThat(Prioridad.AMARILLA.masUrgenteQue(null)).isTrue();
		assertThat(Prioridad.VERDE.masUrgenteQue(null)).isFalse();
	}

	@Test
	@DisplayName("PB-43 · entre dos prioridades vale la más urgente, y si falta una vale la otra")
	void laMayor() {
		assertThat(Prioridad.laMayor(Prioridad.AMARILLA, Prioridad.ROJA)).isEqualTo(Prioridad.ROJA);
		assertThat(Prioridad.laMayor(Prioridad.ROJA, Prioridad.VERDE)).isEqualTo(Prioridad.ROJA);
		assertThat(Prioridad.laMayor(null, Prioridad.VERDE)).isEqualTo(Prioridad.VERDE);
		assertThat(Prioridad.laMayor(Prioridad.AMARILLA, null)).isEqualTo(Prioridad.AMARILLA);
		assertThat(Prioridad.laMayor(null, null)).isNull();
	}

}
