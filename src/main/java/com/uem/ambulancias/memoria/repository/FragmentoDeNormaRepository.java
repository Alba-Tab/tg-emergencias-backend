package com.uem.ambulancias.memoria.repository;

import java.util.List;

import com.uem.ambulancias.memoria.domain.FragmentoDeNorma;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FragmentoDeNormaRepository extends JpaRepository<FragmentoDeNorma, Long> {

	List<FragmentoDeNorma> findAllByOrderByIdAsc();

	long countByVectorIsNotNull();

}
