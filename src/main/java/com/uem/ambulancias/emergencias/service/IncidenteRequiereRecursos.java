package com.uem.ambulancias.emergencias.service;

/**
 * El incidente pasó a necesitar más unidades o una de más capacidad. Se atiende después del commit: en modo
 * asignación el sistema le asigna el caso a otra unidad; en modo difusión se avisa a las de turno que hace falta apoyo.
 */
public record IncidenteRequiereRecursos(Long incidenteId) {
}
