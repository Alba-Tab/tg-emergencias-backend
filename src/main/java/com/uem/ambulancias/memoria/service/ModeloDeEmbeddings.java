package com.uem.ambulancias.memoria.service;

import java.util.List;

/**
 * El modelo que convierte textos en vectores que representan su significado, para buscar por parecido. No decide ni
 * redacta nada.
 *
 * <p>Se llama siempre fuera de una transacción. Si no responde bien, lanza
 * {@link com.uem.ambulancias.evidencias.service.FalloDelServicioIa} con lo que conviene hacer.
 */
public interface ModeloDeEmbeddings {

	/** Si la búsqueda por parecido está encendida. Apagada, todo funciona como antes: sin memoria ni norma indexada. */
	boolean listoParaEmbeber();

	/** Un vector por texto, en el mismo orden. Hasta 64 textos de 8000 caracteres por pedido. */
	VectoresRecibidos embeber(List<String> textos);

	/**
	 * @param vectores uno por texto, en el mismo orden
	 * @param modelo   el modelo que los produjo: dos vectores solo se comparan si salieron del mismo
	 */
	record VectoresRecibidos(List<float[]> vectores, String modelo) {
	}

}
