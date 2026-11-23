package com.uem.ambulancias.memoria.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.uem.ambulancias.memoria.service.TextosDeLaNorma.Extracto;

import tools.jackson.databind.json.JsonMapper;

/** Los extractos de las normas que vienen con el servidor: se pueden leer, citar y convertir en vectores. */
@Tag("PB-44")
class TextosDeLaNormaTest {

	private final List<Extracto> extractos = new TextosDeLaNorma(JsonMapper.builder().build()).todos();

	@Test
	@DisplayName("PB-44 · vienen extractos de las dos normas, cada uno con su documento, su referencia y su sección")
	void hayExtractosDeLasDosNormas() {
		assertThat(extractos).hasSizeGreaterThanOrEqualTo(15);
		assertThat(extractos).extracting(Extracto::documento)
				.contains("NORMA_AMBULANCIAS_TERRESTRES", "NORMA_SISTEMA_DE_EMERGENCIAS_EN_SALUD");
		assertThat(extractos).allSatisfy(extracto -> {
			assertThat(extracto.titulo()).isNotBlank();
			assertThat(extracto.referencia()).isNotBlank();
			assertThat(extracto.seccion()).isNotBlank();
			assertThat(extracto.texto()).hasSizeBetween(100, TextosDeLaNorma.LARGO_MAXIMO);
		});
	}

	@Test
	@DisplayName("PB-44 · cada extracto tiene una huella propia, que cambia si cambia su texto")
	void huellas() {
		assertThat(extractos).extracting(Extracto::huella).doesNotHaveDuplicates();
		assertThat(TextosDeLaNorma.huella("DOC", "1", "Texto"))
				.hasSize(64)
				.isEqualTo(TextosDeLaNorma.huella("DOC", "1", "Texto"))
				.isNotEqualTo(TextosDeLaNorma.huella("DOC", "1", "Texto."));
	}

	@Test
	@DisplayName("PB-44 · están las prioridades del triaje telefónico y el tipo de ambulancia según la gravedad")
	void loQueUsaElDespacho() {
		assertThat(extractos).anySatisfy(extracto -> assertThat(extracto.texto())
				.contains("P1 (máxima), código rojo").contains("P4, código negro"));
		assertThat(extractos).anySatisfy(extracto -> assertThat(extracto.texto())
				.contains("Paciente Rojo o paciente crítico").contains("ambulancia tipo 3"));
	}

}
