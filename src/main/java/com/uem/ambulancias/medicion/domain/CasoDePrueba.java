package com.uem.ambulancias.medicion.domain;

import java.time.Instant;

import com.uem.ambulancias.emergencias.domain.Incidente;
import com.uem.ambulancias.protocolo.domain.Protocolo;

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
 * Un caso del que se sabe la verdad: el estado de un suceso, con la misma forma que el estado que se arma al evaluar
 * un incidente, y si cada condición era cierta o no. Con estos casos se mide al modelo de decisión.
 *
 * <p>Un caso GENERADO es inventado por el generador y está marcado como tal: sirve para medir, no cuenta cómo operó la
 * central. Un caso REAL sale de un incidente cuya verdad confirmó una persona.
 */
@Entity
@Table(name = "caso_de_prueba", indexes = @Index(name = "ix_caso_de_prueba_origen", columnList = "origen, id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CasoDePrueba {

	public static final String GENERADO = "GENERADO";
	public static final String REAL = "REAL";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** GENERADO o REAL. Se guarda como texto. */
	@Column(nullable = false, length = 20)
	private String origen;

	@Column(nullable = false, length = 60)
	private String tipoSuceso;

	/** La versión del protocolo de la que salen el tipo de suceso y sus condiciones. */
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "protocolo_id", nullable = false)
	private Protocolo protocolo;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private String estado;

	/** Por código de condición, si era cierta. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private String verdades;

	/** El incidente del que salió, si es un caso real. */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "incidente_id")
	private Incidente incidente;

	/** La semilla con la que se generó, si es generado: con la misma semilla sale el mismo caso. */
	private Long semilla;

	@Column(length = 500)
	private String nota;

	@Column(nullable = false)
	private Instant creadoEn;

	public static CasoDePrueba generado(Protocolo protocolo, String tipoSuceso, String estado, String verdades,
			long semilla, Instant ahora) {
		CasoDePrueba caso = nuevo(GENERADO, protocolo, tipoSuceso, estado, verdades, ahora);
		caso.semilla = semilla;
		return caso;
	}

	public static CasoDePrueba real(Incidente incidente, String estado, String verdades, String nota, Instant ahora) {
		CasoDePrueba caso = nuevo(REAL, incidente.getProtocolo(), incidente.getTipoSuceso(), estado, verdades, ahora);
		caso.incidente = incidente;
		caso.nota = nota;
		return caso;
	}

	private static CasoDePrueba nuevo(String origen, Protocolo protocolo, String tipoSuceso, String estado,
			String verdades, Instant ahora) {
		CasoDePrueba caso = new CasoDePrueba();
		caso.origen = origen;
		caso.protocolo = protocolo;
		caso.tipoSuceso = tipoSuceso;
		caso.estado = estado;
		caso.verdades = verdades;
		caso.creadoEn = ahora;
		return caso;
	}

}
