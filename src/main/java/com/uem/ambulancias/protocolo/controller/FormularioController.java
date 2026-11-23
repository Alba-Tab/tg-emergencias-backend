package com.uem.ambulancias.protocolo.controller;

import java.util.List;

import com.uem.ambulancias.protocolo.dto.PreguntaResponse;
import com.uem.ambulancias.protocolo.dto.TipoDeSucesoResponse;
import com.uem.ambulancias.protocolo.service.ProtocoloService;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lo que la app le muestra a quien alerta, sacado del protocolo vigente: qué tipos de suceso puede marcar y qué se
 * le pregunta después para cada uno. Mientras la central no publique un protocolo, la lista de tipos viene vacía y
 * la alerta sigue funcionando sin tipo.
 */
@RestController
@RequestMapping("/formulario")
@RequiredArgsConstructor
public class FormularioController {

	private final ProtocoloService protocoloService;

	@GetMapping("/tipos")
	public List<TipoDeSucesoResponse> tipos() {
		return protocoloService.tiposVigentes().stream().map(TipoDeSucesoResponse::de).toList();
	}

	@GetMapping("/tipos/{codigo}/preguntas")
	public List<PreguntaResponse> preguntas(@PathVariable String codigo) {
		return protocoloService.preguntasDe(codigo).stream().map(PreguntaResponse::de).toList();
	}

}
