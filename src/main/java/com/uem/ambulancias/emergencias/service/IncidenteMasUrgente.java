package com.uem.ambulancias.emergencias.service;

/**
 * La prioridad del incidente subió. Se atiende después del commit: si el caso esperaba una unidad, a la persona que
 * lo tiene que decidir se le muestra que ahora urge más.
 */
public record IncidenteMasUrgente(Long incidenteId) {
}
