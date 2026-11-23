package com.uem.ambulancias.coordinacion.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.uem.ambulancias.coordinacion.domain.EvaluacionDelCaso;
import com.uem.ambulancias.coordinacion.repository.EvaluacionDelCasoRepository;
import com.uem.ambulancias.emergencias.domain.Incidente;
import com.uem.ambulancias.emergencias.repository.AtencionRepository;
import com.uem.ambulancias.emergencias.repository.IncidenteRepository;
import com.uem.ambulancias.emergencias.service.NovedadEnLaBitacora;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * La cola de evaluaciones. Cada dato nuevo sobre lo que pasa en un incidente pide evaluar otra vez sus condiciones.
 * Pedir va en la transacción de quien anotó el dato, así la evaluación existe si y solo si el dato se guardó. Tomar va
 * en su transacción corta: la consulta al modelo queda afuera.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ColaDeEvaluaciones {

	private final EvaluacionDelCasoRepository evaluaciones;
	private final IncidenteRepository incidentes;
	private final AtencionRepository atenciones;
	private final CoordinacionProperties config;

	/**
	 * Se anotó algo en la bitácora. Si es un dato del suceso y el incidente tiene un tipo que el protocolo conoce, se
	 * pide una evaluación. Si ya hay una esperando alcanza con esa: cuando corra va a mirar todo lo que haya en ese
	 * momento. Una que ya está en curso no cuenta, porque juntó sus datos antes de esta novedad.
	 */
	@EventListener
	@Transactional
	public void alAnotarse(NovedadEnLaBitacora novedad) {
		if (!novedad.tipo().aportaDatosDelSuceso()) {
			return;
		}
		// Bloqueado, para que dos novedades simultáneas no dejen dos evaluaciones en la cola.
		Incidente incidente = incidentes.buscarParaActualizar(novedad.incidenteId()).orElse(null);
		if (incidente == null || !seEvalua(incidente)) {
			return;
		}
		Instant ahora = Instant.now();
		if (evaluaciones.existePendiente(incidente.getId())) {
			// La que esperaba para más tarde se adelanta: llegó algo nuevo y no hay por qué esperar.
			evaluaciones.adelantarPendientes(incidente.getId(), novedad.tipo().name(), ahora);
			return;
		}
		evaluaciones.save(EvaluacionDelCaso.pedir(incidente, novedad.tipo().name(), ahora));
	}

	/**
	 * Pide volver a evaluar el incidente más tarde, aunque no llegue nada nuevo: es el reloj con el que se nota que el
	 * testigo no contestó. Si ya hay una evaluación esperando, alcanza con esa. El incidente llega bloqueado.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void pedirParaDespues(Incidente incidente, String motivo, Instant desde) {
		if (!evaluaciones.existePendiente(incidente.getId())) {
			evaluaciones.save(EvaluacionDelCaso.pedirPara(incidente, motivo, Instant.now(), desde));
		}
	}

	/**
	 * Se evalúa el incidente abierto que tiene un tipo de suceso declarado y al que todavía no llegó ninguna unidad.
	 * Desde que una tripulación está en el lugar, lo que hace falta lo dice ella.
	 */
	public boolean seEvalua(Incidente incidente) {
		return motivoParaNoEvaluar(incidente) == null;
	}

	/** Por qué no corresponde evaluarlo, o {@code null} si corresponde. */
	public String motivoParaNoEvaluar(Incidente incidente) {
		if (!incidente.getEstado().isAbierto()) {
			return "incidente_cerrado";
		}
		if (incidente.getTipoSuceso() == null || incidente.getProtocolo() == null) {
			return "sin_tipo_de_suceso";
		}
		return atenciones.existeLlegadaVigentePorIncidente(incidente.getId()) ? "unidad_en_el_lugar" : null;
	}

	/**
	 * Toma hasta {@code limite} evaluaciones y las deja EN_CURSO. Una que vuelve a la cola porque su evaluador se cayó
	 * y ya gastó todos sus intentos no se toma: se da por fallida.
	 */
	@Transactional
	public List<Long> tomar(int limite) {
		Instant ahora = Instant.now();
		List<Long> tomadas = new ArrayList<>();
		for (EvaluacionDelCaso evaluacion : evaluaciones.tomarDisponibles(ahora, limite)) {
			if (evaluacion.isEnCurso() && evaluacion.agotoIntentos(config.maximoIntentos())) {
				log.warn("Evaluación {} abandonada a mitad demasiadas veces: se da por fallida.", evaluacion.getId());
				evaluacion.fallar("evaluador_sin_respuesta", ahora);
				continue;
			}
			evaluacion.tomar(ahora, config.bloqueo());
			tomadas.add(evaluacion.getId());
		}
		return tomadas;
	}

	/** Falló algo de este lado: vuelve a la cola, salvo que ya no le queden intentos. */
	@Transactional
	public void reintentar(Long evaluacionId, String error) {
		evaluaciones.buscarParaActualizar(evaluacionId).ifPresent(evaluacion -> {
			Instant ahora = Instant.now();
			if (!evaluacion.isEnCurso()) {
				return;
			}
			if (evaluacion.agotoIntentos(config.maximoIntentos())) {
				log.warn("Evaluación {} sin más intentos tras {}: se da por fallida.", evaluacionId, error);
				evaluacion.fallar(error, ahora);
			} else {
				evaluacion.reintentar(error, ahora, config.esperaTras(evaluacion.getIntentos()));
			}
		});
	}

}
