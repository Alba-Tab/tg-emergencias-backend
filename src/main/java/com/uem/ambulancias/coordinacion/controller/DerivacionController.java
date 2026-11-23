package com.uem.ambulancias.coordinacion.controller;

import java.util.List;

import com.uem.ambulancias.comun.web.Textos;
import com.uem.ambulancias.comun.web.UsuarioActual;
import com.uem.ambulancias.coordinacion.dto.DecidirDerivacionRequest;
import com.uem.ambulancias.coordinacion.dto.DerivacionResponse;
import com.uem.ambulancias.coordinacion.service.Derivaciones;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Lo que el sistema le pasa a la central para que lo decida una persona (PB-38). */
@RestController
@RequiredArgsConstructor
public class DerivacionController {

	/** Cuántas se devuelven al pedir el historial. */
	private static final int MAXIMO_DEL_HISTORIAL = 100;

	private final Derivaciones derivaciones;

	/**
	 * Las que le hacen falta a una persona: las que esperan respuesta y las que vencieron sin que nadie las viera, la
	 * que vence antes primero. Con {@code todas=true}, las últimas cien con cualquier estado, de la más nueva a la más
	 * vieja.
	 */
	@GetMapping("/derivaciones")
	public List<DerivacionResponse> listar(@RequestParam(defaultValue = "false") boolean todas) {
		return (todas ? derivaciones.recientes(MAXIMO_DEL_HISTORIAL) : derivaciones.queNecesitanAtencion()).stream()
				.map(DerivacionResponse::de)
				.toList();
	}

	/** 404 si no existe. */
	@GetMapping("/derivaciones/{id}")
	public DerivacionResponse ver(@PathVariable Long id) {
		return DerivacionResponse.de(derivaciones.buscar(id));
	}

	/**
	 * La persona contesta. 409 {@code DERIVACION_CERRADA} si ya no esperaba respuesta y 409 {@code OPCION_NO_PERMITIDA}
	 * si el motivo no admite esa opción.
	 */
	@PostMapping("/derivaciones/{id}/decision")
	public DerivacionResponse decidir(@PathVariable Long id, @UsuarioActual Long administradorId,
			@Valid @RequestBody DecidirDerivacionRequest request) {
		return DerivacionResponse.de(derivaciones.decidir(id, administradorId, request.opcion(),
				Textos.opcional(request.nota())));
	}

	/** Alguien se da por enterado de una que venció sin respuesta. 409 {@code DERIVACION_CERRADA} si no está vencida. */
	@PostMapping("/derivaciones/{id}/visto")
	public DerivacionResponse marcarVista(@PathVariable Long id, @UsuarioActual Long administradorId) {
		return DerivacionResponse.de(derivaciones.marcarVista(id, administradorId));
	}

	/** Todo lo que se derivó de un incidente, en orden. 404 si el incidente no existe. */
	@GetMapping("/incidentes/{id}/derivaciones")
	public List<DerivacionResponse> deIncidente(@PathVariable("id") Long incidenteId) {
		return derivaciones.deIncidente(incidenteId).stream().map(DerivacionResponse::de).toList();
	}

}
