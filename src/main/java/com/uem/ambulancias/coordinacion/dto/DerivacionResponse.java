package com.uem.ambulancias.coordinacion.dto;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.uem.ambulancias.coordinacion.domain.Derivacion;
import com.uem.ambulancias.coordinacion.domain.EstadoDerivacion;
import com.uem.ambulancias.coordinacion.domain.MotivoDeDerivacion;
import com.uem.ambulancias.coordinacion.domain.OpcionDeDerivacion;

/**
 * Un asunto que el sistema le pasó a la central, como lo ve la persona que lo tiene que decidir.
 *
 * @param detalle         el paquete tal como se armó: sus campos dependen del motivo
 * @param opciones        lo que puede contestar
 * @param opcionSegura    la que se aplica sola si nadie contesta antes de {@code venceEn}
 * @param necesitaAtencion si espera respuesta, o si venció y nadie la marcó como vista
 */
public record DerivacionResponse(
		Long id,
		Long incidenteId,
		EstadoDerivacion estado,
		MotivoDeDerivacion motivo,
		String condicion,
		@JsonRawValue String detalle,
		List<Opcion> opciones,
		OpcionDeDerivacion opcionSegura,
		Instant creadaEn,
		Instant venceEn,
		Instant resueltaEn,
		OpcionDeDerivacion opcionElegida,
		String resueltaPor,
		String nota,
		Instant vistaEn,
		String vistaPor,
		Long evaluacionId,
		boolean necesitaAtencion) {

	/** Una opción con el texto que se le muestra a la persona. */
	public record Opcion(OpcionDeDerivacion codigo, String texto) {
	}

	public static DerivacionResponse de(Derivacion derivacion) {
		MotivoDeDerivacion motivo = derivacion.getMotivo();
		return new DerivacionResponse(
				derivacion.getId(),
				derivacion.getIncidente().getId(),
				derivacion.getEstado(),
				motivo,
				derivacion.getCondicion(),
				derivacion.getDetalle(),
				motivo.getOpciones().stream().map(opcion -> new Opcion(opcion, opcion.getTexto())).toList(),
				motivo.getOpcionSegura(),
				derivacion.getCreadaEn(),
				derivacion.getVenceEn(),
				derivacion.getResueltaEn(),
				derivacion.getOpcionElegida(),
				derivacion.getResueltaPor() == null ? null : derivacion.getResueltaPor().getNombreCompleto(),
				derivacion.getNota(),
				derivacion.getVistaEn(),
				derivacion.getVistaPor() == null ? null : derivacion.getVistaPor().getNombreCompleto(),
				derivacion.getEvaluacion() == null ? null : derivacion.getEvaluacion().getId(),
				derivacion.necesitaAtencion());
	}

}
