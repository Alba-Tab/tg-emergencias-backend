package com.uem.ambulancias.memoria.repository;

import java.util.List;

import com.uem.ambulancias.memoria.domain.MemoriaDelCaso;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemoriaDelCasoRepository extends JpaRepository<MemoriaDelCaso, Long> {

	/**
	 * Los incidentes ya cerrados que declararon un tipo de suceso y todavía no están en la memoria, del más viejo al
	 * más nuevo.
	 */
	@Query("""
			select i.id from Incidente i
			where i.estado not in ('ACTIVO', 'EN_ATENCION') and i.tipoSuceso is not null and i.protocolo is not null
			  and not exists (select m from MemoriaDelCaso m where m.incidente = i)
			order by i.id
			""")
	List<Long> buscarIncidentesSinMemoria(Pageable limite);

	/** Las que tienen resumen y todavía no tienen vector. */
	@Query("select m from MemoriaDelCaso m where m.texto is not null and m.vector is null order by m.id")
	List<MemoriaDelCaso> buscarSinVector(Pageable limite);

	/** Las que tienen un vector de otro modelo: no se pueden comparar con los nuevos. */
	@Query("""
			select m from MemoriaDelCaso m
			where m.texto is not null and m.vector is not null and m.modelo <> :modelo
			order by m.id
			""")
	List<MemoriaDelCaso> buscarDeOtroModelo(@Param("modelo") String modelo, Pageable limite);

	/** Las que se pueden comparar con un vector de ese modelo, sin contar la de un incidente. */
	@Query("""
			select m from MemoriaDelCaso m join fetch m.incidente
			where m.vector is not null and m.modelo = :modelo and m.incidente.id <> :excluido
			""")
	List<MemoriaDelCaso> buscarComparablesCon(@Param("modelo") String modelo, @Param("excluido") Long excluido);

	long countByVectorIsNotNull();

	/**
	 * Si hay algún incidente cerrado de ese tipo de suceso: son los casos que la memoria le muestra al agente, por
	 * parecido si ya tienen su vector o por tipo de suceso si no.
	 */
	@Query("""
			select count(i) > 0 from Incidente i
			where i.tipoSuceso = :tipoSuceso and i.estado not in ('ACTIVO', 'EN_ATENCION')
			""")
	boolean existenCasosDelTipo(@Param("tipoSuceso") String tipoSuceso);

}
