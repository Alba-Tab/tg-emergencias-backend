package com.uem.ambulancias.emergencias.domain;

import java.time.Instant;

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
 * Un renglón de la bitácora del caso: algo que llegó o que se decidió en un incidente, con quién lo aportó y cuándo.
 * Solo se agrega, nunca se cambia ni se borra: con la bitácora se puede saber qué se sabía del caso en cada momento
 * y volver a recorrerlo tal como ocurrió.
 *
 * <p>No reemplaza a la bitácora del centro de control, que se arma con las horas de cada atención. Esta guarda la
 * evidencia y las decisiones, que no tienen otra tabla donde quedar.
 */
@Entity
@Table(name = "evento_del_caso", indexes = @Index(name = "ix_evento_del_caso_incidente",
		columnList = "incidente_id, id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventoDelCaso {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "incidente_id", nullable = false)
	private Incidente incidente;

	/** La alerta de la que salió, cuando lo aportó quien alertó o una evidencia suya. */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "alerta_id")
	private Alerta alerta;

	@Column(nullable = false, length = 40)
	private String tipo;

	@Column(nullable = false, length = 20)
	private String origen;

	/** El contenido del evento. Cada tipo tiene sus campos; un dato que no se conoce no se incluye. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private String datos;

	/** Cuándo pasó, según quien lo aportó. */
	@Column(nullable = false)
	private Instant ocurridoEn;

	/** Cuándo quedó anotado. */
	@Column(nullable = false)
	private Instant registradoEn;

	public static EventoDelCaso anotar(Incidente incidente, Alerta alerta, TipoEventoDelCaso tipo,
			OrigenDelEvento origen, String datos, Instant ocurridoEn, Instant registradoEn) {
		EventoDelCaso evento = new EventoDelCaso();
		evento.incidente = incidente;
		evento.alerta = alerta;
		evento.tipo = tipo.name();
		evento.origen = origen.name();
		evento.datos = datos;
		evento.ocurridoEn = ocurridoEn;
		evento.registradoEn = registradoEn;
		return evento;
	}

	public TipoEventoDelCaso getTipo() {
		return TipoEventoDelCaso.valueOf(tipo);
	}

	public OrigenDelEvento getOrigen() {
		return OrigenDelEvento.valueOf(origen);
	}

}
