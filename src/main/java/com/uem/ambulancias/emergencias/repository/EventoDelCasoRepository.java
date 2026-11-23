package com.uem.ambulancias.emergencias.repository;

import java.util.List;

import com.uem.ambulancias.emergencias.domain.EventoDelCaso;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EventoDelCasoRepository extends JpaRepository<EventoDelCaso, Long> {

	/** La bitácora del incidente, en el orden en que se fue anotando. */
	List<EventoDelCaso> findByIncidenteIdOrderByIdAsc(Long incidenteId);

}
