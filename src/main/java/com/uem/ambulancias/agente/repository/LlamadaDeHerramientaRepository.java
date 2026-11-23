package com.uem.ambulancias.agente.repository;

import java.util.List;

import com.uem.ambulancias.agente.domain.LlamadaDeHerramienta;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LlamadaDeHerramientaRepository extends JpaRepository<LlamadaDeHerramienta, Long> {

	/** Lo que el agente pidió sobre ese incidente, en orden. */
	List<LlamadaDeHerramienta> findByIncidenteIdOrderByIdAsc(Long incidenteId);

}
