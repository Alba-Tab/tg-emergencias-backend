package com.uem.ambulancias.coordinacion.service;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Barrido que da por vencidas las derivaciones que se quedaron sin respuesta. Cada una va en su transacción: si una
 * falla, las demás siguen.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VencimientoDeDerivaciones {

	private final Derivaciones derivaciones;

	@Scheduled(fixedDelayString = "${sga.derivacion.barrido-seg:5}", timeUnit = TimeUnit.SECONDS)
	public void barrer() {
		Instant ahora = Instant.now();
		for (Long derivacionId : derivaciones.vencidas(ahora)) {
			try {
				derivaciones.vencer(derivacionId, ahora);
			} catch (RuntimeException e) {
				log.error("No se pudo dar por vencida la derivación {}.", derivacionId, e);
			}
		}
	}

}
