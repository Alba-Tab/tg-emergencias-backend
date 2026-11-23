package com.uem.ambulancias.emergencias.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
import org.springframework.stereotype.Service;
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
