package com.uem.ambulancias.emergencias.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.uem.ambulancias.comun.error.NoEncontradoException;
import com.uem.ambulancias.emergencias.domain.Alerta;
import com.uem.ambulancias.emergencias.domain.EventoDelCaso;
import com.uem.ambulancias.emergencias.domain.Incidente;
import com.uem.ambulancias.emergencias.domain.OrigenDelEvento;
import com.uem.ambulancias.emergencias.domain.TipoEventoDelCaso;
import com.uem.ambulancias.emergencias.repository.EventoDelCasoRepository;
import com.uem.ambulancias.emergencias.repository.IncidenteRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * La bitácora del caso: todo lo que llega a un incidente queda anotado en orden, con quién lo aportó. Se anota en la
 * misma transacción que el cambio que lo origina, así nunca queda un dato guardado sin su renglón ni un renglón de
 * algo que al final no se guardó.
 */
@Service
@RequiredArgsConstructor
public class BitacoraDelCaso {

	private final EventoDelCasoRepository eventos;
	private final IncidenteRepository incidentes;
	private final JsonMapper json;
	private final ApplicationEventPublisher novedades;

	/**
	 * Arma los datos de un evento de a pares clave, valor. Un valor {@code null} no se incluye: en la bitácora, lo
	 * que no se sabe no figura.
	 */
	public static Map<String, Object> datos(Object... clavesYValores) {
		if (clavesYValores.length % 2 != 0) {
			throw new IllegalArgumentException("Faltan valores: van de a pares clave, valor.");
		}
		Map<String, Object> datos = new LinkedHashMap<>();
		for (int i = 0; i < clavesYValores.length; i += 2) {
			if (clavesYValores[i + 1] != null) {
				datos.put((String) clavesYValores[i], clavesYValores[i + 1]);
			}
		}
		return datos;
	}

	/**
	 * Agrega un renglón. Exige una transacción ya abierta: la del cambio que se está anotando. Avisa de la novedad
	 * dentro de esa misma transacción, para quien tenga que reaccionar a lo que se acaba de saber del caso.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public EventoDelCaso anotar(Incidente incidente, Alerta alerta, TipoEventoDelCaso tipo, OrigenDelEvento origen,
			Instant ocurridoEn, Map<String, Object> datos) {
		EventoDelCaso anotado = eventos.save(EventoDelCaso.anotar(incidente, alerta, tipo, origen,
				json.writeValueAsString(datos), ocurridoEn, Instant.now()));
		novedades.publishEvent(new NovedadEnLaBitacora(incidente.getId(), tipo));
		return anotado;
	}

	/** La bitácora completa del incidente, del renglón más viejo al más nuevo. */
	@Transactional(readOnly = true)
	public List<EventoDelCaso> deIncidente(Long incidenteId) {
		if (!incidentes.existsById(incidenteId)) {
			throw new NoEncontradoException("No existe el incidente " + incidenteId + ".");
		}
		return eventos.findByIncidenteIdOrderByIdAsc(incidenteId);
	}

}
