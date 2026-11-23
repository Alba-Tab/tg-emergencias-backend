package com.uem.ambulancias.emergencias.dto;

import java.time.Instant;

import com.uem.ambulancias.emergencias.domain.AjusteDeDespacho;
import com.uem.ambulancias.emergencias.domain.ModoDeDespacho;

/**
 * Cómo despacha la central ahora. {@code actualizadoEn} y {@code actualizadoPor} vienen nulos mientras rijan los
 * valores de la configuración.
 */
public record AjusteDeDespachoResponse(ModoDeDespacho modo, int segundosConfirmacion, Instant actualizadoEn,
		String actualizadoPor) {

	public static AjusteDeDespachoResponse de(AjusteDeDespacho ajuste) {
		return new AjusteDeDespachoResponse(ajuste.getModo(), ajuste.getSegundosConfirmacion(),
				ajuste.getActualizadoEn(),
				ajuste.getActualizadoPor() == null ? null : ajuste.getActualizadoPor().getNombreCompleto());
	}

}
