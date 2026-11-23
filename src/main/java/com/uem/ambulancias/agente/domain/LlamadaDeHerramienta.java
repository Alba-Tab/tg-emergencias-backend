package com.uem.ambulancias.agente.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Una herramienta que el agente le pidió a la central: cuál, con qué argumentos, cómo salió y cuánto tardó. Solo se
 * agrega. Sirve para saber qué miró el agente antes de concluir algo.
 */
@Entity
@Table(name = "llamada_de_herramienta", indexes = @Index(name = "ix_llamada_de_herramienta_incidente",
		columnList = "incidente_id, id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LlamadaDeHerramienta {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 60)
	private String herramienta;

	/** El incidente sobre el que se pidió, cuando los argumentos lo traen. Sin clave foránea: es un registro. */
	@Column(name = "incidente_id")
	private Long incidenteId;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private String argumentos;

	@Column(nullable = false)
	private boolean exito;

	/** Lo que se le respondió al agente cuando no salió bien. */
	@Column(length = 500)
	private String error;

	@Column(nullable = false)
	private long milisegundos;

	@Column(nullable = false)
	private Instant llamadaEn;

	public static LlamadaDeHerramienta registrar(String herramienta, Long incidenteId, String argumentos,
			boolean exito, String error, long milisegundos, Instant llamadaEn) {
		LlamadaDeHerramienta llamada = new LlamadaDeHerramienta();
		llamada.herramienta = herramienta;
		llamada.incidenteId = incidenteId;
		llamada.argumentos = argumentos;
		llamada.exito = exito;
		llamada.error = error == null || error.length() <= 500 ? error : error.substring(0, 500);
		llamada.milisegundos = milisegundos;
		llamada.llamadaEn = llamadaEn;
		return llamada;
	}

}
