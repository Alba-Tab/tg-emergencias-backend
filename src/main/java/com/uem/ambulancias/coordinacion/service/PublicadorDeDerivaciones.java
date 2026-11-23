package com.uem.ambulancias.coordinacion.service;

/**
 * Aviso en tiempo real para el panel de la central. Solo dice cuántas derivaciones le quedan por atender: con eso el
 * panel vuelve a pedir la lista, que es donde está el contenido. Se llama siempre después del commit.
 */
public interface PublicadorDeDerivaciones {

	void publicarPendientes(long cantidad);

}
