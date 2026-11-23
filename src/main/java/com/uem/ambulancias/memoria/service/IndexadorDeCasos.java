package com.uem.ambulancias.memoria.service;

import java.util.concurrent.TimeUnit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Worker de la memoria de casos: cada tanto suma los incidentes que se cerraron y calcula sus vectores. No hay apuro:
 * un caso recién cerrado sirve de referencia para los que vengan después, no para el que está en curso.
 *
 * <p>Con la búsqueda por parecido apagada no hace nada.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IndexadorDeCasos {

	/** Tope de lotes por barrido, para que un atraso grande no ocupe el hilo sin fin. */
	private static final int LOTES_POR_BARRIDO = 20;

	private final MemoriaDeCasos memoria;

	@Scheduled(fixedDelayString = "${sga.memoria.barrido-seg:30}", timeUnit = TimeUnit.SECONDS)
	public void barrer() {
		try {
			for (int lote = 0; lote < LOTES_POR_BARRIDO && memoria.indexarPendientes() > 0; lote++) {
				// Cada vuelta indexa un lote; para cuando ya no queda nada.
			}
		} catch (RuntimeException e) {
			log.error("Falló el barrido de la memoria de casos.", e);
		}
	}

}
