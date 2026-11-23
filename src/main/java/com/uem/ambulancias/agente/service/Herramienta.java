package com.uem.ambulancias.agente.service;

import java.util.Map;
import java.util.function.Function;

import tools.jackson.databind.JsonNode;

/**
 * Algo que el agente puede pedirle a la central.
 *
 * @param nombre      con el que la llama el modelo
 * @param descripcion para qué sirve y cuándo conviene usarla: es lo que lee el modelo para elegirla
 * @param esquema     el JSON Schema de sus argumentos
 * @param accion      lo que hace; recibe los argumentos y devuelve algo que se puede escribir como JSON
 * @param soloLee     si no cambia nada en la central
 */
public record Herramienta(String nombre, String titulo, String descripcion, Map<String, Object> esquema,
		Function<JsonNode, Object> accion, boolean soloLee) {
}
