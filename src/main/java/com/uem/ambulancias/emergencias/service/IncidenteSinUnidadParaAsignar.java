package com.uem.ambulancias.emergencias.service;

/**
 * No quedó ninguna unidad a la que asignarle el incidente: se lo abre a todas las de turno, como en el modo de
 * siempre, para que lo tome la que pueda.
 */
public record IncidenteSinUnidadParaAsignar(Long incidenteId) {
}
