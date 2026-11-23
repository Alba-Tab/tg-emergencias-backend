package com.uem.ambulancias.coordinacion.service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.uem.ambulancias.evidencias.service.FalloDelServicioIa;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Worker de las evaluaciones: toma de a una, junta lo que se sabe del caso, resuelve con las reglas lo que se pueda y
 * le consulta al modelo de decisión lo que quede abierto.
 *
 * <p>Existe siempre, con la IA encendida o apagada. Sin modelo de decisión trabaja solo con las reglas: lo que el
 * testigo contestó se aplica igual.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EvaluadorDeCasos {

	private final ColaDeEvaluaciones cola;
	private final EvaluacionDelCasoService servicio;
	private final ObjectProvider<ModeloDeDecision> modelo;

	@Scheduled(fixedDelayString = "${sga.coordinacion.barrido-seg:2}", timeUnit = TimeUnit.SECONDS)
	public void barrer() {
		List<Long> tomadas = cola.tomar(1);
		while (!tomadas.isEmpty()) {
			procesar(tomadas.getFirst());
			tomadas = cola.tomar(1);
		}
	}

	private void procesar(Long evaluacionId) {
		try {
			ModeloDeDecision decisor = modelo.getIfAvailable();
			Optional<PedidoDeEvaluacion> pedido = servicio.preparar(evaluacionId, decisor != null);
			if (pedido.isEmpty()) {
				return;
			}
			long inicio = System.nanoTime();
			ProbabilidadesRecibidas recibidas = decisor.evaluar(pedido.get());
			servicio.concluir(evaluacionId, recibidas, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicio));
		} catch (FalloDelServicioIa e) {
			log.warn("Evaluación {} sin respuesta del modelo: {} ({}).", evaluacionId, e.getCodigo(),
					e.getDesenlace());
			servicio.sinModelo(evaluacionId, e.getCodigo(), e.getDesenlace());
		} catch (RuntimeException e) {
			log.error("Falló la evaluación {} de este lado.", evaluacionId, e);
			cola.reintentar(evaluacionId, "error_del_backend");
		}
	}

}
