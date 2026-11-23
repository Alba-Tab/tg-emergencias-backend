package com.uem.ambulancias.emergencias.service;

import com.uem.ambulancias.emergencias.domain.RespuestaDelTestigo;

/** Una respuesta del ciudadano: qué condición del protocolo contesta y qué contestó. */
public record RespuestaALaCondicion(String condicion, RespuestaDelTestigo valor) {
}
