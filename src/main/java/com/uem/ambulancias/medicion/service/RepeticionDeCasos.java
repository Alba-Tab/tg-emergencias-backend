package com.uem.ambulancias.medicion.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.uem.ambulancias.comun.error.CodigoError;
import com.uem.ambulancias.comun.error.ConflictoException;
import com.uem.ambulancias.comun.error.NoEncontradoException;
import com.uem.ambulancias.coordinacion.domain.EstadoEvaluacion;
import com.uem.ambulancias.coordinacion.domain.EvaluacionDelCaso;
import com.uem.ambulancias.coordinacion.repository.EvaluacionDelCasoRepository;
import com.uem.ambulancias.coordinacion.service.ArmadorDelEstado;
import com.uem.ambulancias.coordinacion.service.JuicioDelProtocolo;
import com.uem.ambulancias.coordinacion.service.ModeloDeDecision;
import com.uem.ambulancias.coordinacion.service.PedidoDeEvaluacion;
import com.uem.ambulancias.coordinacion.service.Veredicto;
import com.uem.ambulancias.emergencias.domain.Incidente;
import com.uem.ambulancias.emergencias.repository.IncidenteRepository;
import com.uem.ambulancias.evidencias.service.FalloDelServicioIa;
import com.uem.ambulancias.protocolo.domain.TipoDeSuceso;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Vuelve a recorrer un incidente (PB-42): toma cada evaluación que se le hizo, con el estado del caso tal como se
 * guardó en ese momento, y la evalúa de nuevo con las reglas y el modelo de ahora. Dice qué veredicto cambiaría. No
 * toca el incidente ni anota nada: es para comparar, por ejemplo antes de cambiar la versión del modelo.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RepeticionDeCasos {

	private final IncidenteRepository incidentes;
	private final EvaluacionDelCasoRepository evaluaciones;
	private final ObjectProvider<ModeloDeDecision> modelo;
	private final TransactionTemplate transaccion;
	private final JsonMapper json;

	/**
	 * Una evaluación vuelta a hacer.
	 *
	 * @param antes   los veredictos que se guardaron entonces
	 * @param ahora   los que salen hoy con el mismo estado del caso
	 * @param cambios las condiciones cuyo resultado sería otro, con el antes y el ahora
	 * @param modelo  si esta vez se pudo consultar al modelo; sin él, lo que le tocaba queda con lo de las reglas
	 */
	public record Repetida(Long evaluacionId, String pedidaPor, JsonNode antes, List<Map<String, Object>> ahora,
			List<String> cambios, boolean conModelo) {
	}

	private record Guardada(Long evaluacionId, String pedidaPor, String estadoJson, JsonNode antes) {
	}

	private record Leido(Long incidenteId, TipoDeSuceso tipo, List<Guardada> guardadas) {
	}

	public List<Repetida> repetir(Long incidenteId) {
		// La lectura va en su transacción y deja cargado lo que se usa después: las consultas al modelo quedan afuera.
		Leido leido = transaccion.execute(estado -> leer(incidenteId));
		ModeloDeDecision decisor = modelo.getIfAvailable();
		List<Repetida> repetidas = new ArrayList<>();
		for (Guardada guardada : leido.guardadas()) {
			JuicioDelProtocolo.Hechos hechos = ArmadorDelEstado.hechosDe(json.readTree(guardada.estadoJson()));
			List<Veredicto> veredictos = JuicioDelProtocolo.porReglas(leido.tipo(), hechos);
			boolean conModelo = false;
			List<PedidoDeEvaluacion.Pregunta> abiertas = veredictos.stream()
					.filter(Veredicto::paraElModelo)
					.map(veredicto -> leido.tipo().buscarCondicion(veredicto.condicion()).orElseThrow())
					.map(condicion -> new PedidoDeEvaluacion.Pregunta(condicion.getCodigo(), condicion.getEnunciado()))
					.toList();
			if (decisor != null && !abiertas.isEmpty()) {
				try {
					veredictos = JuicioDelProtocolo.conModelo(leido.tipo(), veredictos, decisor.evaluar(
							new PedidoDeEvaluacion(UUID.randomUUID(), leido.incidenteId(), guardada.estadoJson(),
									abiertas)).probabilidades());
					conModelo = true;
				} catch (FalloDelServicioIa e) {
					log.warn("Al repetir la evaluación {} el modelo no respondió ({}): queda con las reglas.",
							guardada.evaluacionId(), e.getCodigo());
				}
			}
			repetidas.add(comparada(guardada, veredictos, conModelo));
		}
		return repetidas;
	}

	private Leido leer(Long incidenteId) {
		Incidente incidente = incidentes.findById(incidenteId)
				.orElseThrow(() -> new NoEncontradoException("No existe el incidente " + incidenteId + "."));
		if (incidente.getTipoSuceso() == null || incidente.getProtocolo() == null) {
			throw new ConflictoException(CodigoError.SIN_TIPO_DE_SUCESO,
					"El incidente " + incidenteId + " no tiene un tipo de suceso declarado: no hay nada que repetir.");
		}
		TipoDeSuceso tipo = incidente.getProtocolo().buscarTipo(incidente.getTipoSuceso()).orElseThrow();
		// Se recorren acá para que queden cargadas: después se usan fuera de la transacción.
		tipo.getCondiciones().forEach(condicion -> condicion.getCodigo());
		List<Guardada> guardadas = new ArrayList<>();
		for (EvaluacionDelCaso evaluacion : evaluaciones.findByIncidenteIdOrderByIdAsc(incidenteId)) {
			if (evaluacion.getEstado() == EstadoEvaluacion.HECHA && evaluacion.getEstadoDelCaso() != null
					&& evaluacion.getVeredictos() != null) {
				guardadas.add(new Guardada(evaluacion.getId(), evaluacion.getMotivo(), evaluacion.getEstadoDelCaso(),
						json.readTree(evaluacion.getVeredictos())));
			}
		}
		return new Leido(incidenteId, tipo, guardadas);
	}

	private static Repetida comparada(Guardada guardada, List<Veredicto> veredictos, boolean conModelo) {
		Map<String, String> antes = new LinkedHashMap<>();
		for (JsonNode veredicto : guardada.antes()) {
			antes.put(veredicto.path("condicion").asString(), veredicto.path("resultado").asString());
		}
		List<Map<String, Object>> ahora = new ArrayList<>();
		List<String> cambios = new ArrayList<>();
		for (Veredicto veredicto : veredictos) {
			Map<String, Object> actual = new LinkedHashMap<>();
			actual.put("condicion", veredicto.condicion());
			actual.put("resultado", veredicto.resultado().name());
			actual.put("fuente", veredicto.fuente() == null ? null : veredicto.fuente().name());
			actual.put("probabilidad", veredicto.probabilidad());
			ahora.add(actual);
			String anterior = antes.get(veredicto.condicion());
			if (anterior != null && !anterior.equals(veredicto.resultado().name())) {
				cambios.add(veredicto.condicion() + ": " + anterior + " → " + veredicto.resultado().name());
			}
		}
		return new Repetida(guardada.evaluacionId(), guardada.pedidaPor(), guardada.antes(), ahora, cambios, conModelo);
	}

}
