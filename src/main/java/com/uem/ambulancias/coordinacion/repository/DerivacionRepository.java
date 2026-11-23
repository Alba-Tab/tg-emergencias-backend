package com.uem.ambulancias.coordinacion.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.uem.ambulancias.coordinacion.domain.Derivacion;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DerivacionRepository extends JpaRepository<Derivacion, Long> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select d from Derivacion d where d.id = :id")
	Optional<Derivacion> buscarParaActualizar(@Param("id") Long id);

	/** Solo el incidente de una derivación: quien la va a cambiar bloquea primero el incidente. */
	@Query("select d.incidente.id from Derivacion d where d.id = :id")
	Optional<Long> buscarIncidenteId(@Param("id") Long id);

	boolean existsByIncidenteIdAndClave(Long incidenteId, String clave);

	/** Si el incidente tiene una derivación de ese motivo esperando respuesta. */
	@Query("""
			select count(d) > 0 from Derivacion d
			where d.incidente.id = :incidenteId and d.motivo = :motivo and d.estado = 'ABIERTA'
			""")
	boolean existeAbierta(@Param("incidenteId") Long incidenteId, @Param("motivo") String motivo);

	@Query("select d from Derivacion d where d.incidente.id = :incidenteId and d.estado = 'ABIERTA' order by d.id")
	List<Derivacion> buscarAbiertasDeIncidente(@Param("incidenteId") Long incidenteId);

	/** Las que ya deberían haber tenido respuesta. Solo los ids: cada una se relee bloqueada antes de vencerla. */
	@Query("select d.id from Derivacion d where d.estado = 'ABIERTA' and d.venceEn <= :ahora order by d.id")
	List<Long> buscarIdsVencidas(@Param("ahora") Instant ahora);

	/**
	 * Las que le hacen falta a una persona: las que esperan respuesta y las que vencieron sin que nadie las viera. La
	 * que vence antes va primero.
	 */
	@Query("""
			select d from Derivacion d join fetch d.incidente
			where d.estado = 'ABIERTA' or (d.estado = 'VENCIDA' and d.vistaEn is null)
			order by d.venceEn, d.id
			""")
	List<Derivacion> buscarQueNecesitanAtencion();

	@Query("""
			select count(d) from Derivacion d
			where d.estado = 'ABIERTA' or (d.estado = 'VENCIDA' and d.vistaEn is null)
			""")
	long contarQueNecesitanAtencion();

	/** Las más recientes, con cualquier estado, para el historial del panel. */
	@Query("select d from Derivacion d join fetch d.incidente order by d.id desc")
	List<Derivacion> buscarRecientes(Pageable limite);

	List<Derivacion> findByIncidenteIdOrderByIdAsc(Long incidenteId);

}
