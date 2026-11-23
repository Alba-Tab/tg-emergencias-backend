package com.uem.ambulancias.emergencias.domain;

import java.time.Duration;
import java.time.Instant;

import com.uem.ambulancias.comun.error.CodigoError;
import com.uem.ambulancias.comun.error.ConflictoException;
import com.uem.ambulancias.flota.domain.Ambulancia;
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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * El sistema le asignó un incidente a una unidad y espera que su tripulación confirme. Mientras está pendiente, a esa
 * unidad no se le ofrece nada más. Si la tripulación no contesta a tiempo o dice que no puede, el incidente pasa a
 * la unidad siguiente.
 *
 * <p>Una unidad puede seguir tomando un incidente por su cuenta: la asignación suma un camino, no cierra el otro.
 */
@Entity
@Table(name = "oferta_de_incidente", indexes = {
		@Index(name = "ix_oferta_de_incidente_incidente", columnList = "incidente_id"),
		@Index(name = "ix_oferta_de_incidente_ambulancia", columnList = "ambulancia_id, estado") })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OfertaDeIncidente {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "incidente_id", nullable = false)
	private Incidente incidente;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "ambulancia_id", nullable = false)
	private Ambulancia ambulancia;

	/** Guardado como texto, sin restricción en la base. */
	@Column(nullable = false, length = 20)
	private String estado;

	/**
	 * Cada vez que el incidente vuelve a necesitar una unidad empieza una ronda nueva. Dentro de una ronda no se le
	 * ofrece dos veces a la misma unidad.
	 */
	@Column(nullable = false)
	private int ronda;

	/** Qué número de unidad es dentro de la ronda: la primera es la que llegaba antes. */
	@Column(nullable = false)
	private int orden;

	/** Cuánto se estimó que tardaba en llegar al ofrecérselo. */
	private Integer minutosDeLlegada;

	@Column(nullable = false)
	private Instant ofrecidaEn;

	@Column(nullable = false)
	private Instant venceEn;

	private Instant resueltaEn;

	/** Lo que escribió la tripulación al decir que no puede ir. */
	@Column(length = 300)
	private String motivoRechazo;

	/** Quién confirmó o rechazó. */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "respondida_por_id")
	private Usuario respondidaPor;

	/** La atención que nació al confirmar. */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "atencion_id")
	private Atencion atencion;

	public static OfertaDeIncidente ofrecer(Incidente incidente, Ambulancia ambulancia, int ronda, int orden,
			Integer minutosDeLlegada, Instant ahora, Duration espera) {
		OfertaDeIncidente oferta = new OfertaDeIncidente();
		oferta.incidente = incidente;
		oferta.ambulancia = ambulancia;
		oferta.estado = EstadoOferta.PENDIENTE.name();
		oferta.ronda = ronda;
		oferta.orden = orden;
		oferta.minutosDeLlegada = minutosDeLlegada;
		oferta.ofrecidaEn = ahora;
		oferta.venceEn = ahora.plus(espera);
		return oferta;
	}

	public EstadoOferta getEstado() {
		return EstadoOferta.valueOf(estado);
	}

	public boolean isPendiente() {
		return getEstado() == EstadoOferta.PENDIENTE;
	}

	/** Si ya pasó el tiempo de espera sin respuesta. */
	public boolean vencio(Instant ahora) {
		return isPendiente() && !ahora.isBefore(venceEn);
	}

	/**
	 * La tripulación confirma. Vale aunque ya haya pasado el tiempo, mientras nadie la haya dado por vencida: si la
	 * unidad contesta un segundo tarde y sigue siendo la suya, que vaya.
	 */
	public void confirmar(Usuario paramedico, Atencion atencion, Instant ahora) {
		resolver(EstadoOferta.CONFIRMADA, ahora);
		this.respondidaPor = paramedico;
		this.atencion = atencion;
	}

	public void rechazar(Usuario paramedico, String motivo, Instant ahora) {
		resolver(EstadoOferta.RECHAZADA, ahora);
		this.respondidaPor = paramedico;
		this.motivoRechazo = motivo;
	}

	public void vencer(Instant ahora) {
		resolver(EstadoOferta.VENCIDA, ahora);
	}

	public void retirar(Instant ahora) {
		resolver(EstadoOferta.RETIRADA, ahora);
	}

	private void resolver(EstadoOferta nuevo, Instant ahora) {
		if (!isPendiente()) {
			throw new ConflictoException(CodigoError.SIN_OFERTA,
					"La asignación " + id + " ya no espera respuesta: quedó " + estado + ".");
		}
		estado = nuevo.name();
		resueltaEn = ahora;
	}

}
