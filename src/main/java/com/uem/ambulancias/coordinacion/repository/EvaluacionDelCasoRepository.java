package com.uem.ambulancias.coordinacion.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.uem.ambulancias.coordinacion.domain.EvaluacionDelCaso;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EvaluacionDelCasoRepository extends JpaRepository<EvaluacionDelCaso, Long> {

	/**
	 * Las próximas que se pueden tomar: las pendientes a las que ya les llegó la hora y las que un evaluador dejó a
	 * medias y ya vencieron. {@code skip locked} hace que dos evaluadores a la vez se repartan la cola.
	 */
	@Query(value = """
			select * from evaluacion_del_caso
			where (estado = 'PENDIENTE' and proximo_intento <= :ahora)
			   or (estado = 'EN_CURSO' and bloqueado_hasta < :ahora)
			order by proximo_intento, id
			limit :limite
			for update skip locked
			""", nativeQuery = true)
	List<EvaluacionDelCaso> tomarDisponibles(@Param("ahora") Instant ahora, @Param("limite") int limite);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select e from EvaluacionDelCaso e where e.id = :id")
	Optional<EvaluacionDelCaso> buscarParaActualizar(@Param("id") Long id);

	/** Solo el incidente de una evaluación: quien la va a cerrar bloquea primero el incidente. */
	@Query("select e.incidente.id from EvaluacionDelCaso e where e.id = :id")
	Optional<Long> buscarIncidenteId(@Param("id") Long id);

	@Query("select count(e) > 0 from EvaluacionDelCaso e where e.incidente.id = :incidenteId and e.estado = 'PENDIENTE'")
	boolean existePendiente(@Param("incidenteId") Long incidenteId);

	/**
	 * Las pendientes del incidente que estaban programadas para más tarde pasan a poder tomarse ya, y queda como motivo
	 * lo que las adelantó.
	 */
	@Modifying
	@Query("""
			update EvaluacionDelCaso e set e.proximoIntento = :ahora, e.motivo = :motivo
			where e.incidente.id = :incidenteId and e.estado = 'PENDIENTE' and e.proximoIntento > :ahora
			""")
	int adelantarPendientes(@Param("incidenteId") Long incidenteId, @Param("motivo") String motivo,
			@Param("ahora") Instant ahora);

	/** Las evaluaciones del incidente, de la más vieja a la más nueva. */
	List<EvaluacionDelCaso> findByIncidenteIdOrderByIdAsc(Long incidenteId);

	/** La última que terminó con veredictos. */
	@Query("""
			select e from EvaluacionDelCaso e
			where e.incidente.id = :incidenteId and e.estado = 'HECHA'
			order by e.id desc limit 1
			""")
	Optional<EvaluacionDelCaso> buscarUltimaHecha(@Param("incidenteId") Long incidenteId);

}
