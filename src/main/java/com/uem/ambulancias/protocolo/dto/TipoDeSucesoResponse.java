package com.uem.ambulancias.protocolo.dto;

import com.uem.ambulancias.protocolo.domain.TipoDeSuceso;

/** Un tipo de suceso como se le ofrece al ciudadano: el código que manda con la alerta y el nombre que lee. */
public record TipoDeSucesoResponse(String codigo, String nombre) {

	public static TipoDeSucesoResponse de(TipoDeSuceso tipo) {
		return new TipoDeSucesoResponse(tipo.getCodigo(), tipo.getNombre());
	}

}
