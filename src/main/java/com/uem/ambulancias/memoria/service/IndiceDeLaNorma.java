package com.uem.ambulancias.memoria.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.uem.ambulancias.comun.error.CodigoError;
import com.uem.ambulancias.comun.error.ConflictoException;
import com.uem.ambulancias.evidencias.service.FalloDelServicioIa;
import com.uem.ambulancias.memoria.domain.FragmentoDeNorma;
import com.uem.ambulancias.memoria.repository.FragmentoDeNormaRepository;
import com.uem.ambulancias.memoria.service.ModeloDeEmbeddings.VectoresRecibidos;
import com.uem.ambulancias.memoria.service.TextosDeLaNorma.Extracto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Los extractos de las normas, listos para buscarlos por lo que dicen. Indexar es convertir cada extracto en un
 * vector; buscar es convertir la consulta y quedarse con los extractos cuyo vector está más cerca.
 *
 * <p>Ningún método abre una transacción: adentro se llama al servicio de IA, que puede tardar.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndiceDeLaNorma {

	private final FragmentoDeNormaRepository fragmentos;
	private final TextosDeLaNorma textos;
	private final ObjectProvider<ModeloDeEmbeddings> modelo;
	private final MemoriaProperties config;

	/**
	 * @param extractos          cuántos hay en los archivos de la norma
	 * @param indexados          cuántos tienen su vector y se pueden buscar
	 * @param modelos            con qué modelos se calcularon los vectores; más de uno pide volver a indexar
	 * @param busquedaEncendida  si la búsqueda por parecido está encendida
	 */
	public record Estado(long extractos, long indexados, List<String> modelos, boolean busquedaEncendida) {
	}

	/** Un extracto que se parece a lo que se buscó, con qué tanto se parece (de 0 a 1). */
	public record Hallazgo(String documento, String titulo, String referencia, String seccion, Integer pagina,
			String texto, double similitud) {
	}

	public Estado estado() {
		List<FragmentoDeNorma> guardados = fragmentos.findAllByOrderByIdAsc();
		return new Estado(textos.todos().size(),
				guardados.stream().filter(fragmento -> fragmento.getVector() != null).count(),
				guardados.stream().map(FragmentoDeNorma::getModelo).filter(uno -> uno != null).distinct().sorted()
						.toList(),
				encendido().isPresent());
	}

	/**
	 * Deja la tabla igual a los archivos y calcula el vector de todos los extractos. Se vuelven a calcular todos a
	 * propósito: son pocos, y así un cambio de modelo no deja vectores que no se pueden comparar entre sí.
	 */
	public Estado indexar() {
		ModeloDeEmbeddings embeddings = encendido().orElseThrow(() -> new ConflictoException(
				CodigoError.EMBEDDINGS_NO_DISPONIBLES,
				"La búsqueda por parecido está apagada: enciende sga.ia.embeddings-habilitado."));
		sincronizar();
		List<FragmentoDeNorma> todos = fragmentos.findAllByOrderByIdAsc();
		try {
			for (int desde = 0; desde < todos.size(); desde += config.lote()) {
				List<FragmentoDeNorma> lote = todos.subList(desde, Math.min(desde + config.lote(), todos.size()));
				VectoresRecibidos recibidos = embeddings.embeber(lote.stream().map(FragmentoDeNorma::getTexto).toList());
				Instant ahora = Instant.now();
				for (int i = 0; i < lote.size(); i++) {
					lote.get(i).indexar(Vectores.aBytes(recibidos.vectores().get(i)), recibidos.modelo(), ahora);
				}
				fragmentos.saveAll(lote);
			}
		} catch (FalloDelServicioIa e) {
			log.warn("No se pudo indexar la norma: {} ({}).", e.getCodigo(), e.getDesenlace());
			throw new ConflictoException(CodigoError.EMBEDDINGS_NO_DISPONIBLES,
					"El servicio de IA no pudo convertir los textos (" + e.getCodigo() + "). Intenta de nuevo.");
		}
		return estado();
	}

	/**
	 * Los extractos más cercanos a la consulta, del más parecido al menos. Vacío si la búsqueda está apagada, si
	 * todavía no se indexó o si el servicio de IA no responde: quien busca sigue con lo que tenga.
	 */
	public List<Hallazgo> buscar(String consulta) {
		ModeloDeEmbeddings embeddings = encendido().orElse(null);
		if (embeddings == null || consulta == null || consulta.isBlank() || fragmentos.countByVectorIsNotNull() == 0) {
			return List.of();
		}
		VectoresRecibidos recibidos;
		try {
			recibidos = embeddings.embeber(List.of(recortar(consulta.strip())));
		} catch (FalloDelServicioIa e) {
			log.warn("Búsqueda en la norma sin embeddings: {} ({}).", e.getCodigo(), e.getDesenlace());
			return List.of();
		}
		float[] buscado = recibidos.vectores().getFirst();
		List<Hallazgo> hallazgos = new ArrayList<>();
		for (FragmentoDeNorma fragmento : fragmentos.findAllByOrderByIdAsc()) {
			if (!fragmento.indexadoCon(recibidos.modelo())) {
				continue;
			}
			double similitud = Vectores.coseno(buscado, Vectores.deBytes(fragmento.getVector()));
			if (similitud >= config.similitudMinima()) {
				hallazgos.add(new Hallazgo(fragmento.getDocumento(), fragmento.getTitulo(), fragmento.getReferencia(),
						fragmento.getSeccion(), fragmento.getPagina(), fragmento.getTexto(), similitud));
			}
		}
		hallazgos.sort(Comparator.comparingDouble(Hallazgo::similitud).reversed());
		return List.copyOf(hallazgos.subList(0, Math.min(config.fragmentos(), hallazgos.size())));
	}

	/** Borra los extractos que ya no están en los archivos y agrega los nuevos. Los demás conservan su vector. */
	private void sincronizar() {
		Map<String, Extracto> vigentes = textos.todos().stream()
				.collect(Collectors.toMap(Extracto::huella, Function.identity(), (uno, otro) -> uno));
		List<FragmentoDeNorma> guardados = fragmentos.findAllByOrderByIdAsc();
		fragmentos.deleteAll(guardados.stream().filter(guardado -> !vigentes.containsKey(guardado.getHuella()))
				.toList());
		List<String> yaEstan = guardados.stream().map(FragmentoDeNorma::getHuella).toList();
		fragmentos.saveAll(textos.todos().stream()
				.filter(extracto -> !yaEstan.contains(extracto.huella()))
				.map(extracto -> FragmentoDeNorma.de(extracto.documento(), extracto.titulo(), extracto.referencia(),
						extracto.seccion(), extracto.pagina(), extracto.texto(), extracto.huella()))
				.toList());
	}

	private Optional<ModeloDeEmbeddings> encendido() {
		return Optional.ofNullable(modelo.getIfAvailable()).filter(ModeloDeEmbeddings::listoParaEmbeber);
	}

	private static String recortar(String texto) {
		return texto.length() <= TextosDeLaNorma.LARGO_MAXIMO ? texto : texto.substring(0, TextosDeLaNorma.LARGO_MAXIMO);
	}

}
