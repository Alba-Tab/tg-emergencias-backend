package com.uem.ambulancias.revision.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.uem.ambulancias.evidencias.service.FalloDelServicioIa;
import com.uem.ambulancias.memoria.service.IndiceDeLaNorma;
import com.uem.ambulancias.revision.service.AnalistaDelProtocolo.ExtractoDeNorma;
import com.uem.ambulancias.revision.service.AnalistaDelProtocolo.PedidoDeRevision;
import com.uem.ambulancias.revision.service.AnalistaDelProtocolo.PropuestasRecibidas;
import com.uem.ambulancias.revision.service.RevisionDelProtocoloService.Preparada;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Worker de las revisiones del protocolo: toma las pedidas, le manda al analista los hechos y los extractos de la
 * norma que vienen al caso, y guarda lo que propone. Una revisión tarda decenas de segundos y nadie la espera con un
 * caso abierto: por eso corre aparte de todo lo demás.
 *
 * <p>Con el analista apagado no hace nada.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RevisorDelProtocolo {

	private final RevisionDelProtocoloService servicio;
	private final ObjectProvider<AnalistaDelProtocolo> analista;
	private final IndiceDeLaNorma norma;
	private final RevisionProperties config;

	@Scheduled(fixedDelayString = "${sga.revision.barrido-seg:5}", timeUnit = TimeUnit.SECONDS)
	public void barrer() {
		AnalistaDelProtocolo encendido = analista.getIfAvailable();
		if (encendido == null || !encendido.listoParaRevisar()) {
			return;
		}
		for (Long revisionId : servicio.porHacer()) {
			procesar(encendido, revisionId);
		}
	}

	private void procesar(AnalistaDelProtocolo encendido, Long revisionId) {
		try {
			Optional<Preparada> preparada = servicio.preparar(revisionId);
			if (preparada.isEmpty()) {
				return;
			}
			List<ExtractoDeNorma> extractos = extractos(preparada.get().temas());
			PropuestasRecibidas recibidas = encendido.revisar(new PedidoDeRevision(UUID.randomUUID(),
					preparada.get().protocoloJson(), preparada.get().hechos(), extractos));
			servicio.concluir(revisionId, preparada.get().hechos(), extractos, recibidas);
		} catch (FalloDelServicioIa e) {
			log.warn("Revisión {} sin respuesta del analista: {} ({}).", revisionId, e.getCodigo(), e.getDesenlace());
			servicio.fallar(revisionId, "El analista no respondió (" + e.getCodigo() + "). Vuelve a pedirla.");
		} catch (RuntimeException e) {
			log.error("Falló la revisión {} de este lado.", revisionId, e);
			servicio.fallar(revisionId, "La revisión falló de este lado. Vuelve a pedirla.");
		}
	}

	/** Los extractos de la norma más cercanos a lo que evalúa el protocolo, sin repetir, numerados N1, N2… */
	private List<ExtractoDeNorma> extractos(List<String> temas) {
		Map<String, IndiceDeLaNorma.Hallazgo> unicos = new LinkedHashMap<>();
		for (String tema : temas) {
			for (IndiceDeLaNorma.Hallazgo hallazgo : norma.buscar(tema)) {
				if (unicos.size() < config.extractos()) {
					unicos.putIfAbsent(hallazgo.documento() + "/" + hallazgo.seccion(), hallazgo);
				}
			}
		}
		List<ExtractoDeNorma> extractos = new ArrayList<>();
		for (IndiceDeLaNorma.Hallazgo hallazgo : unicos.values()) {
			extractos.add(new ExtractoDeNorma("N" + (extractos.size() + 1), hallazgo.titulo() + ", "
					+ hallazgo.seccion() + (hallazgo.pagina() == null ? "" : ", p. " + hallazgo.pagina()) + ". "
					+ hallazgo.referencia(), hallazgo.texto()));
		}
		return extractos;
	}

}
