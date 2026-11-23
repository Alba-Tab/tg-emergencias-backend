package com.uem.ambulancias.medicion.repository;

import java.util.List;
import java.util.Optional;

import com.uem.ambulancias.medicion.domain.Medicion;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MedicionRepository extends JpaRepository<Medicion, Long> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select m from Medicion m where m.id = :id")
	Optional<Medicion> buscarParaActualizar(@Param("id") Long id);

	/** Las que esperan o quedaron a medias, de la más vieja a la más nueva. Solo los ids. */
	@Query("select m.id from Medicion m where m.estado in ('PENDIENTE', 'EN_CURSO') order by m.id")
	List<Long> buscarIdsPorHacer();

	List<Medicion> findAllByOrderByIdDesc();

}
