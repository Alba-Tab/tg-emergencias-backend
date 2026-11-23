package com.uem.ambulancias.revision.domain;

import java.time.Instant;

import com.uem.ambulancias.protocolo.domain.Protocolo;
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
 * Una revisión del protocolo vigente hecha por el analista: los hechos de cómo funcionó, tal como se contaron, y las
 * propuestas que salieron de ellos. Es cola y registro a la vez. Las propuestas no cambian nada: las lee una persona
 * y, si le sirven, edita un borrador del protocolo.
 */
@Entity
@Table(name = "revision_del_protocolo")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RevisionDelProtocolo {

	public static final String PENDIENTE = "PENDIENTE";
	public static final String EN_CURSO = "EN_CURSO";
	public static final String HECHA = "HECHA";
	public static final String FALLIDA = "FALLIDA";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** PENDIENTE, EN_CURSO, HECHA o FALLIDA. Se guarda como texto. */
	@Column(nullable = false, length = 20)
	private String estado;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "protocolo_id", nullable = false)
	private Protocolo protocolo;

	@Column(nullable = false)
	private int versionDelProtocolo;

	/** Lo que se contó del uso del protocolo y se le mandó al analista. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	private String hechos;

	/** Las propuestas que pasaron las reglas, cada una con los hechos que la respaldan. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	private String propuestas;

	/** Cuántas propuestas del analista no pasaron las reglas. */
	@Column(nullable = false)
	private int descartadas;

	private String proveedor;

	private String modelo;

	@Column(length = 60)
	private String versionDelPrompt;

	/** Por qué no hay propuestas, cuando no las hay: falló el analista o no había nada que contar. */
	@Column(length = 300)
	private String nota;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "pedida_por_id")
	private Usuario pedidaPor;

	@Column(nullable = false)
	private Instant creadaEn;

	private Instant terminadaEn;

	public static RevisionDelProtocolo pedir(Protocolo protocolo, Usuario pedidaPor, Instant ahora) {
		RevisionDelProtocolo revision = new RevisionDelProtocolo();
		revision.estado = PENDIENTE;
		revision.protocolo = protocolo;
		revision.versionDelProtocolo = protocolo.getVersion();
		revision.pedidaPor = pedidaPor;
		revision.creadaEn = ahora;
		return revision;
	}

	public boolean terminada() {
		return HECHA.equals(estado) || FALLIDA.equals(estado);
	}

	public void empezar(String hechos) {
		this.estado = EN_CURSO;
		this.hechos = hechos;
	}

	public void concluir(String propuestas, int descartadas, String proveedor, String modelo, String versionDelPrompt,
			String nota, Instant ahora) {
		this.estado = HECHA;
		this.propuestas = propuestas;
		this.descartadas = descartadas;
		this.proveedor = proveedor;
		this.modelo = modelo;
		this.versionDelPrompt = versionDelPrompt;
		this.nota = nota;
		this.terminadaEn = ahora;
	}

	public void fallar(String nota, Instant ahora) {
		this.estado = FALLIDA;
		this.nota = nota;
		this.terminadaEn = ahora;
	}

}
