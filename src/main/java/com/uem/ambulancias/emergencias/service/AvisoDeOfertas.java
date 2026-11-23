package com.uem.ambulancias.emergencias.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.uem.ambulancias.emergencias.domain.OfertaDeIncidente;
import com.uem.ambulancias.emergencias.repository.OfertaDeIncidenteRepository;
import com.uem.ambulancias.flota.domain.Turno;
import com.uem.ambulancias.flota.repository.TurnoRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Los push de una asignación: a la tripulación le llega que tiene un incidente esperando su confirmación y, si deja
 * de hacer falta, que ya no tiene que contestar. Se manda después del commit y a quienes están de turno en la
 * unidad, igual que el aviso de un despacho.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AvisoDeOfertas {

	/** Lo que mira la app para saber qué pantalla abrir. */
	public static final String TIPO_ASIGNADO = "INCIDENTE_POR_CONFIRMAR";
	public static final String TIPO_RETIRADO = "ASIGNACION_RETIRADA";

	private final OfertaDeIncidenteRepository ofertas;
	private final TurnoRepository turnos;
	private final NotificadorPush notificador;

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public void avisarAsignacion(OfertaEmitida evento) {
		try {
			OfertaDeIncidente oferta = ofertas.findById(evento.ofertaId()).orElse(null);
			if (oferta == null || !oferta.isPendiente()) {
				return;
			}
			long segundos = Math.max(0, Duration.between(Instant.now(), oferta.getVenceEn()).toSeconds());
			Map<String, String> datos = Map.of(
					"tipo", TIPO_ASIGNADO,
					"incidenteId", String.valueOf(oferta.getIncidente().getId()),
					"ofertaId", String.valueOf(oferta.getId()),
					"venceEn", oferta.getVenceEn().toString());
			avisar(oferta.getAmbulancia().getId(), "Te asignaron una emergencia",
					"Confirma si puedes ir. Tienes " + segundos + " segundos.", datos);
		} catch (RuntimeException e) {
			log.error("No se pudo avisar de la asignación {}.", evento.ofertaId(), e);
		}
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public void avisarRetiro(OfertaRetirada evento) {
		try {
			avisar(evento.ambulanciaId(), "La emergencia ya no espera tu respuesta",
					"Se la pasó a otra unidad o ya no hace falta.",
					Map.of("tipo", TIPO_RETIRADO, "incidenteId", String.valueOf(evento.incidenteId())));
		} catch (RuntimeException e) {
			log.error("No se pudo avisar a la ambulancia {} que su asignación del incidente {} se retiró.",
					evento.ambulanciaId(), evento.incidenteId(), e);
		}
	}

	private void avisar(Long ambulanciaId, String titulo, String cuerpo, Map<String, String> datos) {
		List<String> tokens = turnos.buscarAbiertosConAvisoPorAmbulancias(List.of(ambulanciaId)).stream()
				.map(Turno::getParamedico)
				.map(paramedico -> paramedico.getTokenPush())
				.distinct()
				.toList();
		if (tokens.isEmpty()) {
			log.info("Nadie de turno en la ambulancia {} tiene un teléfono registrado: \"{}\" solo se verá en la app.",
					ambulanciaId, titulo);
			return;
		}
		notificador.notificarParamedicos(tokens.stream()
				.map(token -> new AvisoParaParamedico(token, titulo, cuerpo, datos))
				.toList());
	}

}
