package com.uem.ambulancias.revision.service;

import java.util.List;
import java.util.UUID;

/**
 * El analista: mira cómo funcionó el protocolo vigente y propone mejoras. Trabaja fuera de cualquier caso y no cambia
 * nada: sus propuestas las lee una persona.
 *
 * <p>Se llama solo desde el worker de las revisiones, siempre fuera de una transacción. Si no responde bien, lanza
 * {@link com.uem.ambulancias.evidencias.service.FalloDelServicioIa}.
 */
public interface AnalistaDelProtocolo {

	/** Si el analista está encendido. Apagado, no se le piden revisiones. */
	boolean listoParaRevisar();

	PropuestasRecibidas revisar(PedidoDeRevision pedido);

	/**
	 * Algo que pasó al usar el protocolo, ya contado. {@code casos} dice sobre cuántos casos se contó: con pocos no
	 * se puede proponer cambiar un umbral.
	 */
	record Hecho(String id, String tipoDeSuceso, String condicion, String texto, int casos) {
	}

	/** Un extracto de la norma que puede respaldar una propuesta. */
	record ExtractoDeNorma(String id, String cita, String texto) {
	}

	/** @param protocoloJson el protocolo vigente con el contrato del servicio de IA */
	record PedidoDeRevision(UUID trabajo, String protocoloJson, List<Hecho> hechos, List<ExtractoDeNorma> norma) {
	}

	/**
	 * @param tipo   {@code adjust_threshold}, {@code change_question}, {@code change_response},
	 *               {@code add_condition}, {@code remove_condition} u {@code other}
	 * @param hechos los ids de los hechos que la respaldan
	 * @param norma  los ids de los extractos de la norma que cita
	 */
	record Propuesta(String tipo, String tipoDeSuceso, String condicion, String propuesta, String porQue,
			List<String> hechos, List<String> norma) {
	}

	/** @param descartadas cuántas propuestas del modelo no pasaron las reglas del servicio de IA */
	record PropuestasRecibidas(List<Propuesta> propuestas, int descartadas, String proveedor, String modelo,
			String versionDelPrompt) {
	}

}
