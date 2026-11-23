package com.uem.ambulancias.coordinacion.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Le avisa al panel, después del commit, cuántas derivaciones le quedan por atender. Con el panel abierto, la persona
 * de la central se entera en el momento de que el sistema le pasó algo.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DifusionDeDerivaciones {

	private final Derivaciones derivaciones;
	private final PublicadorDeDerivaciones publicador;

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public void difundir(DerivacionCambiada evento) {
		try {
			publicador.publicarPendientes(derivaciones.cuantasNecesitanAtencion());
		} catch (RuntimeException e) {
			// El cambio ya está confirmado: un fallo al publicar no debe convertirse en un error de la petición.
			log.error("No se pudo avisar al panel del cambio en la derivación {}.", evento.derivacionId(), e);
		}
	}

}
