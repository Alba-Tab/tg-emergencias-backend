package com.uem.ambulancias.coordinacion.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.uem.ambulancias.comun.error.NoEncontradoException;
import com.uem.ambulancias.coordinacion.domain.EvaluacionDelCaso;
import com.uem.ambulancias.coordinacion.domain.MetodoDeEvaluacion;
import com.uem.ambulancias.coordinacion.repository.EvaluacionDelCasoRepository;
import com.uem.ambulancias.emergencias.domain.Incidente;
import com.uem.ambulancias.emergencias.domain.OrigenDelEvento;
import com.uem.ambulancias.emergencias.domain.TipoEventoDelCaso;
import com.uem.ambulancias.emergencias.repository.IncidenteRepository;
import com.uem.ambulancias.emergencias.service.BitacoraDelCaso;
import com.uem.ambulancias.emergencias.service.RecursosDelIncidente;
import com.uem.ambulancias.evidencias.service.FalloDelServicioIa.Desenlace;
import com.uem.ambulancias.protocolo.domain.Condicion;
import com.uem.ambulancias.protocolo.domain.TipoDeSuceso;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Los pasos de una evaluación que tocan la base. Cada uno va en su transacción corta, con el incidente bloqueado
 * primero y la evaluación después: la consulta al modelo, que es lo lento, queda entre uno y otro, sin transacción.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluacionDelCasoService {

	private final EvaluacionDelCasoRepository evaluaciones;
	private final IncidenteRepository incidentes;
	private final ColaDeEvaluaciones cola;
	private final ArmadorDelEstado armador;
	private final BitacoraDelCaso bitacora;
	private final RecursosDelIncidente recursos;
	private final CoordinacionProperties config;
	private final JsonMapper json;

	/**
	 * Junta lo que se sabe del caso, aplica las reglas y dice qué hay que consultarle al modelo. Devuelve vacío cuando
	 * no queda nada para consultar: o las reglas cerraron todas las condiciones, o no hay modelo, o la evaluación ya
	 * no hacía falta. En esos casos la evaluación queda terminada acá.
	 *
	 * @param hayModelo si hay un modelo de decisión al que consultar
	 */
	@Transactional
	public Optional<PedidoDeEvaluacion> preparar(Long evaluacionId, boolean hayModelo) {
		Enlazada enlazada = bloquear(evaluacionId).orElse(null);
		if (enlazada == null) {
			return Optional.empty();
		}
		EvaluacionDelCaso evaluacion = enlazada.evaluacion();
		Incidente incidente = enlazada.incidente();
		Instant ahora = Instant.now();
		String motivo = cola.motivoParaNoEvaluar(incidente);
		if (motivo != null) {
			evaluacion.descartar(motivo, ahora);
			return Optional.empty();
		}
		TipoDeSuceso tipo = tipoDe(incidente);
		ArmadorDelEstado.EstadoArmado armado = armador.armar(incidente, tipo);
		String estadoJson = json.writeValueAsString(armado.estado());
		evaluacion.registrarEstado(estadoJson, armado.hastaEvento());

		List<Veredicto> porReglas = JuicioDelProtocolo.porReglas(tipo, armado.hechos());
		List<PedidoDeEvaluacion.Pregunta> abiertas = porReglas.stream()
				.filter(Veredicto::paraElModelo)
				.map(veredicto -> tipo.buscarCondicion(veredicto.condicion()).orElseThrow())
				.map(condicion -> new PedidoDeEvaluacion.Pregunta(condicion.getCodigo(), condicion.getEnunciado()))
				.toList();
		if (abiertas.isEmpty() || !hayModelo) {
			cerrar(evaluacion, incidente, tipo, porReglas, MetodoDeEvaluacion.REGLAS, ahora);
			return Optional.empty();
		}
		// Lo que las reglas ya resolvieron se aplica ahora, sin esperar al modelo: si el testigo dijo que hay alguien
		// atrapado, la unidad que eso pide sale ya.
		aplicarPlan(evaluacion, incidente, tipo, porReglas, true);
		return Optional.of(new PedidoDeEvaluacion(evaluacion.getReferencia(), incidente.getId(), estadoJson, abiertas));
	}

	/** Llegó la respuesta del modelo: se combinan sus probabilidades con lo que dejaron las reglas. */
	@Transactional
	public void concluir(Long evaluacionId, ProbabilidadesRecibidas recibidas, long milisegundos) {
		Enlazada enlazada = bloquear(evaluacionId).orElse(null);
		if (enlazada == null) {
			log.warn("La evaluación {} ya no estaba en curso al llegar la respuesta del modelo: se descarta.",
					evaluacionId);
			return;
		}
		EvaluacionDelCaso evaluacion = enlazada.evaluacion();
		Incidente incidente = enlazada.incidente();
		Instant ahora = Instant.now();
		evaluacion.registrarModelo(recibidas.proveedor(), recibidas.modelo(), recibidas.tokensDeEntrada(),
				recibidas.costo(), milisegundos);
		String motivo = cola.motivoParaNoEvaluar(incidente);
		if (motivo != null) {
			evaluacion.descartar(motivo, ahora);
			return;
		}
		TipoDeSuceso tipo = tipoDe(incidente);
		// Las reglas se vuelven a aplicar sobre el mismo estado que se guardó al preparar: lo que llegó después lo
		// mira la evaluación siguiente, que ya está en la cola.
		List<Veredicto> porReglas = JuicioDelProtocolo.porReglas(tipo, hechosDe(evaluacion));
		List<Veredicto> finales = JuicioDelProtocolo.conModelo(tipo, porReglas, recibidas.probabilidades());
		cerrar(evaluacion, incidente, tipo, finales, MetodoDeEvaluacion.MODELO, ahora);
	}

	/**
	 * El modelo no respondió. Si fue algo pasajero y quedan intentos, la evaluación vuelve a la cola con una espera
	 * corta. Si no, termina con lo que dejaron las reglas: una condición que solo el modelo podía resolver queda sin
	 * evaluar, y eso también es un resultado.
	 */
	@Transactional
	public void sinModelo(Long evaluacionId, String error, Desenlace desenlace) {
		Enlazada enlazada = bloquear(evaluacionId).orElse(null);
		if (enlazada == null) {
			return;
		}
		EvaluacionDelCaso evaluacion = enlazada.evaluacion();
		Incidente incidente = enlazada.incidente();
		Instant ahora = Instant.now();
		boolean pasajero = desenlace == Desenlace.REINTENTAR || desenlace == Desenlace.FIRMAR_DE_NUEVO;
		if (pasajero && !evaluacion.agotoIntentos(config.maximoIntentos())) {
			evaluacion.reintentar(error, ahora, config.esperaTras(evaluacion.getIntentos()));
			return;
		}
		if (desenlace == Desenlace.REVISION) {
			log.error("El modelo de decisión no está bien configurado ({}): la evaluación {} queda solo con las "
					+ "reglas.", error, evaluacionId);
		}
		evaluacion.anotarError(error);
		String motivo = cola.motivoParaNoEvaluar(incidente);
		if (motivo != null) {
			evaluacion.descartar(motivo, ahora);
			return;
		}
		TipoDeSuceso tipo = tipoDe(incidente);
		cerrar(evaluacion, incidente, tipo, JuicioDelProtocolo.porReglas(tipo, hechosDe(evaluacion)),
				MetodoDeEvaluacion.REGLAS_SIN_MODELO, ahora);
	}

	/** Las evaluaciones del incidente, de la más vieja a la más nueva. */
	@Transactional(readOnly = true)
	public List<EvaluacionDelCaso> deIncidente(Long incidenteId) {
		if (!incidentes.existsById(incidenteId)) {
			throw new NoEncontradoException("No existe el incidente " + incidenteId + ".");
		}
		return evaluaciones.findByIncidenteIdOrderByIdAsc(incidenteId);
	}

	private record Enlazada(EvaluacionDelCaso evaluacion, Incidente incidente) {
	}

	/** Bloquea el incidente y después la evaluación. Vacío si la evaluación ya no está en curso. */
	private Optional<Enlazada> bloquear(Long evaluacionId) {
		Long incidenteId = evaluaciones.buscarIncidenteId(evaluacionId).orElse(null);
		if (incidenteId == null) {
			return Optional.empty();
		}
		Incidente incidente = incidentes.buscarParaActualizar(incidenteId).orElse(null);
		EvaluacionDelCaso evaluacion = evaluaciones.buscarParaActualizar(evaluacionId).orElse(null);
		if (incidente == null || evaluacion == null || !evaluacion.isEnCurso()) {
			return Optional.empty();
		}
		return Optional.of(new Enlazada(evaluacion, incidente));
	}

	private static TipoDeSuceso tipoDe(Incidente incidente) {
		return incidente.getProtocolo().buscarTipo(incidente.getTipoSuceso())
				.orElseThrow(() -> new IllegalStateException("El incidente " + incidente.getId()
						+ " señala un tipo de suceso que su protocolo no tiene."));
	}

	/** Los hechos con los que se preparó la evaluación, leídos del estado que quedó guardado. */
	private JuicioDelProtocolo.Hechos hechosDe(EvaluacionDelCaso evaluacion) {
		return ArmadorDelEstado.hechosDe(json.readTree(evaluacion.getEstadoDelCaso()));
	}

	private void cerrar(EvaluacionDelCaso evaluacion, Incidente incidente, TipoDeSuceso tipo,
			List<Veredicto> veredictos, MetodoDeEvaluacion metodo, Instant ahora) {
		List<Map<String, Object>> detalle = new ArrayList<>();
		for (Veredicto veredicto : veredictos) {
			Condicion condicion = tipo.buscarCondicion(veredicto.condicion()).orElseThrow();
			detalle.add(BitacoraDelCaso.datos(
					"condicion", veredicto.condicion(),
					"resultado", veredicto.resultado().name(),
					"fuente", veredicto.fuente() == null ? null : veredicto.fuente().name(),
					"probabilidad", veredicto.probabilidad(),
					"respuestaDelTestigo",
					veredicto.respuestaDelTestigo() == null ? null : veredicto.respuestaDelTestigo().name(),
					"umbralActuar", condicion.getUmbralActuar(),
					"umbralPreguntar", condicion.getUmbralPreguntar()));
		}
		evaluacion.concluir(json.writeValueAsString(detalle), metodo, ahora);
		bitacora.anotar(incidente, null, TipoEventoDelCaso.CONDICIONES_EVALUADAS, OrigenDelEvento.SISTEMA, ahora,
				BitacoraDelCaso.datos(
						"evaluacionId", evaluacion.getId(),
						"pedidaPor", evaluacion.getMotivo(),
						"metodo", metodo.name(),
						"modelo", metodo == MetodoDeEvaluacion.MODELO ? evaluacion.getModelo() : null,
						"error", evaluacion.getUltimoError(),
						"hastaEvento", evaluacion.getHastaEvento(),
						"tipoDeSuceso", tipo.getCodigo(),
						"veredictos", detalle));
		aplicarPlan(evaluacion, incidente, tipo, veredictos, false);
	}

	/**
	 * Lleva al incidente lo que piden las condiciones que se cumplen. Solo sube lo que el incidente necesita: una
	 * evaluación posterior con menos condiciones cumplidas no le quita nada.
	 *
	 * @param adelantado si se aplica con lo que resolvieron las reglas, antes de que responda el modelo
	 */
	private void aplicarPlan(EvaluacionDelCaso evaluacion, Incidente incidente, TipoDeSuceso tipo,
			List<Veredicto> veredictos, boolean adelantado) {
		PlanDeRespuesta plan = JuicioDelProtocolo.plan(tipo, veredictos);
		recursos.requerir(incidente, plan.unidades(), plan.tipoMinimo(), BitacoraDelCaso.datos(
				"evaluacionId", evaluacion.getId(),
				"porCondiciones", plan.porCondiciones(),
				"antesDelModelo", adelantado ? true : null));
	}

}
