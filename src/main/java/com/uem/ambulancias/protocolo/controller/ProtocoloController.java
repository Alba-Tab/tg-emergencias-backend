package com.uem.ambulancias.protocolo.controller;

import java.util.List;

import com.uem.ambulancias.comun.web.UsuarioActual;
import com.uem.ambulancias.protocolo.dto.GuardarProtocoloRequest;
import com.uem.ambulancias.protocolo.dto.ProtocoloResponse;
import com.uem.ambulancias.protocolo.dto.ProtocoloResumenResponse;
import com.uem.ambulancias.protocolo.service.ProtocoloService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** La central mantiene el protocolo de despacho: abre un borrador, lo edita y lo publica. */
@RestController
@RequestMapping("/protocolos")
@RequiredArgsConstructor
public class ProtocoloController {

	private final ProtocoloService protocoloService;

	@GetMapping
	public List<ProtocoloResumenResponse> listar() {
		return protocoloService.listar().stream().map(ProtocoloResumenResponse::de).toList();
	}

	@GetMapping("/vigente")
	public ProtocoloResponse vigente() {
		return ProtocoloResponse.de(protocoloService.vigenteObligatorio());
	}

	@GetMapping("/borrador")
	public ProtocoloResponse borrador() {
		return ProtocoloResponse.de(protocoloService.borrador());
	}

	@GetMapping("/{id}")
	public ProtocoloResponse ver(@PathVariable Long id) {
		return ProtocoloResponse.de(protocoloService.buscar(id));
	}

	@PostMapping("/borrador")
	@ResponseStatus(HttpStatus.CREATED)
	public ProtocoloResponse abrirBorrador(@UsuarioActual Long administradorId) {
		return ProtocoloResponse.de(protocoloService.abrirBorrador(administradorId));
	}

	@PutMapping("/borrador")
	public ProtocoloResponse guardarBorrador(@Valid @RequestBody GuardarProtocoloRequest request) {
		return ProtocoloResponse.de(protocoloService.guardarBorrador(request));
	}

	@PostMapping("/borrador/publicacion")
	public ProtocoloResponse publicarBorrador() {
		return ProtocoloResponse.de(protocoloService.publicarBorrador());
	}

	@DeleteMapping("/borrador")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void descartarBorrador() {
		protocoloService.descartarBorrador();
	}

}
