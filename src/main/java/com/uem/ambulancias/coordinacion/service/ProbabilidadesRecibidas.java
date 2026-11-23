package com.uem.ambulancias.coordinacion.service;

import java.util.Map;

/**
 * Lo que respondió el modelo de decisión: una probabilidad de 0 a 1 por cada condición consultada, y con qué la
 * calculó.
 */
public record ProbabilidadesRecibidas(Map<String, Double> probabilidades, String proveedor, String modelo,
		Integer tokensDeEntrada, Double costo) {
}
