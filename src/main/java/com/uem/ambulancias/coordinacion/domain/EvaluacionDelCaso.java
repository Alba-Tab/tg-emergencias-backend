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
 * Una evaluación de las condiciones del protocolo sobre un incidente. La tabla cumple dos papeles: es la cola (lo
 * pendiente sobrevive a un reinicio) y es el registro de qué se evaluó, con qué datos y qué salió.
 *
 * <p>Guarda el estado del caso tal como se armó en ese momento y hasta qué renglón de la bitácora se miró: con eso
 * se puede repetir la misma evaluación después, con otra versión del modelo o con otros umbrales, y comparar.
 */
@Entity
@Table(name = "evaluacion_del_caso", indexes = {
		@Index(name = "ix_evaluacion_del_caso_incidente", columnList = "incidente_id, id"),
		@Index(name = "ix_evaluacion_del_caso_cola", columnList = "estado, proximo_intento") })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EvaluacionDelCaso {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** Lo que viaja como {@code jobId} al servicio de IA y queda en los registros de los dos lados. */
	@Column(nullable = false, unique = true)
	private UUID referencia;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "incidente_id", nullable = false)
	private Incidente incidente;

	@Column(nullable = false, length = 20)
	private String estado;

	/** Qué renglón de la bitácora la pidió: el nombre del tipo de evento. */
	@Column(nullable = false, length = 40)
	private String motivo;

	/** Cuántas veces se tomó, contando la actual. */
	@Column(nullable = false)
	private int intentos;

	/** Desde cuándo se puede tomar. */
	@Column(nullable = false)
	private Instant proximoIntento;

	/** Mientras está EN_CURSO, hasta cuándo es del evaluador que la tomó. */
	private Instant bloqueadoHasta;

	/** El último error del servicio de IA, o el de este lado si ni siquiera respondió. */
	@Column(length = 80)
	private String ultimoError;

	@Column(nullable = false)
	private Instant creadaEn;

	private Instant terminadaEn;

	/** El último renglón de la bitácora que se tuvo en cuenta. */
	private Long hastaEvento;

	/** El estado del caso que se armó para evaluar: lo mismo que recibió el modelo. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	private String estadoDelCaso;

	/** El veredicto de cada condición, con su fuente, su probabilidad y los umbrales contra los que se comparó. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	private String veredictos;

	@Column(length = 20)
	private String metodo;

	private String proveedor;

	private String modelo;

	private Integer tokensDeEntrada;

	private Double costo;

	/** Cuánto tardó el modelo en responder, medido de este lado. */
	private Long milisegundosDelModelo;

	public static EvaluacionDelCaso pedir(Incidente incidente, String motivo, Instant ahora) {
		EvaluacionDelCaso evaluacion = new EvaluacionDelCaso();
		evaluacion.referencia = UUID.randomUUID();
		evaluacion.incidente = incidente;
		evaluacion.estado = EstadoEvaluacion.PENDIENTE.name();
		evaluacion.motivo = motivo;
		evaluacion.intentos = 0;
		evaluacion.proximoIntento = ahora;
		evaluacion.creadaEn = ahora;
		return evaluacion;
	}

	public EstadoEvaluacion getEstado() {
		return EstadoEvaluacion.valueOf(estado);
	}

	public MetodoDeEvaluacion getMetodo() {
		return metodo == null ? null : MetodoDeEvaluacion.valueOf(metodo);
	}

	public boolean isEnCurso() {
		return getEstado() == EstadoEvaluacion.EN_CURSO;
	}

	/** Un evaluador se la lleva. Vale también para una EN_CURSO cuyo bloqueo venció: el anterior se cayó. */
	public void tomar(Instant ahora, Duration bloqueo) {
		estado = EstadoEvaluacion.EN_CURSO.name();
		intentos++;
		bloqueadoHasta = ahora.plus(bloqueo);
	}

	public boolean agotoIntentos(int maximo) {
		return intentos >= maximo;
	}

	/** Deja anotado qué se miró: el estado del caso y hasta qué renglón de la bitácora. */
	public void registrarEstado(String estadoDelCaso, Long hastaEvento) {
		this.estadoDelCaso = estadoDelCaso;
		this.hastaEvento = hastaEvento;
	}

	/** Terminó: quedan los veredictos y con qué se resolvieron. */
	public void concluir(String veredictos, MetodoDeEvaluacion metodo, Instant ahora) {
		this.veredictos = veredictos;
		this.metodo = metodo.name();
		terminar(EstadoEvaluacion.HECHA, ahora);
	}

	/** Lo que el modelo informó de su propia respuesta. */
	public void registrarModelo(String proveedor, String modelo, Integer tokensDeEntrada, Double costo,
			Long milisegundos) {
		this.proveedor = proveedor;
		this.modelo = modelo;
		this.tokensDeEntrada = tokensDeEntrada;
		this.costo = costo;
		this.milisegundosDelModelo = milisegundos;
	}

	/** Vuelve a la cola, para dentro de {@code espera}. */
	public void reintentar(String error, Instant ahora, Duration espera) {
		estado = EstadoEvaluacion.PENDIENTE.name();
		ultimoError = error;
		proximoIntento = ahora.plus(espera);
		bloqueadoHasta = null;
	}

	public void anotarError(String error) {
		ultimoError = error;
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
		terminadaEn = ahora;
	}

}
