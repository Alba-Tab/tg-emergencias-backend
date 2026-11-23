package com.uem.ambulancias.emergencias.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.uem.ambulancias.emergencias.domain.Incidente;
import com.uem.ambulancias.emergencias.domain.OrigenDelEvento;
import com.uem.ambulancias.emergencias.domain.TipoEventoDelCaso;
import com.uem.ambulancias.flota.domain.TipoUnidad;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lo que un incidente necesita: cuántas unidades y de qué tipo como mínimo. Nace en una unidad de cualquier tipo y
 * solo sube. El sistema suma recursos cuando el protocolo lo pide; quitar una unidad, cambiarla o cancelar el caso lo
 * decide siempre una persona.
 */
@Service
@RequiredArgsConstructor
public class RecursosDelIncidente {

	private final BitacoraDelCaso bitacora;
	private final ApplicationEventPublisher eventos;

	/**
	 * Sube lo que el incidente necesita, lo deja anotado con su porqué y avisa para que el despacho consiga lo que
	 * falta. Si lo pedido no supera a lo que ya tenía, no hace nada. El incidente llega bloqueado por quien llama.
	 *
	 * @param porQue lo que se suma al renglón de la bitácora: qué evaluación lo pidió y por qué condiciones
	 * @return si algo subió
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean requerir(Incidente incidente, int unidades, TipoUnidad tipoMinimo, Map<String, Object> porQue) {
		int unidadesAntes = incidente.getUnidadesRequeridas();
		TipoUnidad tipoAntes = incidente.getTipoMinimoRequerido();
		if (!incidente.requerir(unidades, tipoMinimo)) {
			return false;
		}
		TipoUnidad tipoAhora = incidente.getTipoMinimoRequerido();
		Map<String, Object> datos = new LinkedHashMap<>(BitacoraDelCaso.datos(
				"unidades", incidente.getUnidadesRequeridas(),
				"tipoMinimo", tipoAhora == null ? null : tipoAhora.name(),
				"unidadesAntes", unidadesAntes,
				"tipoMinimoAntes", tipoAntes == null ? null : tipoAntes.name()));
		datos.putAll(porQue);
		bitacora.anotar(incidente, null, TipoEventoDelCaso.PLAN_DE_RESPUESTA_ACTUALIZADO, OrigenDelEvento.SISTEMA,
				Instant.now(), datos);
		eventos.publishEvent(new IncidenteRequiereRecursos(incidente.getId()));
		eventos.publishEvent(new IncidenteActualizado(incidente.getId(), false));
		return true;
	}

}
