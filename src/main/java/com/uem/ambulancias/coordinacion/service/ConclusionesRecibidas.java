package com.uem.ambulancias.coordinacion.service;

import java.util.List;

import com.uem.ambulancias.coordinacion.domain.ConclusionDelAgente;

/**
 * Lo que respondió el agente: una conclusión por condición, qué herramientas usó para llegar a ellas y con qué
 * modelo razonó.
 */
public record ConclusionesRecibidas(List<Conclusion> conclusiones, List<String> herramientas, String proveedor,
		String modelo, String versionDelPrompt, Integer tokensDeEntrada, Integer tokensDeSalida, Integer pedidosAlModelo) {

	/**
	 * @param justificacion el porqué, en una o dos frases
	 * @param fuentes       en qué se apoya
	 */
	public record Conclusion(String condicion, ConclusionDelAgente veredicto, String justificacion,
			List<String> fuentes) {
	}

}
