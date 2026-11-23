package com.uem.ambulancias.emergencias.service;

import com.uem.ambulancias.emergencias.domain.TipoEventoDelCaso;

/**
 * Se anotó algo en la bitácora de un incidente. Se publica dentro de la transacción que lo anota: quien lo escuche
 * trabaja en esa misma transacción, y lo que haga se guarda si y solo si el renglón se guardó.
 */
public record NovedadEnLaBitacora(Long incidenteId, TipoEventoDelCaso tipo) {
}
