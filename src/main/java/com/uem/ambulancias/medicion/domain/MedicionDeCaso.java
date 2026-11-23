package com.uem.ambulancias.medicion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Lo que el modelo respondió sobre un caso en una medición. Se guarda la respuesta cruda: con ella se pueden volver a
 * hacer las cuentas con otros objetivos sin consultar otra vez al modelo.
 */
@Entity
@Table(name = "medicion_de_caso", uniqueConstraints = @UniqueConstraint(name = "uk_medicion_de_caso",
		columnNames = { "medicion_id", "caso_id" }))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MedicionDeCaso {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "medicion_id", nullable = false)
	private Medicion medicion;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "caso_id", nullable = false)
	private CasoDePrueba caso;

	/** Por código de condición, la probabilidad que dio el modelo. Vacío si el caso terminó con error. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	private String probabilidades;

	@Column(length = 80)
	private String error;

	private Long milisegundos;

	public static MedicionDeCaso medido(Medicion medicion, CasoDePrueba caso, String probabilidades,
			long milisegundos) {
		MedicionDeCaso medido = new MedicionDeCaso();
		medido.medicion = medicion;
		medido.caso = caso;
		medido.probabilidades = probabilidades;
		medido.milisegundos = milisegundos;
		return medido;
	}

	public static MedicionDeCaso conError(Medicion medicion, CasoDePrueba caso, String error) {
		MedicionDeCaso medido = new MedicionDeCaso();
		medido.medicion = medicion;
		medido.caso = caso;
		medido.error = error;
		return medido;
	}

}
