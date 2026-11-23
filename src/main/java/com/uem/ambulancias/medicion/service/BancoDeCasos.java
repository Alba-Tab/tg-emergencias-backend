package com.uem.ambulancias.medicion.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.uem.ambulancias.comun.error.CodigoError;
import com.uem.ambulancias.comun.error.ConflictoException;
import com.uem.ambulancias.comun.error.NoEncontradoException;
import com.uem.ambulancias.coordinacion.domain.EvaluacionDelCaso;
import com.uem.ambulancias.coordinacion.repository.EvaluacionDelCasoRepository;
import com.uem.ambulancias.emergencias.domain.Incidente;
import com.uem.ambulancias.emergencias.repository.IncidenteRepository;
import com.uem.ambulancias.medicion.domain.CasoDePrueba;
import com.uem.ambulancias.medicion.repository.CasoDePruebaRepository;
import com.uem.ambulancias.protocolo.domain.Protocolo;
import com.uem.ambulancias.protocolo.domain.TipoDeSuceso;
import com.uem.ambulancias.protocolo.service.ProtocoloService;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * El banco de casos: sucesos de los que se sabe la verdad, para medir con ellos al modelo de decisión. Entran por dos
 * caminos: los inventa el generador a partir del protocolo vigente, o salen de un incidente real cuya verdad confirma
 * una persona.
 */
@Service
@RequiredArgsConstructor
public class BancoDeCasos {

	/** Cuántos casos se devuelven al listar. */
	private static final int MAXIMO_AL_LISTAR = 200;

	private final CasoDePruebaRepository casos;
	private final IncidenteRepository incidentes;
	private final EvaluacionDelCasoRepository evaluaciones;
	private final ProtocoloService protocolos;
	private final JsonMapper json;

	/**
	 * Genera casos inventados con el protocolo vigente, repartidos entre sus tipos de suceso. Cada caso usa una
	 * semilla distinta que sale de la que se pasa: con la misma semilla y el mismo protocolo salen los mismos casos.
	 *
	 * @return cuántos generó
	 */
	@Transactional
	public int generar(int cantidad, long semilla) {
		Protocolo vigente = protocolos.vigente().orElseThrow(() -> new ConflictoException(CodigoError.PROTOCOLO_INVALIDO,
				"La central no tiene un protocolo publicado: no hay con qué generar casos."));
		List<TipoDeSuceso> tipos = vigente.getTipos().stream().filter(tipo -> !tipo.getCondiciones().isEmpty()).toList();
		if (tipos.isEmpty()) {
			throw new ConflictoException(CodigoError.PROTOCOLO_INVALIDO,
					"El protocolo vigente no tiene condiciones: no hay nada que medir.");
		}
		Instant ahora = Instant.now();
		for (int i = 0; i < cantidad; i++) {
			TipoDeSuceso tipo = tipos.get(i % tipos.size());
			long semillaDelCaso = semilla * 1_000_003L + i;
			GeneradorDeCasos.Caso caso = GeneradorDeCasos.generar(tipo, semillaDelCaso);
			casos.save(CasoDePrueba.generado(vigente, tipo.getCodigo(), json.writeValueAsString(caso.estado()),
					json.writeValueAsString(caso.verdades()), semillaDelCaso, ahora));
		}
		return cantidad;
	}

	/**
	 * Guarda como caso real el estado de un incidente tal como quedó en su última evaluación, con la verdad de cada
	 * condición que confirma una persona. Tiene que decir la verdad de todas las condiciones del tipo de suceso.
	 */
	@Transactional
	public CasoDePrueba guardarReal(Long incidenteId, Map<String, Boolean> verdades, String nota) {
		Incidente incidente = incidentes.findById(incidenteId)
				.orElseThrow(() -> new NoEncontradoException("No existe el incidente " + incidenteId + "."));
		if (incidente.getTipoSuceso() == null || incidente.getProtocolo() == null) {
			throw new ConflictoException(CodigoError.SIN_TIPO_DE_SUCESO,
					"El incidente " + incidenteId + " no tiene un tipo de suceso declarado.");
		}
		EvaluacionDelCaso ultima = evaluaciones.buscarUltimaHecha(incidenteId)
				.orElseThrow(() -> new ConflictoException(CodigoError.SIN_TIPO_DE_SUCESO,
						"El incidente " + incidenteId + " todavía no tiene ninguna evaluación terminada."));
		TipoDeSuceso tipo = incidente.getProtocolo().buscarTipo(incidente.getTipoSuceso()).orElseThrow();
		Map<String, Boolean> completas = new LinkedHashMap<>();
		tipo.getCondiciones().forEach(condicion -> {
			Boolean verdad = verdades.get(condicion.getCodigo());
			if (verdad == null) {
				throw new ConflictoException(CodigoError.CONDICION_DESCONOCIDA,
						"Falta decir si se cumplió " + condicion.getCodigo() + ".");
			}
			completas.put(condicion.getCodigo(), verdad);
		});
		return casos.save(CasoDePrueba.real(incidente, ultima.getEstadoDelCaso(), json.writeValueAsString(completas),
				nota, Instant.now()));
	}

	/** Los casos más nuevos, de ese origen o de cualquiera. */
	@Transactional(readOnly = true)
	public List<CasoDePrueba> listar(String origen) {
		PageRequest limite = PageRequest.of(0, MAXIMO_AL_LISTAR);
		return origen == null ? casos.buscar(limite) : casos.buscarDeOrigen(origen, limite);
	}

	/** Cuántos casos hay de cada origen. */
	@Transactional(readOnly = true)
	public Map<String, Integer> cuantos() {
		Map<String, Integer> cuantos = new LinkedHashMap<>();
		cuantos.put(CasoDePrueba.GENERADO, casos.countByOrigen(CasoDePrueba.GENERADO));
		cuantos.put(CasoDePrueba.REAL, casos.countByOrigen(CasoDePrueba.REAL));
		return cuantos;
	}

	/** Borra los casos generados que ninguna medición usó. Los que ya se midieron quedan: son parte de ese resultado. */
	@Transactional
	public int borrarGeneradosSinMedir() {
		return casos.borrarGeneradosSinMedir();
	}

}
