package com.uem.ambulancias.soporte;

import java.text.Normalizer;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.uem.ambulancias.coordinacion.domain.ConclusionDelAgente;
import com.uem.ambulancias.coordinacion.service.ConclusionesRecibidas;
import com.uem.ambulancias.coordinacion.service.ModeloDeDecision;
import com.uem.ambulancias.coordinacion.service.PedidoDeEvaluacion;
import com.uem.ambulancias.coordinacion.service.PedidoDeRazonamiento;
import com.uem.ambulancias.coordinacion.service.ProbabilidadesRecibidas;
import com.uem.ambulancias.coordinacion.service.Razonador;
import com.uem.ambulancias.evidencias.service.AnalisisRecibido;
import com.uem.ambulancias.evidencias.service.FalloDelServicioIa;
import com.uem.ambulancias.evidencias.service.PedidoDeAnalisis;
import com.uem.ambulancias.evidencias.service.PedidoDeResumen;
import com.uem.ambulancias.evidencias.service.Procedencia;
import com.uem.ambulancias.evidencias.service.ResumenRecibido;
import com.uem.ambulancias.evidencias.service.ServicioDeAnalisis;
import com.uem.ambulancias.memoria.service.ModeloDeEmbeddings;

/**
 * Reemplaza al microservicio de IA en las pruebas. Responde lo que la prueba le prepara: por defecto un análisis
 * con transcripción y un resumen que usa todas las fuentes que recibió. Anota cada pedido para revisarlo después.
 *
 * <p>También hace de modelo de decisión. Sin nada preparado responde como el decisor de prueba del servicio: 0,95 a
 * la condición que el testigo contestó que sí, 0,05 a la que contestó que no y 0,5 a las demás.
 *
 * <p>Y de agente razonador, que arranca apagado para que las pruebas que no lo necesitan no lo vean. Encendido y sin
 * nada preparado, no resuelve ninguna condición: todas quedan en «no se sabe».
 */
public class ServicioIaFalso implements ServicioDeAnalisis, ModeloDeDecision, Razonador, ModeloDeEmbeddings {

	public static final String MODELO_DE_EMBEDDINGS = "embeddings-de-prueba";

	/** Tamaño de los vectores de prueba: holgado, para que dos palabras distintas casi nunca caigan juntas. */
	private static final int DIMENSIONES = 512;

	private final Map<Long, FalloDelServicioIa> fallosPorEvidencia = new ConcurrentHashMap<>();
	private final List<PedidoDeAnalisis> analisisPedidos = new CopyOnWriteArrayList<>();
	private final List<PedidoDeResumen> resumenesPedidos = new CopyOnWriteArrayList<>();
	private final List<PedidoDeEvaluacion> evaluacionesPedidas = new CopyOnWriteArrayList<>();
	private final Map<String, Double> probabilidades = new ConcurrentHashMap<>();
	private volatile FalloDelServicioIa falloDelResumen;
	private volatile FalloDelServicioIa falloDeLaEvaluacion;
	private volatile String analisisPreparado;
	private final List<PedidoDeRazonamiento> razonamientosPedidos = new CopyOnWriteArrayList<>();
	private final Map<String, ConclusionesRecibidas.Conclusion> conclusiones = new ConcurrentHashMap<>();
	private volatile boolean razonadorEncendido;
	private volatile FalloDelServicioIa falloDelRazonamiento;
	private final List<List<String>> textosEmbebidos = new CopyOnWriteArrayList<>();
	private volatile boolean embeddingsEncendidos;
	private volatile FalloDelServicioIa falloDeLosEmbeddings;

	@Override
	public AnalisisRecibido analizar(PedidoDeAnalisis pedido) {
		analisisPedidos.add(pedido);
		FalloDelServicioIa fallo = fallosPorEvidencia.get(pedido.evidenciaId());
		if (fallo != null) {
			throw fallo;
		}
		String modalidad = pedido.mimeType().startsWith("audio/") ? "AUDIO" : "IMAGEN";
		if (analisisPreparado != null) {
			return new AnalisisRecibido(modalidad, "evidence-analysis.v2", analisisPreparado, procedencia());
		}
		String analisis = """
				{"transcript": "Se escucha a una persona pidiendo ayuda (evidencia %d)",
				 "timeline": [{"startSecond": 0.0, "text": "Pedido de ayuda"}, {"startSecond": 4.5, "text": "Sirena"}],
				 "facts": ["persona en el piso"]}
				""".formatted(pedido.evidenciaId());
		return new AnalisisRecibido(modalidad, "1.0", analisis, procedencia());
	}

	@Override
	public ResumenRecibido resumir(PedidoDeResumen pedido) {
		resumenesPedidos.add(pedido);
		if (falloDelResumen != null) {
			throw falloDelResumen;
		}
		List<Long> evidencias = pedido.evidencias().stream().map(PedidoDeResumen.EvidenciaAnalizada::evidenciaId)
				.toList();
		List<Long> alertas = pedido.alertas().stream().map(PedidoDeResumen.AlertaDelIncidente::alertaId).toList();
		String resumen = """
				{"eventType": "CAIDA", "people": %d, "risks": ["golpe en la cabeza"], "suggestedSeverity": "ALTA"}
				""".formatted(alertas.size());
		return new ResumenRecibido(resumen, evidencias, alertas, procedencia());
	}

	@Override
	public ProbabilidadesRecibidas evaluar(PedidoDeEvaluacion pedido) {
		evaluacionesPedidas.add(pedido);
		if (falloDeLaEvaluacion != null) {
			throw falloDeLaEvaluacion;
		}
		Map<String, Double> respuesta = new HashMap<>();
		for (PedidoDeEvaluacion.Pregunta pregunta : pedido.preguntas()) {
			Double preparada = probabilidades.get(pregunta.codigo());
			respuesta.put(pregunta.codigo(), preparada != null ? preparada : segunElTestigo(pedido, pregunta.codigo()));
		}
		return new ProbabilidadesRecibidas(respuesta, "prueba", "decisor-de-prueba", 120, 0.0004);
	}

	private static double segunElTestigo(PedidoDeEvaluacion pedido, String condicion) {
		String respuesta = Json.textoOpcional(pedido.estadoJson(), "$.respuestasDelTestigo." + condicion);
		if ("SI".equals(respuesta)) {
			return 0.95;
		}
		return "NO".equals(respuesta) ? 0.05 : 0.5;
	}

	@Override
	public boolean disponible() {
		return razonadorEncendido;
	}

	@Override
	public ConclusionesRecibidas razonar(PedidoDeRazonamiento pedido) {
		razonamientosPedidos.add(pedido);
		if (falloDelRazonamiento != null) {
			throw falloDelRazonamiento;
		}
		List<ConclusionesRecibidas.Conclusion> respuesta = pedido.preguntas().stream()
				.map(pregunta -> conclusiones.getOrDefault(pregunta.codigo(), new ConclusionesRecibidas.Conclusion(
						pregunta.codigo(), ConclusionDelAgente.NO_SE_SABE, "La información no alcanza.", List.of())))
				.toList();
		return new ConclusionesRecibidas(respuesta, List.of("ver_caso", "buscar_casos_parecidos"), "prueba",
				"razonador-de-prueba", "reasoning-v1", 900, 120, 3);
	}

	/** Desde ahora hay un agente al que pedirle razonamientos. */
	public void encenderRazonador() {
		razonadorEncendido = true;
	}

	/** Desde ahora, el agente concluye eso sobre esa condición. */
	public void concluir(String condicion, ConclusionDelAgente veredicto, String justificacion, String... fuentes) {
		conclusiones.put(condicion,
				new ConclusionesRecibidas.Conclusion(condicion, veredicto, justificacion, List.of(fuentes)));
	}

	/** Los razonamientos fallan con ese error hasta que se llame a {@link #olvidar()}. */
	public void fallarRazonamientos(String codigo, FalloDelServicioIa.Desenlace desenlace) {
		falloDelRazonamiento = new FalloDelServicioIa(codigo, desenlace);
	}

	public List<PedidoDeRazonamiento> razonamientosPedidos() {
		return List.copyOf(razonamientosPedidos);
	}

	/** Desde ahora, el modelo de decisión le da esa probabilidad a esa condición. */
	public void responderProbabilidad(String condicion, double probabilidad) {
		probabilidades.put(condicion, probabilidad);
	}

	/** Las evaluaciones fallan con ese error hasta que se llame a {@link #olvidar()}. */
	public void fallarEvaluaciones(String codigo, FalloDelServicioIa.Desenlace desenlace) {
		falloDeLaEvaluacion = new FalloDelServicioIa(codigo, desenlace);
	}

	/** El modelo de decisión vuelve a responder. */
	public void repararEvaluaciones() {
		falloDeLaEvaluacion = null;
	}

	/** Desde ahora, cada evidencia se analiza con ese JSON, el {@code analysis} del servicio. */
	public void analizarCon(String analisisJson) {
		analisisPreparado = analisisJson;
	}

	public List<PedidoDeEvaluacion> evaluacionesPedidas() {
		return List.copyOf(evaluacionesPedidas);
	}

	/** La próxima vez que se analice esa evidencia, el servicio responde con ese error. */
	public void fallarAnalisisDe(long evidenciaId, String codigo, FalloDelServicioIa.Desenlace desenlace) {
		fallosPorEvidencia.put(evidenciaId, new FalloDelServicioIa(codigo, desenlace));
	}

	/** Los resúmenes fallan con ese error hasta que se llame a {@link #olvidar()}. */
	public void fallarResumenes(String codigo, FalloDelServicioIa.Desenlace desenlace) {
		falloDelResumen = new FalloDelServicioIa(codigo, desenlace);
	}

	public List<PedidoDeAnalisis> analisisPedidos() {
		return List.copyOf(analisisPedidos);
	}

	public List<PedidoDeResumen> resumenesPedidos() {
		return List.copyOf(resumenesPedidos);
	}

	@Override
	public boolean listoParaEmbeber() {
		return embeddingsEncendidos;
	}

	/**
	 * Como el modelo de prueba del servicio: cada palabra suma en una posición fija del vector, así que dos textos
	 * quedan cerca solo si comparten palabras. Alcanza para probar el flujo, no entiende sinónimos.
	 */
	@Override
	public VectoresRecibidos embeber(List<String> textos) {
		textosEmbebidos.add(List.copyOf(textos));
		if (falloDeLosEmbeddings != null) {
			throw falloDeLosEmbeddings;
		}
		return new VectoresRecibidos(textos.stream().map(ServicioIaFalso::vectorDe).toList(), MODELO_DE_EMBEDDINGS);
	}

	private static float[] vectorDe(String texto) {
		float[] vector = new float[DIMENSIONES];
		String plano = Normalizer.normalize(texto.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
				.replaceAll("\\p{M}", "");
		for (String palabra : plano.split("[^a-z0-9]+")) {
			// Las palabras muy cortas son artículos y preposiciones: están en todos los textos y no distinguen nada.
			if (palabra.length() > 3) {
				vector[Math.floorMod(palabra.hashCode(), DIMENSIONES)] += 1;
			}
		}
		return vector;
	}

	/** Enciende la búsqueda por parecido, que arranca apagada como en el servidor. */
	public void encenderEmbeddings() {
		embeddingsEncendidos = true;
	}

	public void fallarEmbeddings(String codigo, FalloDelServicioIa.Desenlace desenlace) {
		falloDeLosEmbeddings = new FalloDelServicioIa(codigo, desenlace);
	}

	/** Los textos de cada pedido de vectores, en el orden en que llegaron. */
	public List<List<String>> textosEmbebidos() {
		return List.copyOf(textosEmbebidos);
	}

	/** Cada prueba empieza con un servicio que responde bien y sin pedidos anotados. */
	public void olvidar() {
		fallosPorEvidencia.clear();
		analisisPedidos.clear();
		resumenesPedidos.clear();
		evaluacionesPedidas.clear();
		probabilidades.clear();
		falloDelResumen = null;
		falloDeLaEvaluacion = null;
		analisisPreparado = null;
		razonamientosPedidos.clear();
		conclusiones.clear();
		razonadorEncendido = false;
		falloDelRazonamiento = null;
		textosEmbebidos.clear();
		embeddingsEncendidos = false;
		falloDeLosEmbeddings = null;
	}

	private static Procedencia procedencia() {
		return new Procedencia("prueba", "modelo-de-prueba", "prompt-1", Instant.now(), "LLM");
	}

}
