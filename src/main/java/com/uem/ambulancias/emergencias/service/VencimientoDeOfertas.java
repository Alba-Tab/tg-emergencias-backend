package com.uem.ambulancias.emergencias.service;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * El reloj de las asignaciones: cada pocos segundos da por vencidas las que se quedaron sin respuesta y le pasa el
 * incidente a la unidad siguiente. Cada una se resuelve en su propia transacción, así una que falle no frena a las
 * demás.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VencimientoDeOfertas {

	private final AsignadorDeIncidentes asignador;

	@Scheduled(fixedDelayString = "${sga.despacho.barrido-seg:5}", timeUnit = TimeUnit.SECONDS)
	public void barrer() {
		Instant ahora = Instant.now();
		for (Long ofertaId : asignador.vencidas(ahora)) {
			try {
				asignador.vencer(ofertaId, ahora);
			} catch (RuntimeException e) {
				log.error("No se pudo vencer la asignación {}: se reintenta en el próximo barrido.", ofertaId, e);
			}
		}
	}

}
