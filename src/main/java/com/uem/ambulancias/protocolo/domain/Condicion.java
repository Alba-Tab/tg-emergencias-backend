package com.uem.ambulancias.protocolo.domain;

import com.uem.ambulancias.comun.error.CodigoError;
import com.uem.ambulancias.comun.error.ConflictoException;
import com.uem.ambulancias.flota.domain.TipoUnidad;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

/**
 * Algo que puede ser cierto o no en un suceso y que cambia lo que hay que mandar. Se escribe como una afirmación de
 * sí o no, se le puede preguntar al testigo, y lleva la respuesta que le corresponde cuando se cumple.
 *
 * <p>Los dos umbrales dicen cuánta certeza hace falta: desde {@code umbralActuar} se aplica la respuesta, desde
 * {@code umbralPreguntar} todavía no alcanza para actuar pero vale la pena preguntar, y por debajo no se hace nada.
 */
@Entity
@Table(name = "condicion", uniqueConstraints = @UniqueConstraint(name = "uk_condicion_codigo",
		columnNames = { "tipo_de_suceso_id", "codigo" }))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Condicion {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "tipo_de_suceso_id", nullable = false)
	private TipoDeSuceso tipoDeSuceso;

	@Column(nullable = false, length = 60)
	private String codigo;

	/** La afirmación que se evalúa, por sí o por no. */
	@Column(nullable = false, length = 500)
	private String enunciado;

	/** Cómo se le pregunta al testigo. Sin pregunta, la condición solo se deduce de la evidencia. */
	@Column(length = 500)
	private String pregunta;

	@Column(nullable = false)
	private double umbralActuar;

	@Column(nullable = false)
	private double umbralPreguntar;

	/** Cuántas unidades más hacen falta cuando la condición se cumple. */
	@Column(nullable = false)
	private int unidadesAdicionales;

	/** El tipo de unidad que como mínimo hace falta cuando se cumple, si la condición lo exige. */
	@Enumerated(EnumType.STRING)
	private TipoUnidad tipoMinimo;

	/** La prioridad que como mínimo toma el caso cuando la condición se cumple, si la condición fija una. */
	@Enumerated(EnumType.STRING)
	private Prioridad prioridad;

	/** Si cuando se cumple el caso tiene que verlo una persona, sin importar lo demás. */
	@Column(nullable = false)
	private boolean derivaAPersona;

	/**
	 * Para las condiciones que dependen de una cantidad: se cumple cuando los afectados declarados llegan a este
	 * número. Una condición así la resuelve una regla y nunca se le consulta al modelo, que no cuenta bien.
	 */
	private Integer afectadosDesde;

	/** De qué parte de la norma sale esta condición y su respuesta. */
	@Column(length = 1000)
	private String cita;

	@Column(nullable = false)
	private int orden;

	public static Condicion de(String codigo, String enunciado, String pregunta, double umbralActuar,
			double umbralPreguntar, int unidadesAdicionales, TipoUnidad tipoMinimo, boolean derivaAPersona,
			String cita) {
		return de(codigo, enunciado, pregunta, umbralActuar, umbralPreguntar, unidadesAdicionales, tipoMinimo,
				derivaAPersona, cita, null);
	}

	/** Lo mismo, para una condición que además se resuelve por la cantidad de afectados. */
	public static Condicion de(String codigo, String enunciado, String pregunta, double umbralActuar,
			double umbralPreguntar, int unidadesAdicionales, TipoUnidad tipoMinimo, boolean derivaAPersona,
			String cita, Integer afectadosDesde) {
		if (umbralPreguntar <= 0 || umbralActuar > 1 || umbralPreguntar > umbralActuar) {
			throw new ConflictoException(CodigoError.PROTOCOLO_INVALIDO, "Los umbrales de " + codigo
					+ " tienen que estar entre 0 y 1, y el de preguntar no puede pasar al de actuar.");
		}
		if (unidadesAdicionales < 0) {
			throw new ConflictoException(CodigoError.PROTOCOLO_INVALIDO,
					"Las unidades adicionales de " + codigo + " no pueden ser negativas.");
		}
		if (tipoMinimo != null && !TipoUnidad.ESCALERA.contains(tipoMinimo)) {
			// Una unidad de rescate no traslada: pedirla es pedir apoyo a otra institución, y eso lo decide una persona.
			throw new ConflictoException(CodigoError.PROTOCOLO_INVALIDO, "El tipo mínimo de " + codigo
					+ " tiene que ser una unidad que traslada: IA, II o III.");
		}
		if (afectadosDesde != null && afectadosDesde < 1) {
			throw new ConflictoException(CodigoError.PROTOCOLO_INVALIDO,
					"La cantidad de afectados de " + codigo + " tiene que ser al menos 1.");
		}
		Condicion condicion = new Condicion();
		condicion.codigo = codigo;
		condicion.enunciado = enunciado;
		condicion.pregunta = pregunta;
		condicion.umbralActuar = umbralActuar;
		condicion.umbralPreguntar = umbralPreguntar;
		condicion.unidadesAdicionales = unidadesAdicionales;
		condicion.tipoMinimo = tipoMinimo;
		condicion.derivaAPersona = derivaAPersona;
		condicion.cita = cita;
		condicion.afectadosDesde = afectadosDesde;
		return condicion;
	}

	public Condicion copiar() {
		return de(codigo, enunciado, pregunta, umbralActuar, umbralPreguntar, unidadesAdicionales, tipoMinimo,
				derivaAPersona, cita, afectadosDesde).conPrioridad(prioridad);
	}

	/** Fija la prioridad que la condición le da al caso cuando se cumple. Sin ella, no la cambia. */
	public Condicion conPrioridad(Prioridad prioridad) {
		this.prioridad = prioridad;
		return this;
	}

	void asignar(TipoDeSuceso tipoDeSuceso, int orden) {
		this.tipoDeSuceso = tipoDeSuceso;
		this.orden = orden;
	}

}
