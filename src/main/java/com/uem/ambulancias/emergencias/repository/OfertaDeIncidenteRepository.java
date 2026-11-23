package com.uem.ambulancias.emergencias.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.uem.ambulancias.emergencias.domain.OfertaDeIncidente;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OfertaDeIncidenteRepository extends JpaRepository<OfertaDeIncidente, Long> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select o from OfertaDeIncidente o where o.id = :id")
	Optional<OfertaDeIncidente> buscarParaActualizar(@Param("id") Long id);

	/** La asignación que esa unidad tiene esperando respuesta. A lo sumo hay una. */
	@Query("select o from OfertaDeIncidente o where o.ambulancia.id = :ambulanciaId and o.estado = 'PENDIENTE'")
	Optional<OfertaDeIncidente> buscarPendienteDeAmbulancia(@Param("ambulanciaId") Long ambulanciaId);

	/**
	 * Solo el id de la pendiente de esa unidad y solo el incidente de una asignación: quien las va a cambiar bloquea
	 * primero el incidente y después lee la asignación bloqueada, para no trabajar sobre una copia vieja.
	 */
	@Query("select o.id from OfertaDeIncidente o where o.ambulancia.id = :ambulanciaId and o.estado = 'PENDIENTE'")
	Optional<Long> buscarIdPendienteDeAmbulancia(@Param("ambulanciaId") Long ambulanciaId);

	@Query("select o.incidente.id from OfertaDeIncidente o where o.id = :id")
	Optional<Long> buscarIncidenteId(@Param("id") Long id);

	@Query("select o from OfertaDeIncidente o where o.incidente.id = :incidenteId and o.estado = 'PENDIENTE'")
	List<OfertaDeIncidente> buscarPendientesDeIncidente(@Param("incidenteId") Long incidenteId);

	@Query("select coalesce(max(o.ronda), 0) from OfertaDeIncidente o where o.incidente.id = :incidenteId")
	int ultimaRonda(@Param("incidenteId") Long incidenteId);

	/** A qué unidades ya se les ofreció el incidente en esa ronda: no se les vuelve a ofrecer. */
	@Query("select o.ambulancia.id from OfertaDeIncidente o where o.incidente.id = :incidenteId and o.ronda = :ronda")
	List<Long> buscarAmbulanciasOfrecidas(@Param("incidenteId") Long incidenteId, @Param("ronda") int ronda);

	/** Las unidades que ya tienen una asignación esperando respuesta, de cualquier incidente. */
	@Query("select o.ambulancia.id from OfertaDeIncidente o where o.estado = 'PENDIENTE'")
	List<Long> buscarAmbulanciasConOfertaPendiente();

	/** Las que ya deberían haber tenido respuesta. Solo los ids: cada una se relee bloqueada antes de vencerla. */
	@Query("select o.id from OfertaDeIncidente o where o.estado = 'PENDIENTE' and o.venceEn <= :ahora order by o.id")
	List<Long> buscarIdsVencidas(@Param("ahora") Instant ahora);

	/** A quiénes se les ofreció el incidente y qué pasó con cada una, en orden. */
	List<OfertaDeIncidente> findByIncidenteIdOrderByIdAsc(Long incidenteId);

}
