package com.uem.ambulancias.medicion.domain;

import java.time.Instant;

import com.uem.ambulancias.usuarios.domain.Usuario;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Una corrida de medición: se le pasan al modelo de decisión los casos del banco y se compara lo que respondió con
 * lo que era cierto. Queda qué modelo se midió, con cuántos casos y qué umbrales convendría usar en cada condición.
 */
@Entity
@Table(name = "medicion")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Medicion {

	public static final String PENDIENTE = "PENDIENTE";
	public static final String EN_CURSO = "EN_CURSO";
	public static final String HECHA = "HECHA";
	public static final String FALLIDA = "FALLIDA";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 20)
	private String estado;

	/** Qué casos del banco se miden: GENERADO, REAL o todos si no se indica. */
	@Column(length = 20)
	private String origenDeLosCasos;

	/** De las veces que se actúa, la proporción mínima en que la condición tiene que ser cierta. */
	@Column(nullable = false)
	private double objetivoDePrecision;

	/** De los casos ciertos, la proporción mínima que no se puede descartar. */
	@Column(nullable = false)
	private double objetivoDeCobertura;

	@Column(nullable = false)
	private int casos;

	@Column(nullable = false)
	private int casosMedidos;

	@Column(nullable = false)
	private int casosConError;

	private String proveedor;

	private String modelo;

	private Long tokensDeEntrada;

	private Double costo;

	/** Por condición: aciertos, calibración y umbrales sugeridos. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	private String resultados;

	@Column(length = 200)
	private String error;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "creada_por_id")
	private Usuario creadaPor;

	@Column(nullable = false)
	private Instant creadaEn;

	private Instant terminadaEn;

	public static Medicion pedir(String origenDeLosCasos, double objetivoDePrecision, double objetivoDeCobertura,
			int casos, Usuario creadaPor, Instant ahora) {
		Medicion medicion = new Medicion();
		medicion.estado = PENDIENTE;
		medicion.origenDeLosCasos = origenDeLosCasos;
		medicion.objetivoDePrecision = objetivoDePrecision;
		medicion.objetivoDeCobertura = objetivoDeCobertura;
		medicion.casos = casos;
		medicion.creadaPor = creadaPor;
		medicion.creadaEn = ahora;
		return medicion;
	}

	public boolean isTerminada() {
		return HECHA.equals(estado) || FALLIDA.equals(estado);
	}

	public void empezar() {
		estado = EN_CURSO;
	}

	/** Un caso más pasó por el modelo y respondió. */
	public void sumarMedido(String proveedor, String modelo, Integer tokens, Double costoDelCaso) {
		casosMedidos++;
		this.proveedor = proveedor;
		this.modelo = modelo;
		if (tokens != null) {
			tokensDeEntrada = (tokensDeEntrada == null ? 0 : tokensDeEntrada) + tokens;
		}
		if (costoDelCaso != null) {
			costo = (costo == null ? 0 : costo) + costoDelCaso;
		}
	}

	/** Un caso más pasó por el modelo y no hubo respuesta. */
	public void sumarError() {
		casosConError++;
	}

	public void terminar(String resultados, Instant ahora) {
		this.resultados = resultados;
		estado = HECHA;
		terminadaEn = ahora;
	}

	public void fallar(String error, Instant ahora) {
		this.error = error;
		estado = FALLIDA;
		terminadaEn = ahora;
	}

}
