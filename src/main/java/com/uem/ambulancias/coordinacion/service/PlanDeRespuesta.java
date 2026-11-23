package com.uem.ambulancias.coordinacion.service;

import java.util.List;

import com.uem.ambulancias.flota.domain.TipoUnidad;
import com.uem.ambulancias.protocolo.domain.Prioridad;

/**
 * Lo que un incidente necesita según su protocolo, con lo que se sabe hasta ahora.
 *
 * @param unidades       cuántas unidades tienen que ir, contando la primera
 * @param tipoMinimo     el tipo de unidad que como mínimo tiene que ir, o {@code null} si alcanza cualquiera
 * @param prioridad      la prioridad que como mínimo toma el caso, o {@code null} si ninguna condición fija una
 * @param porCondiciones las condiciones cumplidas que piden algo más que la respuesta básica
 */
public record PlanDeRespuesta(int unidades, TipoUnidad tipoMinimo, Prioridad prioridad,
		List<String> porCondiciones) {
}
