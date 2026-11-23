package com.uem.ambulancias.protocolo.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Serializa los cambios al protocolo con un advisory lock de PostgreSQL, para que dos personas de la central no
 * abran dos borradores ni publiquen dos versiones a la vez.
 */
@Component
@RequiredArgsConstructor
public class BloqueoDeProtocolo {

	/** Clave fija del bloqueo; solo la usa la administración del protocolo. */
	private static final long CLAVE = 2_026_112_201L;

	private final JdbcTemplate jdbc;

	/** Espera el bloqueo exclusivo. PostgreSQL lo libera solo al terminar la transacción actual. */
	public void adquirir() {
		jdbc.execute("select pg_advisory_xact_lock(" + CLAVE + ")");
	}

}
