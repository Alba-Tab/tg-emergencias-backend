package com.uem.ambulancias.revision.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * La revisión del protocolo por el analista ({@code sga.revision.*}).
 *
 * @param minimoDeCasos con menos casos que esto detrás, una propuesta de cambiar un umbral no se toma
 * @param extractos     cuántos extractos de la norma se le mandan al analista como máximo
 */
@ConfigurationProperties(prefix = "sga.revision")
public record RevisionProperties(
		@DefaultValue("10") int minimoDeCasos,
		@DefaultValue("6") int extractos) {
}
