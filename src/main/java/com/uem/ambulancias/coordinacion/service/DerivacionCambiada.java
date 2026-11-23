package com.uem.ambulancias.coordinacion.service;

/**
 * Se abrió, se resolvió, venció, se retiró o se marcó como vista una derivación. Se atiende después del commit, para
 * avisarle al panel cuántas le quedan por atender.
 */
public record DerivacionCambiada(Long derivacionId) {
}
