package com.uem.ambulancias.coordinacion.service;

import com.uem.ambulancias.coordinacion.domain.FuenteDelVeredicto;
import com.uem.ambulancias.coordinacion.domain.ResultadoDeCondicion;
import com.uem.ambulancias.emergencias.domain.RespuestaDelTestigo;

/**
 * En qué quedó una condición del protocolo y por qué.
 *
 * @param fuente              de dónde salió; vacío si nadie la pudo evaluar
 * @param probabilidad        la que dio el modelo, cuando el veredicto es suyo
 * @param respuestaDelTestigo lo que contestó quien está en el lugar, si contestó
 * @param paraElModelo        si las reglas no alcanzan para cerrarla y hay que consultarla al modelo
 */
public record Veredicto(String condicion, ResultadoDeCondicion resultado, FuenteDelVeredicto fuente,
		Double probabilidad, RespuestaDelTestigo respuestaDelTestigo, boolean paraElModelo) {

	public boolean cumplida() {
		return resultado == ResultadoDeCondicion.CUMPLIDA;
	}

}
