package com.uem.ambulancias.memoria.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Los extractos de las normas que el agente puede consultar. Viven en {@code resources/norma}, un archivo por
 * documento: son los capítulos que sirven al despacho, con los defectos del escaneo corregidos. Para sumar o corregir
 * un extracto se edita el archivo y se vuelve a indexar.
 */
@Component
public class TextosDeLaNorma {

	/** Lo que entra holgado en un pedido de embeddings. */
	static final int LARGO_MAXIMO = 6000;

	private final List<Extracto> extractos;

	/**
	 * @param referencia quién emitió el documento y con qué resolución: lo que se cita
	 * @param huella     identifica al extracto por su contenido: cambia si cambia el texto o la sección
	 */
	public record Extracto(String documento, String titulo, String referencia, String seccion, Integer pagina,
			String texto, String huella) {
	}

	public TextosDeLaNorma(JsonMapper json) {
		this.extractos = leer(json);
	}

	public List<Extracto> todos() {
		return extractos;
	}

	private static List<Extracto> leer(JsonMapper json) {
		List<Extracto> leidos = new ArrayList<>();
		try {
			Resource[] archivos = new PathMatchingResourcePatternResolver().getResources("classpath:norma/*.json");
			Arrays.sort(archivos, Comparator.comparing(Resource::getFilename));
			for (Resource archivo : archivos) {
				try (InputStream entrada = archivo.getInputStream()) {
					JsonNode raiz = json.readTree(entrada);
					String documento = obligatorio(raiz, "documento", archivo);
					String titulo = obligatorio(raiz, "titulo", archivo);
					String referencia = obligatorio(raiz, "referencia", archivo);
					for (JsonNode fragmento : raiz.path("fragmentos")) {
						String seccion = obligatorio(fragmento, "seccion", archivo);
						String texto = obligatorio(fragmento, "texto", archivo);
						if (texto.length() > LARGO_MAXIMO) {
							throw new IllegalStateException("El extracto «" + seccion + "» de " + archivo.getFilename()
									+ " es demasiado largo: hay que partirlo.");
						}
						leidos.add(new Extracto(documento, titulo, referencia, seccion,
								fragmento.path("pagina").isNumber() ? fragmento.path("pagina").asInt() : null, texto,
								huella(documento, seccion, texto)));
					}
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException("No se pudieron leer los extractos de la norma.", e);
		}
		return List.copyOf(leidos);
	}

	private static String obligatorio(JsonNode nodo, String campo, Resource archivo) {
		JsonNode valor = nodo.path(campo);
		if (!valor.isString() || valor.asString().isBlank()) {
			throw new IllegalStateException("Falta «" + campo + "» en " + archivo.getFilename() + ".");
		}
		return valor.asString().strip();
	}

	static String huella(String documento, String seccion, String texto) {
		try {
			MessageDigest sha = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(sha.digest((documento + "\n" + seccion + "\n" + texto)
					.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

}
