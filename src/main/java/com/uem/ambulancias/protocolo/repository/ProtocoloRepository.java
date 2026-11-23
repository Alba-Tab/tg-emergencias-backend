package com.uem.ambulancias.protocolo.repository;

import java.util.List;
import java.util.Optional;

import com.uem.ambulancias.protocolo.domain.EstadoProtocolo;
import com.uem.ambulancias.protocolo.domain.Protocolo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ProtocoloRepository extends JpaRepository<Protocolo, Long> {

	/** Todas las versiones, de la más nueva a la más vieja. */
	List<Protocolo> findAllByOrderByVersionDesc();

	/** Solo tiene sentido para BORRADOR y VIGENTE, que son a lo sumo uno cada uno. */
	Optional<Protocolo> findByEstado(EstadoProtocolo estado);

	@Query("select coalesce(max(p.version), 0) from Protocolo p")
	int ultimaVersion();

}
