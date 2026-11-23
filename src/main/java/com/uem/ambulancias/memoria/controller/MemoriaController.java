package com.uem.ambulancias.memoria.controller;

import java.util.List;

import com.uem.ambulancias.memoria.service.IndiceDeLaNorma;
import com.uem.ambulancias.memoria.service.MemoriaDeCasos;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * La búsqueda por parecido: los extractos de las normas y la memoria de casos (PB-44). Todo es de la central.
 */
@RestController
@RequiredArgsConstructor
public class MemoriaController {

	private final IndiceDeLaNorma norma;
	private final MemoriaDeCasos casos;

	/** Cuántos extractos de la norma hay y cuántos se pueden buscar. */
	@GetMapping("/norma")
	public IndiceDeLaNorma.Estado estadoDeLaNorma() {
		return norma.estado();
	}

	/**
	 * Calcula el vector de todos los extractos de la norma. Hay que pedirlo una vez al encender la búsqueda, y de
	 * nuevo si cambian los textos o el modelo. 409 {@code EMBEDDINGS_NO_DISPONIBLES} si la búsqueda está apagada o el
	 * servicio de IA no responde.
	 */
	@PostMapping("/norma/indexacion")
	public IndiceDeLaNorma.Estado indexarLaNorma() {
		return norma.indexar();
	}

	/** Los extractos de la norma más cercanos a la consulta. Vacío si no se indexó o la búsqueda está apagada. */
	@GetMapping("/norma/busqueda")
	public List<IndiceDeLaNorma.Hallazgo> buscarEnLaNorma(@RequestParam String consulta) {
		return norma.buscar(consulta);
	}

	/** Cuántos incidentes cerrados hay en la memoria y cuántos se pueden encontrar por parecido. */
	@GetMapping("/memoria-de-casos")
	public MemoriaDeCasos.Estado estadoDeLaMemoria() {
		return casos.estado();
	}

}
