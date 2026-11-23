package com.uem.ambulancias.emergencias.service;

import com.uem.ambulancias.emergencias.domain.RespuestaDelTestigo;

/**
 * Una pregunta del formulario de una alerta.
 *
 * @param condicion   el código con el que se manda la respuesta
 * @param respuesta   lo que el ciudadano contestó en esa alerta, o {@code null} si todavía no contestó
 * @param prioritaria si el sistema pidió esta respuesta porque la condición quedó en duda y sigue sin contestar
 */
public record PreguntaDelCaso(String condicion, String pregunta, RespuestaDelTestigo respuesta, boolean prioritaria) {
}
