package com.uem.ambulancias.emergencias.repository;

import java.util.List;

import com.uem.ambulancias.emergencias.domain.EventoDelCaso;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EventoDelCasoRepository extends JpaRepository<EventoDelCaso, Long> {

	/** La bitácora del incidente, en el orden en que se fue anotando. */
	List<EventoDelCaso> findByIncidenteIdOrderByIdAsc(Long incidenteId);

	/** Los renglones de un tipo que salieron de esa alerta, en orden. {@code tipo} es el nombre del enum. */
	List<EventoDelCaso> findByAlertaIdAndTipoOrderByIdAsc(Long alertaId, String tipo);

}
