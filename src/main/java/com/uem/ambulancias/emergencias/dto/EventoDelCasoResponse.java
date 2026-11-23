package com.uem.ambulancias.emergencias.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.uem.ambulancias.emergencias.domain.EventoDelCaso;
import com.uem.ambulancias.emergencias.domain.OrigenDelEvento;
import com.uem.ambulancias.emergencias.domain.TipoEventoDelCaso;

/**
 * Un renglón de la bitácora del caso.
 *
 * @param datos el contenido del evento tal como se anotó; sus campos dependen del tipo
 */
public record EventoDelCasoResponse(Long id, TipoEventoDelCaso tipo, OrigenDelEvento origen, Long alertaId,
		Instant ocurridoEn, Instant registradoEn, @JsonRawValue String datos) {

	public static EventoDelCasoResponse de(EventoDelCaso evento) {
		return new EventoDelCasoResponse(evento.getId(), evento.getTipo(), evento.getOrigen(),
				evento.getAlerta() == null ? null : evento.getAlerta().getId(), evento.getOcurridoEn(),
				evento.getRegistradoEn(), evento.getDatos());
	}

}
