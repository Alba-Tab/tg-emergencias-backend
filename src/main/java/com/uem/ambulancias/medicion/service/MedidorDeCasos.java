package com.uem.ambulancias.medicion.service;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.uem.ambulancias.coordinacion.service.ModeloDeDecision;
import com.uem.ambulancias.coordinacion.service.ProbabilidadesRecibidas;
import com.uem.ambulancias.evidencias.service.FalloDelServicioIa;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Worker de las mediciones: toma una medición pendiente y le pasa al modelo de decisión los casos del banco, de a
 * uno. Si el modelo falla en un caso, queda anotado y sigue con los demás; si está mal configurado, la medición se
 * corta, porque todos los casos fallarían igual. Si el servidor se reinicia a mitad, la medición sigue donde quedó.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MedidorDeCasos {

	private final MedicionService servicio;
	private final ObjectProvider<ModeloDeDecision> modelo;

	@Scheduled(fixedDelayString = "${sga.medicion.barrido-seg:5}", timeUnit = TimeUnit.SECONDS)
	public void barrer() {
		for (Long medicionId : servicio.porHacer()) {
			medir(medicionId);
		}
	}

	private void medir(Long medicionId) {
		ModeloDeDecision decisor = modelo.getIfAvailable();
		if (decisor == null) {
			servicio.fallar(medicionId, "sin_modelo_de_decision");
			return;
		}
		try {
			Optional<MedicionService.Paso> paso = servicio.siguiente(medicionId);
			while (paso.isPresent()) {
				Long casoId = paso.get().casoId();
				try {
					long inicio = System.nanoTime();
					ProbabilidadesRecibidas recibidas = decisor.evaluar(paso.get().pedido());
					servicio.anotar(medicionId, casoId, recibidas,
							TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicio));
				} catch (FalloDelServicioIa e) {
					if (e.getDesenlace() == FalloDelServicioIa.Desenlace.REVISION) {
						log.error("El modelo de decisión no está bien configurado ({}): se corta la medición {}.",
								e.getCodigo(), medicionId);
						servicio.fallar(medicionId, e.getCodigo());
						return;
					}
					servicio.anotarError(medicionId, casoId, e.getCodigo());
				}
				paso = servicio.siguiente(medicionId);
			}
			servicio.cerrar(medicionId);
		} catch (RuntimeException e) {
			log.error("Falló la medición {} de este lado.", medicionId, e);
			servicio.fallar(medicionId, "error_del_backend");
		}
	}

}
