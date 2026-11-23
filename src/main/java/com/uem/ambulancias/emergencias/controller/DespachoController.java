package com.uem.ambulancias.emergencias.controller;

import com.uem.ambulancias.comun.web.UsuarioActual;
import com.uem.ambulancias.emergencias.dto.AjusteDeDespachoResponse;
import com.uem.ambulancias.emergencias.dto.CambiarAjusteDeDespachoRequest;
import com.uem.ambulancias.emergencias.service.AjustesDeDespacho;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** El interruptor del despacho: la central ve cómo se despacha y lo cambia. */
@RestController
@RequestMapping("/despacho")
@RequiredArgsConstructor
public class DespachoController {

	private final AjustesDeDespacho ajustes;

	@GetMapping("/ajustes")
	public AjusteDeDespachoResponse ver() {
		return AjusteDeDespachoResponse.de(ajustes.vigente());
	}

	@PutMapping("/ajustes")
	public AjusteDeDespachoResponse cambiar(@UsuarioActual Long administradorId,
			@Valid @RequestBody CambiarAjusteDeDespachoRequest request) {
		return AjusteDeDespachoResponse.de(
				ajustes.cambiar(administradorId, request.modo(), request.segundosConfirmacion()));
	}

}
