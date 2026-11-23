package com.uem.ambulancias.emergencias.service;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.uem.ambulancias.comun.error.CodigoError;
import com.uem.ambulancias.comun.error.ConflictoException;
import com.uem.ambulancias.comun.error.NoEncontradoException;
import com.uem.ambulancias.emergencias.domain.Alerta;
import com.uem.ambulancias.emergencias.domain.EventoDelCaso;
import com.uem.ambulancias.emergencias.domain.Incidente;
import com.uem.ambulancias.emergencias.domain.OrigenDelEvento;
import com.uem.ambulancias.emergencias.domain.RespuestaDelTestigo;
import com.uem.ambulancias.emergencias.domain.TipoEventoDelCaso;
import com.uem.ambulancias.emergencias.repository.AlertaRepository;
import com.uem.ambulancias.emergencias.repository.AtencionRepository;
import com.uem.ambulancias.emergencias.repository.EventoDelCasoRepository;
import com.uem.ambulancias.emergencias.repository.IncidenteRepository;
import com.uem.ambulancias.protocolo.domain.TipoDeSuceso;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Las respuestas del ciudadano al formulario de su tipo de suceso. No tienen tabla propia: cada respuesta es un
 * renglón de la bitácora del caso, y lo que está contestado ahora se deduce de ahí, tomando la última de cada
 * condición.
 */
@Service
@RequiredArgsConstructor
public class FormularioDelCaso {

	private final AlertaRepository alertas;
	private final IncidenteRepository incidentes;
	private final AtencionRepository atenciones;
	private final EventoDelCasoRepository eventos;
	private final BitacoraDelCaso bitacora;
	private final ApplicationEventPublisher novedades;
	private final JsonMapper json;

	/**
	 * Anota las respuestas, todas o ninguna. Se puede contestar mientras ninguna unidad haya llegado al lugar: desde
	 * ahí manda lo que diga la tripulación. Solo se contestan las condiciones que el protocolo del incidente
	 * pregunta; las demás se deducen de la evidencia.
	 */
	@Transactional
	public Map<String, RespuestaDelTestigo> responder(Long alertaId, Long ciudadanoId,
			List<RespuestaALaCondicion> respuestas) {
		Long incidenteId = alertas.buscarIncidenteId(alertaId)
				.orElseThrow(() -> new NoEncontradoException("No existe la alerta " + alertaId + "."));
		Incidente incidente = incidentes.buscarParaActualizar(incidenteId)
				.orElseThrow(() -> new NoEncontradoException("No existe el incidente " + incidenteId + "."));
		Alerta alerta = alertaDe(alertaId, ciudadanoId);
		if (!incidente.getEstado().isAbierto()) {
			throw new ConflictoException(CodigoError.DETALLES_NO_EDITABLES,
					"El incidente " + incidenteId + " ya está cerrado.");
		}
		if (atenciones.existeLlegadaVigentePorIncidente(incidenteId)) {
			throw new ConflictoException(CodigoError.DETALLES_NO_EDITABLES,
					"Una unidad ya llegó al lugar del incidente " + incidenteId + ".");
		}
		if (incidente.getTipoSuceso() == null) {
			throw new ConflictoException(CodigoError.SIN_TIPO_DE_SUCESO,
					"El incidente " + incidenteId + " no tiene un tipo de suceso declarado.");
		}
		TipoDeSuceso tipo = incidente.getProtocolo().buscarTipo(incidente.getTipoSuceso())
				.orElseThrow(() -> new IllegalStateException("El incidente " + incidenteId
						+ " señala un tipo de suceso que su protocolo no tiene."));
		for (RespuestaALaCondicion respuesta : respuestas) {
			boolean sePregunta = tipo.buscarCondicion(respuesta.condicion())
					.filter(condicion -> condicion.getPregunta() != null)
					.isPresent();
			if (!sePregunta) {
				throw new ConflictoException(CodigoError.CONDICION_DESCONOCIDA, "El formulario de "
						+ tipo.getCodigo() + " no pregunta por " + respuesta.condicion() + ".");
			}
		}

		Instant ahora = Instant.now();
		for (RespuestaALaCondicion respuesta : respuestas) {
			bitacora.anotar(incidente, alerta, TipoEventoDelCaso.RESPUESTA_DEL_TESTIGO, OrigenDelEvento.CIUDADANO,
					ahora, BitacoraDelCaso.datos(
							"condicion", respuesta.condicion(),
							"valor", respuesta.valor().name()));
		}
		return vigentes(alertaId);
	}

	/** Lo que el ciudadano tiene contestado en esa alerta, para que la app lo muestre al volver a abrirla. */
	@Transactional(readOnly = true)
	public Map<String, RespuestaDelTestigo> respuestasDe(Long alertaId, Long ciudadanoId) {
		alertaDe(alertaId, ciudadanoId);
		return vigentes(alertaId);
	}

	/**
	 * El sistema necesita que el testigo conteste esas condiciones: quedaron en duda y tienen pregunta. Cada condición
	 * se pide una sola vez por incidente; si alguna es nueva queda anotada y se avisa a quienes alertaron. El
	 * incidente llega bloqueado por quien llama. Devuelve las que se pidieron ahora.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public List<String> preguntar(Incidente incidente, List<String> condiciones, Long evaluacionId) {
		if (condiciones.isEmpty()) {
			return List.of();
		}
		Set<String> yaPedidas = pedidasDesde(incidente.getId()).keySet();
		List<String> nuevas = condiciones.stream().filter(condicion -> !yaPedidas.contains(condicion)).toList();
		if (nuevas.isEmpty()) {
			return nuevas;
		}
		bitacora.anotar(incidente, null, TipoEventoDelCaso.PREGUNTA_AL_TESTIGO, OrigenDelEvento.SISTEMA,
				Instant.now(), BitacoraDelCaso.datos(
						"condiciones", nuevas,
						"evaluacionId", evaluacionId));
		novedades.publishEvent(new NovedadDelIncidente(incidente.getId(), NovedadDelIncidente.Tipo.PREGUNTA_PENDIENTE));
		return nuevas;
	}

	/**
	 * El formulario de esa alerta: las preguntas del tipo de suceso de su incidente, con lo que el ciudadano ya
	 * contestó. Primero van las que el sistema pidió y él todavía no contestó; dentro de cada grupo, en el orden del
	 * protocolo. Vacío si el incidente no tiene un tipo de suceso declarado.
	 */
	@Transactional(readOnly = true)
	public List<PreguntaDelCaso> preguntasDe(Long alertaId, Long ciudadanoId) {
		Alerta alerta = alertaDe(alertaId, ciudadanoId);
		Incidente incidente = alerta.getIncidente();
		if (incidente == null || incidente.getTipoSuceso() == null) {
			return List.of();
		}
		TipoDeSuceso tipo = incidente.getProtocolo().buscarTipo(incidente.getTipoSuceso())
				.orElseThrow(() -> new IllegalStateException("El incidente " + incidente.getId()
						+ " señala un tipo de suceso que su protocolo no tiene."));
		Map<String, RespuestaDelTestigo> contestadas = vigentes(alertaId);
		Set<String> pedidas = pedidasDesde(incidente.getId()).keySet();
		return tipo.conPregunta().stream()
				.map(condicion -> new PreguntaDelCaso(condicion.getCodigo(), condicion.getPregunta(),
						contestadas.get(condicion.getCodigo()),
						pedidas.contains(condicion.getCodigo()) && !contestadas.containsKey(condicion.getCodigo())))
				.sorted(Comparator.comparing(pregunta -> !pregunta.prioritaria()))
				.toList();
	}

	/**
	 * Las condiciones que el sistema ya le pidió contestar a los testigos de ese incidente, cada una con el momento en
	 * que se pidió. Exige una transacción abierta.
	 */
	public Map<String, Instant> pedidasDesde(Long incidenteId) {
		Map<String, Instant> pedidas = new LinkedHashMap<>();
		for (EventoDelCaso evento : eventos.findByIncidenteIdAndTipoOrderByIdAsc(incidenteId,
				TipoEventoDelCaso.PREGUNTA_AL_TESTIGO.name())) {
			for (JsonNode condicion : json.readTree(evento.getDatos()).path("condiciones")) {
				pedidas.putIfAbsent(condicion.asString(), evento.getOcurridoEn());
			}
		}
		return pedidas;
	}

	private Alerta alertaDe(Long alertaId, Long ciudadanoId) {
		Alerta alerta = alertas.findById(alertaId)
				.orElseThrow(() -> new NoEncontradoException("No existe la alerta " + alertaId + "."));
		if (!alerta.getEmisor().getId().equals(ciudadanoId)) {
			throw new ConflictoException(CodigoError.ALERTA_AJENA,
					"La alerta " + alertaId + " no es del ciudadano " + ciudadanoId + ".");
		}
		return alerta;
	}

	/** La última respuesta de cada condición, en el orden en que se contestó por primera vez. */
	private Map<String, RespuestaDelTestigo> vigentes(Long alertaId) {
		Map<String, RespuestaDelTestigo> vigentes = new LinkedHashMap<>();
		for (EventoDelCaso evento : eventos.findByAlertaIdAndTipoOrderByIdAsc(alertaId,
				TipoEventoDelCaso.RESPUESTA_DEL_TESTIGO.name())) {
			JsonNode datos = json.readTree(evento.getDatos());
			vigentes.put(datos.path("condicion").asString(),
					RespuestaDelTestigo.valueOf(datos.path("valor").asString()));
		}
		return vigentes;
	}

}
