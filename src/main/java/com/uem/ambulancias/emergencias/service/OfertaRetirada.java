package com.uem.ambulancias.emergencias.service;

/**
 * La asignación que una unidad tenía pendiente dejó de hacer falta: hay que avisarle a su tripulación que ya no
 * tiene que contestar.
 */
public record OfertaRetirada(Long incidenteId, Long ambulanciaId) {
}
