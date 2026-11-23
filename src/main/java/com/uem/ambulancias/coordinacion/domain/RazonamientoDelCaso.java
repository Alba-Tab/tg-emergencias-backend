package com.uem.ambulancias.coordinacion.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.uem.ambulancias.emergencias.domain.Incidente;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Un razonamiento del agente sobre las condiciones de un incidente que quedaron en duda. Como la evaluación, la tabla
 * es a la vez la cola y el registro: qué se le pidió, con qué estado del caso, a qué llegó, qué herramientas usó y
 * cuánto costó en pedidos al modelo.
 */
@Entity
@Table(name = "razonamiento_del_caso", indexes = {
		@Index(name = "ix_razonamiento_del_caso_incidente", columnList = "incidente_id, id"),
		@Index(name = "ix_razonamiento_del_caso_cola", columnList = "estado, proximo_intento") })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RazonamientoDelCaso {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** Lo que viaja como {@code jobId} al servicio de IA. */
	@Column(nullable = false, unique = true)
	private UUID referencia;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "incidente_id", nullable = false)
	private Incidente incidente;

	/** La evaluación que dejó las condiciones en duda. */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "evaluacion_id")
	private EvaluacionDelCaso evaluacion;

	/** Los estados son los mismos de una evaluación. Se guarda como texto. */
	@Column(nullable = false, length = 20)
	private String estado;

	/**
	 * Las condiciones a razonar, cada una con el paquete que se le pasaría a una persona si el agente no la resuelve.
	 */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private String condiciones;

	@Column(nullable = false)
	private int intentos;

	@Column(nullable = false)
	private Instant proximoIntento;

	private Instant bloqueadoHasta;

	@Column(length = 80)
	private String ultimoError;

	@Column(nullable = false)
	private Instant creadoEn;

	private Instant terminadoEn;

	/** El estado del caso que recibió el agente. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	private String estadoDelCaso;

	/** El último renglón de la bitácora que se tuvo en cuenta al armar ese estado. */
	private Long hastaEvento;

	/** A qué llegó sobre cada condición, con su porqué y sus fuentes. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	private String conclusiones;

	/** Las herramientas que usó, en orden. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	private String herramientas;

	private String proveedor;

	private String modelo;

	private String versionDelPrompt;

	private Integer tokensDeEntrada;

	private Integer tokensDeSalida;

	private Integer pedidosAlModelo;

	/** Cuánto tardó el agente en responder, medido de este lado. */
	private Long milisegundos;

	public static RazonamientoDelCaso pedir(Incidente incidente, EvaluacionDelCaso evaluacion, String condiciones,
			Instant ahora) {
		RazonamientoDelCaso razonamiento = new RazonamientoDelCaso();
		razonamiento.referencia = UUID.randomUUID();
		razonamiento.incidente = incidente;
		razonamiento.evaluacion = evaluacion;
		razonamiento.estado = EstadoEvaluacion.PENDIENTE.name();
		razonamiento.condiciones = condiciones;
		razonamiento.intentos = 0;
		razonamiento.proximoIntento = ahora;
		razonamiento.creadoEn = ahora;
		return razonamiento;
	}

	public EstadoEvaluacion getEstado() {
		return EstadoEvaluacion.valueOf(estado);
	}

	public boolean isEnCurso() {
		return getEstado() == EstadoEvaluacion.EN_CURSO;
	}

	public void tomar(Instant ahora, Duration bloqueo) {
		estado = EstadoEvaluacion.EN_CURSO.name();
		intentos++;
		bloqueadoHasta = ahora.plus(bloqueo);
	}

	public boolean agotoIntentos(int maximo) {
		return intentos >= maximo;
	}

	public void registrarEstado(String estadoDelCaso, Long hastaEvento) {
		this.estadoDelCaso = estadoDelCaso;
		this.hastaEvento = hastaEvento;
	}

	/** Terminó: quedan sus conclusiones y con qué se llegó a ellas. */
	public void concluir(String conclusiones, String herramientas, String proveedor, String modelo,
			String versionDelPrompt, Integer tokensDeEntrada, Integer tokensDeSalida, Integer pedidosAlModelo,
			Long milisegundos, Instant ahora) {
		this.conclusiones = conclusiones;
		this.herramientas = herramientas;
		this.proveedor = proveedor;
		this.modelo = modelo;
		this.versionDelPrompt = versionDelPrompt;
		this.tokensDeEntrada = tokensDeEntrada;
		this.tokensDeSalida = tokensDeSalida;
		this.pedidosAlModelo = pedidosAlModelo;
		this.milisegundos = milisegundos;
		terminar(EstadoEvaluacion.HECHA, ahora);
	}

	public void reintentar(String error, Instant ahora, Duration espera) {
		estado = EstadoEvaluacion.PENDIENTE.name();
		ultimoError = error;
		proximoIntento = ahora.plus(espera);
		bloqueadoHasta = null;
	}

	public void descartar(String motivo, Instant ahora) {
		ultimoError = motivo;
		terminar(EstadoEvaluacion.DESCARTADA, ahora);
	}

	public void fallar(String error, Instant ahora) {
		ultimoError = error;
		terminar(EstadoEvaluacion.FALLIDA, ahora);
	}

	private void terminar(EstadoEvaluacion fin, Instant ahora) {
		estado = fin.name();
		bloqueadoHasta = null;
		terminadoEn = ahora;
	}

}
