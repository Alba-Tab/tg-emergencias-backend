package com.uem.ambulancias.coordinacion.domain;

import java.time.Duration;
import java.time.Instant;

import com.uem.ambulancias.comun.error.CodigoError;
import com.uem.ambulancias.comun.error.ConflictoException;
import com.uem.ambulancias.emergencias.domain.Incidente;
import com.uem.ambulancias.usuarios.domain.Usuario;

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
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Un caso que el sistema le pasó a una persona de la central porque no puede o no debe decidirlo solo. Lleva el
 * paquete con lo que la persona necesita para decidir, las opciones del motivo y un tiempo límite: si nadie contesta,
 * se aplica la opción segura y queda a la vista hasta que alguien la marque como vista.
 *
 * <p>La clave identifica el asunto dentro del incidente: el mismo asunto no se deriva dos veces.
 */
@Entity
@Table(name = "derivacion", uniqueConstraints = @UniqueConstraint(name = "uk_derivacion_clave",
		columnNames = { "incidente_id", "clave" }), indexes = {
				@Index(name = "ix_derivacion_incidente", columnList = "incidente_id, id"),
				@Index(name = "ix_derivacion_estado", columnList = "estado, vence_en") })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Derivacion {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "incidente_id", nullable = false)
	private Incidente incidente;

	@Column(nullable = false, length = 20)
	private String estado;

	@Column(nullable = false, length = 30)
	private String motivo;

	/** El asunto dentro del incidente: el motivo y, si es por una condición, su código. */
	@Column(nullable = false, length = 120)
	private String clave;

	/** El código de la condición, cuando la derivación es por una. */
	@Column(length = 60)
	private String condicion;

	/** El paquete: lo que la persona necesita ver para decidir. Sus campos dependen del motivo. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private String detalle;

	/** La evaluación que la originó, si salió de una. */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "evaluacion_id")
	private EvaluacionDelCaso evaluacion;

	@Column(nullable = false)
	private Instant creadaEn;

	@Column(nullable = false)
	private Instant venceEn;

	private Instant resueltaEn;

	/** Lo que contestó la persona, o la opción segura si se aplicó sola. */
	@Column(length = 30)
	private String opcionElegida;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "resuelta_por_id")
	private Usuario resueltaPor;

	@Column(length = 1000)
	private String nota;

	/** Cuándo alguien se dio por enterado de una derivación que venció sin respuesta. */
	private Instant vistaEn;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "vista_por_id")
	private Usuario vistaPor;

	public static Derivacion abrir(Incidente incidente, MotivoDeDerivacion motivo, String clave, String condicion,
			String detalle, EvaluacionDelCaso evaluacion, Instant ahora, Duration espera) {
		Derivacion derivacion = new Derivacion();
		derivacion.incidente = incidente;
		derivacion.estado = EstadoDerivacion.ABIERTA.name();
		derivacion.motivo = motivo.name();
		derivacion.clave = clave;
		derivacion.condicion = condicion;
		derivacion.detalle = detalle;
		derivacion.evaluacion = evaluacion;
		derivacion.creadaEn = ahora;
		derivacion.venceEn = ahora.plus(espera);
		return derivacion;
	}

	public EstadoDerivacion getEstado() {
		return EstadoDerivacion.valueOf(estado);
	}

	public MotivoDeDerivacion getMotivo() {
		return MotivoDeDerivacion.valueOf(motivo);
	}

	public OpcionDeDerivacion getOpcionElegida() {
		return opcionElegida == null ? null : OpcionDeDerivacion.valueOf(opcionElegida);
	}

	public boolean isAbierta() {
		return getEstado() == EstadoDerivacion.ABIERTA;
	}

	/** Si ya pasó el tiempo límite sin respuesta. */
	public boolean vencio(Instant ahora) {
		return isAbierta() && !ahora.isBefore(venceEn);
	}

	/** Si todavía le hace falta la atención de una persona: espera respuesta, o venció y nadie la vio. */
	public boolean necesitaAtencion() {
		return isAbierta() || (getEstado() == EstadoDerivacion.VENCIDA && vistaEn == null);
	}

	/**
	 * La persona contesta. Vale aunque ya haya pasado el tiempo, mientras nadie la haya dado por vencida: una
	 * respuesta un segundo tarde sigue siendo la de una persona.
	 */
	public void resolver(OpcionDeDerivacion opcion, Usuario persona, String nota, Instant ahora) {
		exigirAbierta();
		if (!getMotivo().admite(opcion)) {
			throw new ConflictoException(CodigoError.OPCION_NO_PERMITIDA,
					"La derivación " + id + " no admite la opción " + opcion + ".");
		}
		estado = EstadoDerivacion.RESUELTA.name();
		opcionElegida = opcion.name();
		resueltaPor = persona;
		this.nota = nota;
		resueltaEn = ahora;
	}

	/** Nadie contestó a tiempo: queda aplicada la opción segura del motivo. Devuelve cuál. */
	public OpcionDeDerivacion vencer(Instant ahora) {
		exigirAbierta();
		OpcionDeDerivacion segura = getMotivo().getOpcionSegura();
		estado = EstadoDerivacion.VENCIDA.name();
		opcionElegida = segura.name();
		resueltaEn = ahora;
		return segura;
	}

	/** Dejó de hacer falta. */
	public void retirar(String razon, Instant ahora) {
		exigirAbierta();
		estado = EstadoDerivacion.RETIRADA.name();
		nota = razon;
		resueltaEn = ahora;
	}

	/** Alguien se da por enterado de una que venció sin respuesta. Lo ya aplicado no cambia. */
	public void marcarVista(Usuario persona, Instant ahora) {
		if (getEstado() != EstadoDerivacion.VENCIDA) {
			throw new ConflictoException(CodigoError.DERIVACION_CERRADA,
					"La derivación " + id + " no está vencida: quedó " + estado + ".");
		}
		if (vistaEn == null) {
			vistaPor = persona;
			vistaEn = ahora;
		}
	}

	private void exigirAbierta() {
		if (!isAbierta()) {
			throw new ConflictoException(CodigoError.DERIVACION_CERRADA,
					"La derivación " + id + " ya no espera respuesta: quedó " + estado + ".");
		}
	}

}
