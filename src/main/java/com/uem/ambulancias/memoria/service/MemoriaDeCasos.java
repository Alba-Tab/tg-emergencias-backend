package com.uem.ambulancias.memoria.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.uem.ambulancias.evidencias.service.FalloDelServicioIa;
import com.uem.ambulancias.memoria.domain.MemoriaDelCaso;
import com.uem.ambulancias.memoria.repository.MemoriaDelCasoRepository;
import com.uem.ambulancias.memoria.service.ModeloDeEmbeddings.VectoresRecibidos;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * La memoria de casos: los incidentes cerrados, guardados de forma que al llegar uno nuevo se puedan encontrar los
 * que se le parecen. Sirven de referencia para el agente y para la persona, no de regla.
 *
 * <p>Ningún método abre una transacción: adentro se llama al servicio de IA, que puede tardar.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoriaDeCasos {

	private final MemoriaDelCasoRepository memorias;
	private final ResumenDelCaso resumen;
	private final ObjectProvider<ModeloDeEmbeddings> modelo;
	private final MemoriaProperties config;

	/** El modelo del último vector recibido: si cambia, los vectores viejos se vuelven a calcular. */
	private volatile String ultimoModelo;

	/** Un incidente cerrado que se parece al que se consultó, con qué tanto se parece (de 0 a 1). */
	public record Parecido(Long incidenteId, double similitud) {
	}

	/**
	 * @param casos     cuántos incidentes cerrados están en la memoria
	 * @param indexados cuántos tienen su vector y se pueden encontrar
	 */
	public record Estado(long casos, long indexados, boolean busquedaEncendida) {
	}

	public Estado estado() {
		return new Estado(memorias.count(), memorias.countByVectorIsNotNull(), encendido().isPresent());
	}

	/**
	 * Suma a la memoria los incidentes que se cerraron y calcula los vectores que faltan, de a un lote. Devuelve
	 * cuántos vectores calculó: quien barre repite mientras haya trabajo.
	 */
	public int indexarPendientes() {
		ModeloDeEmbeddings embeddings = encendido().orElse(null);
		if (embeddings == null) {
			return 0;
		}
		PageRequest lote = PageRequest.of(0, config.lote());
		memorias.buscarIncidentesSinMemoria(lote).forEach(resumen::registrar);
		List<MemoriaDelCaso> pendientes = new ArrayList<>(memorias.buscarSinVector(lote));
		if (pendientes.isEmpty() && ultimoModelo != null) {
			pendientes.addAll(memorias.buscarDeOtroModelo(ultimoModelo, lote));
		}
		if (pendientes.isEmpty()) {
			return 0;
		}
		VectoresRecibidos recibidos;
		try {
			recibidos = embeddings.embeber(pendientes.stream().map(MemoriaDelCaso::getTexto).toList());
		} catch (FalloDelServicioIa e) {
			log.warn("La memoria de casos se queda sin indexar por ahora: {} ({}).", e.getCodigo(), e.getDesenlace());
			return 0;
		}
		Instant ahora = Instant.now();
		for (int i = 0; i < pendientes.size(); i++) {
			pendientes.get(i).indexar(Vectores.aBytes(recibidos.vectores().get(i)), recibidos.modelo(), ahora);
		}
		memorias.saveAll(pendientes);
		ultimoModelo = recibidos.modelo();
		return pendientes.size();
	}

	/**
	 * Los incidentes cerrados que más se parecen a este, del más parecido al menos. Vacío si la búsqueda está apagada,
	 * si la memoria está vacía, si el incidente no tiene de qué armar un resumen o si el servicio de IA no responde.
	 */
	public List<Parecido> parecidosA(Long incidenteId, int cuantos) {
		ModeloDeEmbeddings embeddings = encendido().orElse(null);
		if (embeddings == null || memorias.countByVectorIsNotNull() == 0) {
			return List.of();
		}
		String texto = resumen.textoDe(incidenteId).orElse(null);
		if (texto == null) {
			return List.of();
		}
		VectoresRecibidos recibidos;
		try {
			recibidos = embeddings.embeber(List.of(texto));
		} catch (FalloDelServicioIa e) {
			log.warn("Búsqueda de casos parecidos sin embeddings: {} ({}).", e.getCodigo(), e.getDesenlace());
			return List.of();
		}
		float[] buscado = recibidos.vectores().getFirst();
		List<Parecido> parecidos = new ArrayList<>();
		for (MemoriaDelCaso memoria : memorias.buscarComparablesCon(recibidos.modelo(), incidenteId)) {
			double similitud = Vectores.coseno(buscado, Vectores.deBytes(memoria.getVector()));
			if (similitud >= config.similitudMinima()) {
				parecidos.add(new Parecido(memoria.getIncidente().getId(), similitud));
			}
		}
		parecidos.sort(Comparator.comparingDouble(Parecido::similitud).reversed());
		return List.copyOf(parecidos.subList(0, Math.min(Math.max(cuantos, 0), parecidos.size())));
	}

	/**
	 * Si la memoria tiene algún caso del mismo tipo de suceso para mostrarle al agente. Cuentan todos los incidentes
	 * cerrados de ese tipo, indexados o no: con la búsqueda por parecido apagada, el agente los recibe igual por tipo.
	 */
	public boolean hayCasosDelTipo(String tipoSuceso) {
		return tipoSuceso != null && memorias.existenCasosDelTipo(tipoSuceso);
	}

	private Optional<ModeloDeEmbeddings> encendido() {
		return Optional.ofNullable(modelo.getIfAvailable()).filter(ModeloDeEmbeddings::listoParaEmbeber);
	}

}
