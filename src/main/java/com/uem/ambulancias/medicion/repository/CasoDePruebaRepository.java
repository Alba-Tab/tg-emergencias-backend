package com.uem.ambulancias.medicion.repository;

import java.util.List;

import com.uem.ambulancias.medicion.domain.CasoDePrueba;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CasoDePruebaRepository extends JpaRepository<CasoDePrueba, Long> {

	/** Los más nuevos primero. */
	@Query("select c from CasoDePrueba c order by c.id desc")
	List<CasoDePrueba> buscar(Pageable limite);

	@Query("select c from CasoDePrueba c where c.origen = :origen order by c.id desc")
	List<CasoDePrueba> buscarDeOrigen(@Param("origen") String origen, Pageable limite);

	int countByOrigen(String origen);

	/** Los casos que una medición todavía no pasó por el modelo. Solo los ids. */
	@Query("""
			select c.id from CasoDePrueba c
			where not exists (select m from MedicionDeCaso m where m.medicion.id = :medicionId and m.caso = c)
			order by c.id
			""")
	List<Long> buscarIdsSinMedir(@Param("medicionId") Long medicionId, Pageable limite);

	/** Lo mismo, solo entre los de ese origen. */
	@Query("""
			select c.id from CasoDePrueba c
			where c.origen = :origen
			  and not exists (select m from MedicionDeCaso m where m.medicion.id = :medicionId and m.caso = c)
			order by c.id
			""")
	List<Long> buscarIdsSinMedirDeOrigen(@Param("medicionId") Long medicionId, @Param("origen") String origen,
			Pageable limite);

	/** Borra los casos generados que ninguna medición usó. Devuelve cuántos borró. */
	@Modifying
	@Query("""
			delete from CasoDePrueba c
			where c.origen = 'GENERADO' and not exists (select m from MedicionDeCaso m where m.caso = c)
			""")
	int borrarGeneradosSinMedir();

}
