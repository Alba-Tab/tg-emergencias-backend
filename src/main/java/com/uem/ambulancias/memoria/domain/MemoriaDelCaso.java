package com.uem.ambulancias.memoria.domain;

import java.time.Instant;

import com.uem.ambulancias.emergencias.domain.Incidente;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Lo que quedó de un incidente cerrado para poder encontrarlo cuando llegue uno parecido: un resumen de la situación,
 * sin datos de las personas, y su vector. Cómo terminó no entra en el resumen: es lo que se quiere averiguar, no lo
 * que se compara.
 */
@Entity
@Table(name = "memoria_del_caso")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemoriaDelCaso {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "incidente_id", nullable = false, unique = true)
	private Incidente incidente;

	@Column(nullable = false, length = 60)
	private String tipoSuceso;

	/** El resumen que se convirtió en vector. Vacío si del incidente no se pudo armar nada. */
	@Column(columnDefinition = "text")
	private String texto;

	private byte[] vector;

	@Column(length = 120)
	private String modelo;

	@Column(nullable = false)
	private Instant creadaEn;

	private Instant indexadaEn;

	public static MemoriaDelCaso de(Incidente incidente, String texto, Instant ahora) {
		MemoriaDelCaso memoria = new MemoriaDelCaso();
		memoria.incidente = incidente;
		memoria.tipoSuceso = incidente.getTipoSuceso();
		memoria.texto = texto;
		memoria.creadaEn = ahora;
		return memoria;
	}

	public void indexar(byte[] vector, String modelo, Instant ahora) {
		this.vector = vector;
		this.modelo = modelo;
		this.indexadaEn = ahora;
	}

	public boolean indexadaCon(String modelo) {
		return vector != null && modelo != null && modelo.equals(this.modelo);
	}

}
