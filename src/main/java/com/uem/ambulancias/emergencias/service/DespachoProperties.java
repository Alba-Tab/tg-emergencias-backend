package com.uem.ambulancias.emergencias.service;

import com.uem.ambulancias.emergencias.domain.ModoDeDespacho;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Con qué despacha la central mientras nadie lo cambie desde el panel ({@code sga.despacho.*}). El modo por defecto
 * es el de siempre: avisar a todas las unidades.
 */
@ConfigurationProperties(prefix = "sga.despacho")
public record DespachoProperties(

		@DefaultValue("DIFUSION") ModoDeDespacho modo,

		/** Cuánto se espera la confirmación de la tripulación antes de pasar a la unidad siguiente. */
		@DefaultValue("30") int segundosConfirmacion) {
}
