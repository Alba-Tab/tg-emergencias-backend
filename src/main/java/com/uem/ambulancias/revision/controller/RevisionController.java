package com.uem.ambulancias.revision.controller;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.uem.ambulancias.comun.web.UsuarioActual;
import com.uem.ambulancias.revision.domain.RevisionDelProtocolo;
import com.uem.ambulancias.revision.service.RevisionDelProtocoloService;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Las revisiones del protocolo que hace el analista (PB-45). Todo es de la central. Una revisión propone; cambiar el
 * protocolo sigue siendo editar un borrador y publicarlo.
 */
@RestController
@RequestMapping("/protocolos/revisiones")
@RequiredArgsConstructor
public class RevisionController {

	private final RevisionDelProtocoloService revisiones;

	/**
	 * @param hechos     lo que se contó del uso del protocolo: {@code [{id, tipoDeSuceso, condicion, texto, casos}]}
	 * @param propuestas lo que propone el analista:
	 *                   {@code [{tipo, tipoDeSuceso, condicion, propuesta, porQue, hechos[], norma[]}]}
	 * @param nota       por qué no hay propuestas, cuando no las hay
	 */
	public record RevisionResponse(Long id, String estado, int versionDelProtocolo, Instant creadaEn,
			Instant terminadaEn, String proveedor, String modelo, String nota, int descartadas,
			@JsonRawValue String hechos, @JsonRawValue String propuestas) {

		static RevisionResponse de(RevisionDelProtocolo revision) {
			return new RevisionResponse(revision.getId(), revision.getEstado(), revision.getVersionDelProtocolo(),
					revision.getCreadaEn(), revision.getTerminadaEn(), revision.getProveedor(), revision.getModelo(),
					revision.getNota(), revision.getDescartadas(), revision.getHechos(), revision.getPropuestas());
		}

	}

	/**
	 * Pide una revisión del protocolo vigente. Corre en segundo plano: el resultado se consulta después. 409
	 * {@code ANALISTA_NO_DISPONIBLE} si el analista está apagado y {@code PROTOCOLO_INVALIDO} si no hay un protocolo
	 * publicado.
	 */
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public RevisionResponse pedir(@UsuarioActual Long administradorId) {
		return RevisionResponse.de(revisiones.pedir(administradorId));
	}

	/** Las últimas revisiones, de la más nueva a la más vieja. */
	@GetMapping
	public List<RevisionResponse> listar() {
		return revisiones.listar().stream().map(RevisionResponse::de).toList();
	}

	/** Una revisión con sus hechos y sus propuestas. 404 si no existe. */
	@GetMapping("/{id}")
	public RevisionResponse buscar(@PathVariable Long id) {
		return RevisionResponse.de(revisiones.buscar(id));
	}

}
