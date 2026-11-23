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
 * Worker de los razonamientos: toma de a uno, le manda al agente el estado del caso con las condiciones en duda y
 * aplica sus conclusiones. Corre aparte del evaluador: un razonamiento tarda decenas de segundos y no puede frenar
 * las evaluaciones de los demás incidentes.
 *
 * <p>Sin agente encendido no hace nada: tampoco se le piden razonamientos.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RazonadorDeCasos {

	private final RazonamientoDelCasoService servicio;
	private final ObjectProvider<Razonador> razonador;

	@Scheduled(fixedDelayString = "${sga.razonamiento.barrido-seg:2}", timeUnit = TimeUnit.SECONDS)
	public void barrer() {
		Razonador agente = razonador.getIfAvailable();
		if (agente == null || !agente.disponible()) {
			return;
		}
		List<Long> tomados = servicio.tomar(1);
		while (!tomados.isEmpty()) {
			procesar(agente, tomados.getFirst());
			tomados = servicio.tomar(1);
		}
	}

	private void procesar(Razonador agente, Long razonamientoId) {
		try {
			Optional<PedidoDeRazonamiento> pedido = servicio.preparar(razonamientoId);
			if (pedido.isEmpty()) {
				return;
			}
			long inicio = System.nanoTime();
			ConclusionesRecibidas recibidas = agente.razonar(pedido.get());
			servicio.concluir(razonamientoId, recibidas, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicio));
		} catch (FalloDelServicioIa e) {
			log.warn("Razonamiento {} sin respuesta del agente: {} ({}).", razonamientoId, e.getCodigo(),
					e.getDesenlace());
			servicio.sinRespuesta(razonamientoId, e.getCodigo(), e.getDesenlace());
		} catch (RuntimeException e) {
			log.error("Falló el razonamiento {} de este lado.", razonamientoId, e);
			servicio.sinRespuesta(razonamientoId, "error_del_backend", FalloDelServicioIa.Desenlace.REINTENTAR);
		}
	}

}
