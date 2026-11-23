package com.uem.ambulancias.emergencias.controller;

import com.uem.ambulancias.comun.web.UsuarioActual;
import com.uem.ambulancias.emergencias.domain.Atencion;
import com.uem.ambulancias.emergencias.dto.AtencionResponse;
import com.uem.ambulancias.emergencias.dto.OfertaResponse;
import com.uem.ambulancias.emergencias.dto.RechazarOfertaRequest;
import com.uem.ambulancias.emergencias.service.AsignadorDeIncidentes;
import com.uem.ambulancias.emergencias.service.AtencionService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lo que hace la tripulación con el incidente que el sistema le asignó (PB-34): verlo, confirmar que va o decir que
 * no puede. Siempre es la asignación de la unidad en la que está de turno, por eso no viaja ningún id.
 */
@RestController
@RequestMapping("/paramedicos/actual/oferta")
@RequiredArgsConstructor
public class OfertaController {

	private final AsignadorDeIncidentes asignador;
	private final AtencionService atencionService;

	/** La asignación que espera respuesta. 204 si la unidad no tiene ninguna. */
	@GetMapping
	public ResponseEntity<OfertaResponse> pendiente(@UsuarioActual Long paramedicoId) {
		return asignador.pendienteDe(paramedicoId)
				.map(oferta -> ResponseEntity.ok(OfertaResponse.de(oferta)))
				.orElseGet(() -> ResponseEntity.noContent().build());
	}

	/** Confirma: la unidad queda en camino, igual que si hubiera tomado el incidente. */
	@PostMapping("/confirmacion")
	@ResponseStatus(HttpStatus.CREATED)
	public AtencionResponse confirmar(@UsuarioActual Long paramedicoId) {
		Atencion atencion = asignador.confirmar(paramedicoId);
		Long incidenteId = atencion.getIncidente().getId();
		return AtencionResponse.de(atencion, atencionService.emisoresCancelaron(incidenteId),
				atencionService.descripcionesDelIncidente(incidenteId));
	}

	/** No puede ir: el incidente pasa en el momento a la unidad siguiente. */
	@PostMapping("/rechazo")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void rechazar(@UsuarioActual Long paramedicoId, @Valid @RequestBody RechazarOfertaRequest request) {
		asignador.rechazar(paramedicoId, request.motivo().trim());
	}

}
