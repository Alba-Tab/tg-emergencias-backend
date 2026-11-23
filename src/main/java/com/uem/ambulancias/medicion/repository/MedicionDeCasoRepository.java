package com.uem.ambulancias.medicion.repository;

import java.util.List;

import com.uem.ambulancias.medicion.domain.MedicionDeCaso;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MedicionDeCasoRepository extends JpaRepository<MedicionDeCaso, Long> {

	/** Lo medido en esa corrida, cada resultado con su caso. */
	@Query("select m from MedicionDeCaso m join fetch m.caso where m.medicion.id = :medicionId order by m.id")
	List<MedicionDeCaso> buscarDeMedicion(@Param("medicionId") Long medicionId);

}
