package com.uem.ambulancias.protocolo.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.uem.ambulancias.comun.error.CodigoError;
import com.uem.ambulancias.comun.error.ConflictoException;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Lo que el ciudadano marca con un toque al alertar: qué pasó. Cada tipo trae las condiciones que importan para
 * decidir qué mandar.
 */
@Entity
@Table(name = "tipo_de_suceso", uniqueConstraints = @UniqueConstraint(name = "uk_tipo_de_suceso_codigo",
		columnNames = { "protocolo_id", "codigo" }))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TipoDeSuceso {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "protocolo_id", nullable = false)
	private Protocolo protocolo;

	/** Con lo que se lo nombra fuera de la base: lo guarda la alerta y lo manda la app. */
	@Column(nullable = false, length = 60)
	private String codigo;

	/** Como lo lee el ciudadano. */
	@Column(nullable = false, length = 120)
	private String nombre;

	@Column(nullable = false)
	private int orden;

	@OneToMany(mappedBy = "tipoDeSuceso", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("orden asc")
	private List<Condicion> condiciones = new ArrayList<>();

	/** Dos condiciones del mismo tipo no pueden compartir el código: es con lo que se anotan las respuestas. */
	public static TipoDeSuceso de(String codigo, String nombre, List<Condicion> condiciones) {
		Set<String> codigos = new HashSet<>();
		for (Condicion condicion : condiciones) {
			if (!codigos.add(condicion.getCodigo())) {
				throw new ConflictoException(CodigoError.PROTOCOLO_INVALIDO,
						"La condición " + condicion.getCodigo() + " está repetida en " + codigo + ".");
			}
		}
		TipoDeSuceso tipo = new TipoDeSuceso();
		tipo.codigo = codigo;
		tipo.nombre = nombre;
		for (Condicion condicion : condiciones) {
			condicion.asignar(tipo, tipo.condiciones.size());
			tipo.condiciones.add(condicion);
		}
		return tipo;
	}

	/** El mismo tipo con sus condiciones, sin atar a ningún protocolo: de ahí arranca el borrador siguiente. */
	public TipoDeSuceso copiar() {
		return de(codigo, nombre, condiciones.stream().map(Condicion::copiar).toList());
	}

	public Optional<Condicion> buscarCondicion(String codigo) {
		return condiciones.stream().filter(condicion -> condicion.getCodigo().equals(codigo)).findFirst();
	}

	/** Las que se le preguntan al testigo. Una condición sin pregunta solo se deduce de la evidencia. */
	public List<Condicion> conPregunta() {
		return condiciones.stream().filter(condicion -> condicion.getPregunta() != null).toList();
	}

	void asignar(Protocolo protocolo, int orden) {
		this.protocolo = protocolo;
		this.orden = orden;
	}

}
