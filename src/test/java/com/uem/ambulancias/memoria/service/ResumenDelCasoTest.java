package com.uem.ambulancias.memoria.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.uem.ambulancias.protocolo.domain.Condicion;
import com.uem.ambulancias.protocolo.domain.TipoDeSuceso;

/** El resumen de un caso que se convierte en vector: la situación en frases, sin códigos y sin cómo terminó. */
@Tag("PB-44")
class ResumenDelCasoTest {

	private static final TipoDeSuceso ACCIDENTE = TipoDeSuceso.de("ACCIDENTE_DE_TRANSITO", "Accidente de tránsito",
			List.of(Condicion.de("PERSONA_ATRAPADA", "Hay una persona atrapada", "¿Hay alguien atrapado?", 0.8, 0.4, 1,
					null, false, null),
					Condicion.de("NO_RESPIRA", "Hay una persona que no respira", null, 0.6, 0.3, 0, null, false, null)));

	@Test
	@DisplayName("PB-44 · el resumen dice el tipo de suceso, los afectados y lo que contestó el testigo con el enunciado de cada condición")
	void loQueDijoElTestigo() {
		Map<String, Object> estado = new LinkedHashMap<>();
		estado.put("afectadosDeclarados", 2);
		estado.put("respuestasDelTestigo", Map.of("PERSONA_ATRAPADA", "SI"));

		String texto = ResumenDelCaso.redactar(ACCIDENTE, estado);

		assertThat(texto)
				.startsWith("Tipo de suceso: Accidente de tránsito.")
				.contains("Personas afectadas declaradas: 2.")
				.contains("El testigo contestó «sí» a: Hay una persona atrapada.")
				.doesNotContain("PERSONA_ATRAPADA");
	}

	@Test
	@DisplayName("PB-44 · entran las condiciones que ya se decidieron y lo que describió el análisis de las evidencias")
	void decididasYEvidencias() {
		Map<String, Object> estado = new LinkedHashMap<>();
		estado.put("respuestasDelTestigo", Map.of());
		estado.put("condicionesDecididas", Map.of("NO_RESPIRA", Map.of("cumple", false, "por", "CENTRAL")));
		estado.put("evidencias", List.of(Map.of("modalidad", "IMAGEN", "resumen", "Un auto volcado en la avenida",
				"peligros", List.of("derrame de combustible"))));
		estado.put("resumenDelIncidente", Map.of("situacion", "Choque entre dos vehículos", "personas", 3));

		String texto = ResumenDelCaso.redactar(ACCIDENTE, estado);

		assertThat(texto)
				.contains("Se descartó: Hay una persona que no respira.")
				.contains("Un auto volcado en la avenida")
				.contains("derrame de combustible")
				.contains("Resumen del incidente:")
				.contains("Choque entre dos vehículos")
				.contains("personas 3");
	}

	@Test
	@DisplayName("PB-44 · una condición que el protocolo ya no tiene va con su código en palabras, y el resumen nunca pasa del largo máximo")
	void bordes() {
		Map<String, Object> estado = new LinkedHashMap<>();
		estado.put("respuestasDelTestigo", Map.of("HAY_FUEGO", "NO_SE"));
		estado.put("evidencias", List.of(Map.of("resumen", "x".repeat(TextosDeLaNorma.LARGO_MAXIMO * 2))));

		String texto = ResumenDelCaso.redactar(ACCIDENTE, estado);

		assertThat(texto).contains("El testigo contestó «no sabe» a: hay fuego.");
		assertThat(texto).hasSize(TextosDeLaNorma.LARGO_MAXIMO);
	}

}
