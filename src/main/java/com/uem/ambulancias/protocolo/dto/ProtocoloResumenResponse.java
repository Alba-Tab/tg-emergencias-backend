package com.uem.ambulancias.protocolo.dto;

import java.time.Instant;

import com.uem.ambulancias.protocolo.domain.EstadoProtocolo;
import com.uem.ambulancias.protocolo.domain.Protocolo;

/** Una versión del protocolo en el listado: sin su contenido, solo para ubicarla. */
public record ProtocoloResumenResponse(Long id, int version, EstadoProtocolo estado, String nota, Instant creadoEn,
		Instant publicadoEn, int cantidadDeTipos) {

	public static ProtocoloResumenResponse de(Protocolo protocolo) {
		return new ProtocoloResumenResponse(protocolo.getId(), protocolo.getVersion(), protocolo.getEstado(),
				protocolo.getNota(), protocolo.getCreadoEn(), protocolo.getPublicadoEn(),
				protocolo.getTipos().size());
	}

}
