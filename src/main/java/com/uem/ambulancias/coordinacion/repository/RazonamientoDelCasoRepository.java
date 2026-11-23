package com.uem.ambulancias.coordinacion.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.uem.ambulancias.coordinacion.domain.RazonamientoDelCaso;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RazonamientoDelCasoRepository extends JpaRepository<RazonamientoDelCaso, Long> {

	/** Los próximos que se pueden tomar: los pendientes a los que les llegó la hora y los que un worker dejó a medias. */
	@Query(value = """
			select * from razonamiento_del_caso
			where (estado = 'PENDIENTE' and proximo_intento <= :ahora)
			   or (estado = 'EN_CURSO' and bloqueado_hasta < :ahora)
			order by proximo_intento, id
			limit :limite
			for update skip locked
			""", nativeQuery = true)
	List<RazonamientoDelCaso> tomarDisponibles(@Param("ahora") Instant ahora, @Param("limite") int limite);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from RazonamientoDelCaso r where r.id = :id")
	Optional<RazonamientoDelCaso> buscarParaActualizar(@Param("id") Long id);

	@Query("select r.incidente.id from RazonamientoDelCaso r where r.id = :id")
	Optional<Long> buscarIncidenteId(@Param("id") Long id);

	/** Si el incidente tiene un razonamiento esperando o en curso. */
	@Query("""
			select count(r) > 0 from RazonamientoDelCaso r
			where r.incidente.id = :incidenteId and r.estado in ('PENDIENTE', 'EN_CURSO')
			""")
	boolean existeEnVuelo(@Param("incidenteId") Long incidenteId);

	List<RazonamientoDelCaso> findByIncidenteIdOrderByIdAsc(Long incidenteId);

}
