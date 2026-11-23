package com.uem.ambulancias.memoria.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Guardar y comparar embeddings: lo mínimo para buscar por parecido sin una base de datos de vectores. */
@Tag("PB-44")
class VectoresTest {

	@Test
	@DisplayName("PB-44 · un vector guardado como bytes se recupera igual")
	void idaYVuelta() {
		float[] vector = {0.25f, -1.5f, 0f, 3.75e-4f, Float.MAX_VALUE};

		byte[] bytes = Vectores.aBytes(vector);

		assertThat(bytes).hasSize(vector.length * 4);
		assertThat(Vectores.deBytes(bytes)).containsExactly(vector);
	}

	@Test
	@DisplayName("PB-44 · el coseno es 1 para el mismo sentido, 0 para vectores que no tienen nada que ver y -1 para el opuesto")
	void coseno() {
		assertThat(Vectores.coseno(new float[] {1, 2, 3}, new float[] {2, 4, 6})).isCloseTo(1.0, within(1e-9));
		assertThat(Vectores.coseno(new float[] {1, 0}, new float[] {0, 5})).isCloseTo(0.0, within(1e-9));
		assertThat(Vectores.coseno(new float[] {1, 1}, new float[] {-1, -1})).isCloseTo(-1.0, within(1e-9));
		assertThat(Vectores.coseno(new float[] {1, 0}, new float[] {1, 1})).isCloseTo(Math.sqrt(0.5), within(1e-6));
	}

	@Test
	@DisplayName("PB-44 · dos vectores de distinto tamaño, o uno todo en cero, no se parecen")
	void noComparables() {
		assertThat(Vectores.coseno(new float[] {1, 2}, new float[] {1, 2, 3})).isZero();
		assertThat(Vectores.coseno(new float[] {0, 0}, new float[] {1, 2})).isZero();
		assertThat(Vectores.coseno(new float[0], new float[0])).isZero();
	}

}
