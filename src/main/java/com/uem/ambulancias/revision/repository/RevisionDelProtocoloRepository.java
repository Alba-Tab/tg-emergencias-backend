package com.uem.ambulancias.revision.repository;

import java.util.List;

import com.uem.ambulancias.revision.domain.RevisionDelProtocolo;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RevisionDelProtocoloRepository extends JpaRepository<RevisionDelProtocolo, Long> {

	@Query("select r.id from RevisionDelProtocolo r where r.estado in ('PENDIENTE', 'EN_CURSO') order by r.id")
	List<Long> buscarIdsPorHacer();

	@Query("select r from RevisionDelProtocolo r order by r.id desc")
	List<RevisionDelProtocolo> buscarRecientes(Pageable limite);

	/** Cuántos incidentes de cada tipo de suceso nacieron con ese protocolo. */
	@Query("""
			select i.tipoSuceso, count(i) from Incidente i
			where i.protocolo.id = :protocoloId and i.tipoSuceso is not null
			group by i.tipoSuceso
			""")
	List<Object[]> contarIncidentesPorTipo(@Param("protocoloId") Long protocoloId);

	/** El tipo de suceso y los veredictos de la última evaluación terminada de cada incidente de ese protocolo. */
	@Query("""
			select e.incidente.tipoSuceso, e.veredictos from EvaluacionDelCaso e
			where e.estado = 'HECHA' and e.incidente.protocolo.id = :protocoloId
			  and e.id = (select max(o.id) from EvaluacionDelCaso o
			              where o.incidente = e.incidente and o.estado = 'HECHA')
			""")
	List<Object[]> buscarUltimosVeredictos(@Param("protocoloId") Long protocoloId);

	/** Tipo de suceso y datos de cada respuesta de un testigo en los incidentes de ese protocolo. */
	@Query("""
			select ev.incidente.tipoSuceso, ev.datos from EventoDelCaso ev
			where ev.tipo = 'RESPUESTA_DEL_TESTIGO' and ev.incidente.protocolo.id = :protocoloId
			""")
	List<Object[]> buscarRespuestasDeTestigos(@Param("protocoloId") Long protocoloId);

	/** Tipo de suceso, condición, estado y opción elegida de cada derivación por una condición. */
	@Query("""
			select d.incidente.tipoSuceso, d.condicion, d.estado, d.opcionElegida from Derivacion d
			where d.condicion is not null and d.incidente.protocolo.id = :protocoloId
			""")
	List<Object[]> buscarDerivacionesPorCondicion(@Param("protocoloId") Long protocoloId);

}
