package com.uem.ambulancias.coordinacion.controller;

import java.util.List;

import com.uem.ambulancias.coordinacion.dto.EvaluacionResponse;
import com.uem.ambulancias.coordinacion.dto.RazonamientoResponse;
import com.uem.ambulancias.coordinacion.service.EvaluacionDelCasoService;
import com.uem.ambulancias.coordinacion.service.RazonamientoDelCasoService;

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
	private final RazonamientoDelCasoService razonamientos;

	/**
	 * Las evaluaciones de las condiciones del incidente, de la más vieja a la más nueva: con qué datos se evaluó cada
	 * vez, qué veredicto tuvo cada condición y de dónde salió. 404 si el incidente no existe.
	 */
	@GetMapping("/{id}/evaluaciones")
	public List<EvaluacionResponse> evaluaciones(@PathVariable("id") Long idIncidente) {
		return evaluaciones.deIncidente(idIncidente).stream().map(EvaluacionResponse::de).toList();
	}

	/**
	 * Los razonamientos del agente sobre el incidente, del más viejo al más nuevo: qué condiciones se le pidieron, qué
	 * herramientas usó y a qué llegó en cada una. 404 si el incidente no existe.
	 */
	@GetMapping("/{id}/razonamientos")
	public List<RazonamientoResponse> razonamientos(@PathVariable("id") Long idIncidente) {
		return razonamientos.deIncidente(idIncidente).stream().map(RazonamientoResponse::de).toList();
	}

}
