package com.uem.ambulancias.emergencias.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.uem.ambulancias.emergencias.domain.EventoDelCaso;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;

public interface EventoDelCasoRepository extends JpaRepository<EventoDelCaso, Long> {

	/** La bitácora del incidente, en el orden en que se fue anotando. */
	List<EventoDelCaso> findByIncidenteIdOrderByIdAsc(Long incidenteId);

	/** Los renglones de un tipo en todo el incidente, en orden. {@code tipo} es el nombre del enum. */
	List<EventoDelCaso> findByIncidenteIdAndTipoOrderByIdAsc(Long incidenteId, String tipo);

	/** El tipo del renglón más nuevo del incidente entre esos tipos, o vacío si no tiene ninguno. */
	@Query("""
			select e.tipo from EventoDelCaso e
			where e.incidente.id = :incidenteId and e.tipo in :tipos
			order by e.id desc limit 1
			""")
	Optional<String> buscarUltimoTipoEntre(@Param("incidenteId") Long incidenteId,
			@Param("tipos") Collection<String> tipos);

	/** Los renglones de un tipo que salieron de esa alerta, en orden. {@code tipo} es el nombre del enum. */
	List<EventoDelCaso> findByAlertaIdAndTipoOrderByIdAsc(Long alertaId, String tipo);

}
