package com.uem.ambulancias.protocolo.dto;

import java.time.Instant;
import java.util.List;

import com.uem.ambulancias.flota.domain.TipoUnidad;
import com.uem.ambulancias.protocolo.domain.Condicion;
import com.uem.ambulancias.protocolo.domain.EstadoProtocolo;
import com.uem.ambulancias.protocolo.domain.Protocolo;
import com.uem.ambulancias.protocolo.domain.TipoDeSuceso;

/** Una versión del protocolo con todo su contenido, como la ve la central. */
public record ProtocoloResponse(Long id, int version, EstadoProtocolo estado, String nota, Instant creadoEn,
		Instant publicadoEn, List<Tipo> tipos) {

	public record Tipo(String codigo, String nombre, List<CondicionDelTipo> condiciones) {

		static Tipo de(TipoDeSuceso tipo) {
			return new Tipo(tipo.getCodigo(), tipo.getNombre(),
					tipo.getCondiciones().stream().map(CondicionDelTipo::de).toList());
		}

	}

	public record CondicionDelTipo(String codigo, String enunciado, String pregunta, double umbralActuar,
			double umbralPreguntar, int unidadesAdicionales, TipoUnidad tipoMinimo, boolean derivaAPersona,
			String cita, Integer afectadosDesde) {

		static CondicionDelTipo de(Condicion condicion) {
			return new CondicionDelTipo(condicion.getCodigo(), condicion.getEnunciado(), condicion.getPregunta(),
					condicion.getUmbralActuar(), condicion.getUmbralPreguntar(), condicion.getUnidadesAdicionales(),
					condicion.getTipoMinimo(), condicion.isDerivaAPersona(), condicion.getCita(),
					condicion.getAfectadosDesde());
		}

	}

	public static ProtocoloResponse de(Protocolo protocolo) {
		return new ProtocoloResponse(protocolo.getId(), protocolo.getVersion(), protocolo.getEstado(),
				protocolo.getNota(), protocolo.getCreadoEn(), protocolo.getPublicadoEn(),
				protocolo.getTipos().stream().map(Tipo::de).toList());
	}

}
