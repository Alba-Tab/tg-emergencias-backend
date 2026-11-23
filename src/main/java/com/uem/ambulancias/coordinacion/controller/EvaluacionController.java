package com.uem.ambulancias.coordinacion.controller;

import java.util.List;

import com.uem.ambulancias.coordinacion.dto.EvaluacionResponse;
import com.uem.ambulancias.coordinacion.service.EvaluacionDelCasoService;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/incidentes")
@RequiredArgsConstructor
public class EvaluacionController {

	private final EvaluacionDelCasoService evaluaciones;

	/**
	 * Las evaluaciones de las condiciones del incidente, de la más vieja a la más nueva: con qué datos se evaluó cada
	 * vez, qué veredicto tuvo cada condición y de dónde salió. 404 si el incidente no existe.
	 */
	@GetMapping("/{id}/evaluaciones")
	public List<EvaluacionResponse> evaluaciones(@PathVariable("id") Long idIncidente) {
		return evaluaciones.deIncidente(idIncidente).stream().map(EvaluacionResponse::de).toList();
	}

}
