package com.uem.ambulancias.protocolo.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.uem.ambulancias.comun.error.CodigoError;
import com.uem.ambulancias.comun.error.ConflictoException;
import com.uem.ambulancias.usuarios.domain.Usuario;

import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Una versión del protocolo de despacho: qué tipos de suceso se pueden declarar y, para cada uno, qué condiciones
 * importan, cómo se preguntan y qué se manda cuando se cumplen. Es un dato que carga la central, no código: los
 * criterios salen del reglamento que rige a las ambulancias y de lo que decida la empresa.
 *
 * <p>Un incidente recuerda con qué versión se lo atendió, así que una versión publicada no se vuelve a tocar: para
 * cambiar algo se abre un borrador nuevo.
 */
@Entity
@Table(name = "protocolo")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Protocolo {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true)
	private int version;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private EstadoProtocolo estado;

	/** De dónde salen los criterios de esta versión: la norma y lo que haya definido la empresa. */
	@Column(length = 1000)
	private String nota;

	@Column(nullable = false)
	private Instant creadoEn;

	private Instant publicadoEn;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "creado_por_id", nullable = false)
	private Usuario creadoPor;

	@OneToMany(mappedBy = "protocolo", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("orden asc")
	private List<TipoDeSuceso> tipos = new ArrayList<>();

	/** Un borrador vacío, listo para cargarle el contenido. */
	public static Protocolo borrador(int version, Usuario creadoPor, Instant ahora) {
		Protocolo protocolo = new Protocolo();
		protocolo.version = version;
		protocolo.estado = EstadoProtocolo.BORRADOR;
		protocolo.creadoPor = creadoPor;
		protocolo.creadoEn = ahora;
		return protocolo;
	}

	public boolean isBorrador() {
		return estado == EstadoProtocolo.BORRADOR;
	}

	/** Quita todo el contenido del borrador, para cargarle uno nuevo. */
	public void vaciar() {
		exigirBorrador();
		tipos.clear();
	}

	/**
	 * Carga el contenido del borrador en el orden en que llega. Dos tipos de suceso no pueden compartir el código:
	 * es con lo que la app y los incidentes se refieren a ellos.
	 */
	public void cargar(String nota, List<TipoDeSuceso> nuevos) {
		exigirBorrador();
		Set<String> codigos = new HashSet<>();
		for (TipoDeSuceso tipo : nuevos) {
			if (!codigos.add(tipo.getCodigo())) {
				throw new ConflictoException(CodigoError.PROTOCOLO_INVALIDO,
						"El tipo de suceso " + tipo.getCodigo() + " está repetido.");
			}
		}
		this.nota = nota;
		for (TipoDeSuceso tipo : nuevos) {
			tipo.asignar(this, tipos.size());
			tipos.add(tipo);
		}
	}

	/** El borrador pasa a ser el vigente. Sin ningún tipo de suceso no hay nada que ofrecerle al ciudadano. */
	public void publicar(Instant ahora) {
		exigirBorrador();
		if (tipos.isEmpty()) {
			throw new ConflictoException(CodigoError.PROTOCOLO_INVALIDO,
					"El protocolo no tiene ningún tipo de suceso.");
		}
		estado = EstadoProtocolo.VIGENTE;
		publicadoEn = ahora;
	}

	/** Lo reemplazó una versión más nueva. Queda guardado: los incidentes que lo usaron lo siguen señalando. */
	public void retirar() {
		if (estado != EstadoProtocolo.VIGENTE) {
			throw new ConflictoException(CodigoError.TRANSICION_INVALIDA,
					"La versión " + version + " del protocolo no es la vigente.");
		}
		estado = EstadoProtocolo.RETIRADO;
	}

	public Optional<TipoDeSuceso> buscarTipo(String codigo) {
		return tipos.stream().filter(tipo -> tipo.getCodigo().equals(codigo)).findFirst();
	}

	private void exigirBorrador() {
		if (!isBorrador()) {
			throw new ConflictoException(CodigoError.TRANSICION_INVALIDA,
					"La versión " + version + " del protocolo ya no es un borrador y no se puede editar.");
		}
	}

}
