package com.uem.ambulancias.memoria.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * La búsqueda por parecido ({@code sga.memoria.*}).
 *
 * @param similitudMinima por debajo de este coseno un resultado no se devuelve: es ruido, no parecido
 * @param fragmentos      cuántos extractos de la norma se devuelven como máximo por búsqueda
 * @param lote            cuántos textos se mandan a convertir por pedido, y cuántos casos se suman por barrido
 */
@ConfigurationProperties(prefix = "sga.memoria")
public record MemoriaProperties(
		@DefaultValue("0.25") double similitudMinima,
		@DefaultValue("4") int fragmentos,
		@DefaultValue("16") int lote) {
}
