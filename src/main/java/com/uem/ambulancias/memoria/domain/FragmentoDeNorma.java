package com.uem.ambulancias.memoria.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Un extracto de una norma, con el vector que permite encontrarlo por lo que dice y no por las palabras que usa. El
 * texto sale de los archivos de {@code resources/norma}; acá se guarda junto con su vector para no volver a
 * calcularlo en cada búsqueda.
 *
 * <p>{@code huella} identifica al extracto por su contenido: si el texto cambia en el archivo, es otro extracto y el
 * anterior se borra al indexar.
 */
@Entity
@Table(name = "fragmento_de_norma", uniqueConstraints = @UniqueConstraint(name = "uk_fragmento_de_norma_huella",
		columnNames = "huella"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FragmentoDeNorma {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** Una clave corta del documento, por ejemplo {@code NORMA_AMBULANCIAS_TERRESTRES}. */
	@Column(nullable = false, length = 80)
	private String documento;

	@Column(nullable = false, length = 255)
	private String titulo;

	/** Quién la emitió y con qué resolución: lo que se cita. */
	@Column(nullable = false, length = 500)
	private String referencia;

	@Column(nullable = false, length = 255)
	private String seccion;

	private Integer pagina;

	@Column(nullable = false, columnDefinition = "text")
	private String texto;

	@Column(nullable = false, length = 64)
	private String huella;

	/** El embedding, cuatro bytes por número. Nulo mientras no se indexó. */
	private byte[] vector;

	/** El modelo que produjo el vector. Uno de otro modelo no se puede comparar. */
	@Column(length = 120)
	private String modelo;

	private Instant indexadoEn;

	public static FragmentoDeNorma de(String documento, String titulo, String referencia, String seccion,
			Integer pagina, String texto, String huella) {
		FragmentoDeNorma fragmento = new FragmentoDeNorma();
		fragmento.documento = documento;
		fragmento.titulo = titulo;
		fragmento.referencia = referencia;
		fragmento.seccion = seccion;
		fragmento.pagina = pagina;
		fragmento.texto = texto;
		fragmento.huella = huella;
		return fragmento;
	}

	public void indexar(byte[] vector, String modelo, Instant ahora) {
		this.vector = vector;
		this.modelo = modelo;
		this.indexadoEn = ahora;
	}

	public boolean indexadoCon(String modelo) {
		return vector != null && modelo != null && modelo.equals(this.modelo);
	}

}
