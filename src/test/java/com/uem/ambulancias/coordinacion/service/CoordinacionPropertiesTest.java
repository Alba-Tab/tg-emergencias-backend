package com.uem.ambulancias.coordinacion.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("PB-35")
class CoordinacionPropertiesTest {

	@Test
	@DisplayName("PB-35 · cada consulta al modelo espera el doble que la anterior, desde 5 s y sin pasar de 30")
	void esperaCorta() {
		CoordinacionProperties config = new CoordinacionProperties(3, 5, 30, 90);

		assertThat(config.esperaTras(1)).isEqualTo(Duration.ofSeconds(5));
		assertThat(config.esperaTras(2)).isEqualTo(Duration.ofSeconds(10));
		assertThat(config.esperaTras(3)).isEqualTo(Duration.ofSeconds(20));
		assertThat(config.esperaTras(4)).isEqualTo(Duration.ofSeconds(30));
		assertThat(config.esperaTras(40)).isEqualTo(Duration.ofSeconds(30));
		assertThat(config.bloqueo()).isEqualTo(Duration.ofSeconds(90));
	}

}
