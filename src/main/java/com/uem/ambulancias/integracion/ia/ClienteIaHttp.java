package com.uem.ambulancias.integracion.ia;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.uem.ambulancias.coordinacion.domain.ConclusionDelAgente;
import com.uem.ambulancias.coordinacion.service.ConclusionesRecibidas;
import com.uem.ambulancias.coordinacion.service.ModeloDeDecision;
import com.uem.ambulancias.coordinacion.service.PedidoDeEvaluacion;
import com.uem.ambulancias.coordinacion.service.PedidoDeRazonamiento;
import com.uem.ambulancias.coordinacion.service.ProbabilidadesRecibidas;
import com.uem.ambulancias.coordinacion.service.Razonador;
import com.uem.ambulancias.evidencias.service.AnalisisRecibido;
import com.uem.ambulancias.evidencias.service.FalloDelServicioIa.Desenlace;
import com.uem.ambulancias.evidencias.service.FalloDelServicioIa;
import com.uem.ambulancias.evidencias.service.PedidoDeAnalisis;
import com.uem.ambulancias.evidencias.service.PedidoDeResumen;
import com.uem.ambulancias.evidencias.service.Procedencia;
import com.uem.ambulancias.evidencias.service.ResumenRecibido;
import com.uem.ambulancias.evidencias.service.ServicioDeAnalisis;
import com.uem.ambulancias.integracion.ia.ContratoIa.AnalysisRequest;
import com.uem.ambulancias.integracion.ia.ContratoIa.AnalysisResponse;
import com.uem.ambulancias.integracion.ia.ContratoIa.EmbeddingRequest;
import com.uem.ambulancias.integracion.ia.ContratoIa.EmbeddingResponse;
import com.uem.ambulancias.integracion.ia.ContratoIa.ErrorResponse;
import com.uem.ambulancias.integracion.ia.ContratoIa.EvaluationRequest;
import com.uem.ambulancias.integracion.ia.ContratoIa.EvaluationResponse;
import com.uem.ambulancias.integracion.ia.ContratoIa.ProtocolReviewRequest;
import com.uem.ambulancias.integracion.ia.ContratoIa.ProtocolReviewResponse;
import com.uem.ambulancias.integracion.ia.ContratoIa.Provenance;
import com.uem.ambulancias.integracion.ia.ContratoIa.ReasoningResponse;
import com.uem.ambulancias.integracion.ia.ContratoIa.ReviewFact;
import com.uem.ambulancias.integracion.ia.ContratoIa.ReviewNormExcerpt;
import com.uem.ambulancias.integracion.ia.ContratoIa.ReviewProposal;
import com.uem.ambulancias.integracion.ia.ContratoIa.SummaryRequest;
import com.uem.ambulancias.integracion.ia.ContratoIa.SummaryResponse;
import com.uem.ambulancias.memoria.service.ModeloDeEmbeddings;
import com.uem.ambulancias.revision.service.AnalistaDelProtocolo;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Llama al servicio de análisis por HTTP. Las llamadas son síncronas y largas: el timeout de lectura es mayor que el
 * peor caso del servicio. Cada error se traduce, según la tabla del servicio, en reintentar, firmar de nuevo, dar por
 * fallido o pedir revisión.
 *
 * <p>La evaluación de condiciones va por el mismo servicio, pero con su propio tiempo de espera, mucho más corto:
 * una decisión de despacho no puede quedarse esperando lo que se espera un video.
 */
@Slf4j
public class ClienteIaHttp
		implements ServicioDeAnalisis, ModeloDeDecision, Razonador, ModeloDeEmbeddings, AnalistaDelProtocolo {

	/** El archivo o el pedido no sirven: reintentar daría lo mismo. */
	private static final Set<String> DEFINITIVOS = Set.of("invalid_request", "unsupported_media_type",
			"evidence_empty", "evidence_too_large", "mime_mismatch", "checksum_mismatch", "duration_exceeded",
			"unreadable_media", "object_not_found", "download_failed", "invalid_download_url", "unknown_alert",
			"duplicate_source", "nothing_to_summarize", "too_many_sources", "state_too_large",
			"reasoning_limit_exceeded");

	private static final Set<String> PASAJEROS = Set.of("download_unavailable", "provider_unavailable",
			"invalid_model_output", "internal_error", "tools_unavailable");

	private static final Set<String> DE_CONFIGURACION = Set.of("unauthorized", "provider_rejected",
			"provider_misconfigured", "service_not_configured");

	private static final String URL_VENCIDA = "download_forbidden";

	private final RestClient rest;

	/** El mismo servicio, con el tiempo de espera corto de las decisiones. */
	private final RestClient restDeDecisiones;

	/** El mismo servicio, con el tiempo de espera de un razonamiento. */
	private final RestClient restDeRazonamientos;

	private final boolean razonamientoHabilitado;

	/** El mismo servicio, con el tiempo de espera corto de los embeddings: los pide el agente mientras razona. */
	private final RestClient restDeEmbeddings;

	private final boolean embeddingsHabilitado;

	private final boolean analistaHabilitado;

	public ClienteIaHttp(IaProperties config) {
		// Sin redirecciones: el servicio no las usa, y seguir una mandaría el token a otro lado. HTTP/1.1 porque
		// uvicorn no habla HTTP/2: el cliente de Java pediría pasar a h2c y el servicio deja un aviso por cada pedido.
		HttpClient http = HttpClient.newBuilder()
				.version(HttpClient.Version.HTTP_1_1)
				.connectTimeout(config.conexion())
				.followRedirects(HttpClient.Redirect.NEVER)
				.build();
		this.rest = cliente(http, config, config.lectura());
		this.restDeDecisiones = cliente(http, config, config.lecturaDeEvaluacion());
		this.restDeRazonamientos = cliente(http, config, config.lecturaDeRazonamiento());
		this.razonamientoHabilitado = config.razonamientoHabilitado();
		this.restDeEmbeddings = cliente(http, config, config.lecturaDeEmbeddings());
		this.embeddingsHabilitado = config.embeddingsHabilitado();
		this.analistaHabilitado = config.analistaHabilitado();
	}

	private static RestClient cliente(HttpClient http, IaProperties config, Duration lectura) {
		JdkClientHttpRequestFactory fabrica = new JdkClientHttpRequestFactory(http);
		fabrica.setReadTimeout(lectura);
		return RestClient.builder()
				.baseUrl(config.url())
				.requestFactory(fabrica)
				.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + config.token())
				.build();
	}

	@Override
	public AnalisisRecibido analizar(PedidoDeAnalisis pedido) {
		AnalysisRequest cuerpo = new AnalysisRequest(pedido.trabajo().toString(), pedido.evidenciaId(),
				pedido.alertaId(), pedido.incidenteId(), pedido.urlLectura(), pedido.mimeType(), pedido.sha256());
		AnalysisResponse respuesta = llamar("/v1/analyses", cuerpo, AnalysisResponse.class);
		if (respuesta.analysis() == null || !respuesta.analysis().isObject()) {
			throw new FalloDelServicioIa("respuesta_incompleta", Desenlace.REINTENTAR);
		}
		return new AnalisisRecibido(respuesta.modality(), respuesta.schemaVersion(), respuesta.analysis().toString(),
				procedencia(respuesta.provenance()));
	}

	@Override
	public ResumenRecibido resumir(PedidoDeResumen pedido) {
		SummaryRequest cuerpo = new SummaryRequest(
				pedido.incidenteId(),
				pedido.alertas().stream()
						.map(alerta -> new ContratoIa.Alert(alerta.alertaId(), texto(alerta.emitidaEn()),
								alerta.descripcion(), alerta.cantidadAfectados(), alerta.emisorEsPaciente()))
						.toList(),
				pedido.evidencias().stream()
						.map(evidencia -> new ContratoIa.Evidence(evidencia.evidenciaId(), evidencia.alertaId(),
								evidencia.modalidad(), texto(evidencia.recibidaEn()), evidencia.analisisJson()))
						.toList());
		SummaryResponse respuesta = llamar("/v1/summaries", cuerpo, SummaryResponse.class);
		if (respuesta.summary() == null || !respuesta.summary().isObject()) {
			throw new FalloDelServicioIa("respuesta_incompleta", Desenlace.REINTENTAR);
		}
		return new ResumenRecibido(respuesta.summary().toString(), lista(respuesta.usedEvidenceIds()),
				lista(respuesta.usedAlertIds()), procedencia(respuesta.provenance()));
	}

	@Override
	public ProbabilidadesRecibidas evaluar(PedidoDeEvaluacion pedido) {
		EvaluationRequest cuerpo = new EvaluationRequest(pedido.trabajo().toString(), pedido.incidenteId(),
				pedido.estadoJson(), pedido.preguntas().stream()
						.map(pregunta -> new ContratoIa.Question(pregunta.codigo(), pregunta.enunciado()))
						.toList());
		EvaluationResponse respuesta = llamar(restDeDecisiones, "/v1/evaluations", cuerpo, EvaluationResponse.class);
		return new ProbabilidadesRecibidas(probabilidades(pedido, respuesta),
				respuesta.provenance() == null ? null : respuesta.provenance().provider(),
				respuesta.provenance() == null ? null : respuesta.provenance().model(),
				respuesta.usage() == null ? null : respuesta.usage().inputTokens(),
				respuesta.usage() == null ? null : respuesta.usage().cost());
	}

	@Override
	public boolean disponible() {
		return razonamientoHabilitado;
	}

	@Override
	public ConclusionesRecibidas razonar(PedidoDeRazonamiento pedido) {
		EvaluationRequest cuerpo = new EvaluationRequest(pedido.trabajo().toString(), pedido.incidenteId(),
				pedido.estadoJson(), pedido.preguntas().stream()
						.map(pregunta -> new ContratoIa.Question(pregunta.codigo(), pregunta.enunciado()))
						.toList());
		ReasoningResponse respuesta = llamar(restDeRazonamientos, "/v1/reasonings", cuerpo, ReasoningResponse.class);
		return conclusiones(respuesta);
	}

	/** Las conclusiones tal como las mandó el servicio. Una sin condición no sirve y se descarta. */
	static ConclusionesRecibidas conclusiones(ReasoningResponse respuesta) {
		if (respuesta.conclusions() == null) {
			throw new FalloDelServicioIa("respuesta_incompleta", Desenlace.REINTENTAR);
		}
		List<ConclusionesRecibidas.Conclusion> conclusiones = respuesta.conclusions().stream()
				.filter(conclusion -> conclusion != null && conclusion.code() != null)
				.map(conclusion -> new ConclusionesRecibidas.Conclusion(conclusion.code(),
						ConclusionDelAgente.de(conclusion.verdict()),
						conclusion.rationale() == null ? "" : conclusion.rationale(), lista(conclusion.sources())))
				.toList();
		Provenance procedencia = respuesta.provenance();
		ContratoIa.ReasoningUsage uso = respuesta.usage();
		return new ConclusionesRecibidas(conclusiones, lista(respuesta.toolCalls()),
				procedencia == null ? null : procedencia.provider(), procedencia == null ? null : procedencia.model(),
				procedencia == null ? null : procedencia.promptVersion(), uso == null ? null : uso.inputTokens(),
				uso == null ? null : uso.outputTokens(), uso == null ? null : uso.requests());
	}

	/**
	 * Una probabilidad por cada condición consultada, entre 0 y 1. Si falta alguna o viene fuera de rango, la
	 * respuesta entera no sirve: decidir con la mitad de las condiciones sería decidir con otra pregunta.
	 */
	static Map<String, Double> probabilidades(PedidoDeEvaluacion pedido, EvaluationResponse respuesta) {
		Map<String, Double> recibidas = new HashMap<>();
		if (respuesta.answers() != null) {
			for (ContratoIa.Answer answer : respuesta.answers()) {
				if (answer != null && answer.code() != null && answer.probability() != null) {
					recibidas.put(answer.code(), answer.probability());
				}
			}
		}
		Map<String, Double> probabilidades = new HashMap<>();
		for (PedidoDeEvaluacion.Pregunta pregunta : pedido.preguntas()) {
			Double probabilidad = recibidas.get(pregunta.codigo());
			if (probabilidad == null || probabilidad.isNaN() || probabilidad < 0 || probabilidad > 1) {
				throw new FalloDelServicioIa("respuesta_incompleta", Desenlace.REINTENTAR);
			}
			probabilidades.put(pregunta.codigo(), probabilidad);
		}
		return probabilidades;
	}

	@Override
	public boolean listoParaEmbeber() {
		return embeddingsHabilitado;
	}

	@Override
	public VectoresRecibidos embeber(List<String> textos) {
		EmbeddingResponse respuesta = llamar(restDeEmbeddings, "/v1/embeddings",
				new EmbeddingRequest(UUID.randomUUID().toString(), textos), EmbeddingResponse.class);
		return vectores(respuesta, textos.size());
	}

	/** Un vector por texto, todos del mismo tamaño y con el modelo que los produjo. Si no, no sirven. */
	static VectoresRecibidos vectores(EmbeddingResponse respuesta, int esperados) {
		if (respuesta.vectors() == null || respuesta.vectors().size() != esperados || respuesta.provenance() == null
				|| respuesta.provenance().model() == null || respuesta.provenance().model().isBlank()) {
			throw new FalloDelServicioIa("respuesta_incompleta", Desenlace.REINTENTAR);
		}
		List<float[]> vectores = new ArrayList<>();
		for (List<Double> recibido : respuesta.vectors()) {
			if (recibido == null || recibido.isEmpty()
					|| (!vectores.isEmpty() && recibido.size() != vectores.getFirst().length)) {
				throw new FalloDelServicioIa("respuesta_incompleta", Desenlace.REINTENTAR);
			}
			float[] vector = new float[recibido.size()];
			for (int i = 0; i < vector.length; i++) {
				Double valor = recibido.get(i);
				if (valor == null || valor.isNaN() || valor.isInfinite()) {
					throw new FalloDelServicioIa("respuesta_incompleta", Desenlace.REINTENTAR);
				}
				vector[i] = valor.floatValue();
			}
			vectores.add(vector);
		}
		return new VectoresRecibidos(vectores, respuesta.provenance().model());
	}

	@Override
	public boolean listoParaRevisar() {
		return analistaHabilitado;
	}

	/** Una revisión tarda lo que un razonamiento: usa su mismo tiempo de espera. */
	@Override
	public PropuestasRecibidas revisar(PedidoDeRevision pedido) {
		ProtocolReviewRequest cuerpo = new ProtocolReviewRequest(pedido.trabajo().toString(), pedido.protocoloJson(),
				pedido.hechos().stream()
						.map(hecho -> new ReviewFact(hecho.id(), hecho.tipoDeSuceso(), hecho.condicion(), hecho.texto()))
						.toList(),
				pedido.norma().stream()
						.map(extracto -> new ReviewNormExcerpt(extracto.id(), extracto.cita(), extracto.texto()))
						.toList());
		return propuestas(llamar(restDeRazonamientos, "/v1/protocol-reviews", cuerpo, ProtocolReviewResponse.class));
	}

	/** Las propuestas tal como llegaron. Qué se hace con cada una lo decide quien las pidió. */
	static PropuestasRecibidas propuestas(ProtocolReviewResponse respuesta) {
		if (respuesta.proposals() == null) {
			throw new FalloDelServicioIa("respuesta_incompleta", Desenlace.REINTENTAR);
		}
		List<Propuesta> propuestas = new ArrayList<>();
		for (ReviewProposal recibida : respuesta.proposals()) {
			if (recibida == null || recibida.kind() == null || recibida.eventType() == null
					|| recibida.proposal() == null || recibida.rationale() == null) {
				throw new FalloDelServicioIa("respuesta_incompleta", Desenlace.REINTENTAR);
			}
			propuestas.add(new Propuesta(recibida.kind(), recibida.eventType(), recibida.condition(),
					recibida.proposal(), recibida.rationale(), lista(recibida.factIds()), lista(recibida.normIds())));
		}
		Provenance procedencia = respuesta.provenance();
		return new PropuestasRecibidas(propuestas, respuesta.discarded() == null ? 0 : respuesta.discarded(),
				procedencia == null ? null : procedencia.provider(), procedencia == null ? null : procedencia.model(),
				procedencia == null ? null : procedencia.promptVersion());
	}

	private <T> T llamar(String ruta, Object cuerpo, Class<T> tipo) {
		return llamar(rest, ruta, cuerpo, tipo);
	}

	private static <T> T llamar(RestClient rest, String ruta, Object cuerpo, Class<T> tipo) {
		try {
			return rest.post()
					.uri(ruta)
					.contentType(MediaType.APPLICATION_JSON)
					.body(cuerpo)
					.exchange((peticion, respuesta) -> {
						if (respuesta.getStatusCode().is2xxSuccessful()) {
							T leido = respuesta.bodyTo(tipo);
							if (leido == null) {
								throw new FalloDelServicioIa("respuesta_vacia", Desenlace.REINTENTAR);
							}
							return leido;
						}
						throw fallo(respuesta.getStatusCode(), leerError(respuesta));
					});
		} catch (FalloDelServicioIa e) {
			throw e;
		} catch (RestClientException e) {
			// No respondió, se cortó o mandó algo ilegible: puede ser pasajero.
			throw new FalloDelServicioIa("sin_respuesta", Desenlace.REINTENTAR, e);
		}
	}

	private static ErrorResponse leerError(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse respuesta) {
		try {
			return respuesta.bodyTo(ErrorResponse.class);
		} catch (RuntimeException e) {
			return null;
		}
	}

	/**
	 * La tabla de errores del servicio. Un código que no figura se resuelve con su {@code retryable}, y si ni eso
	 * llegó, con el estado HTTP.
	 */
	static FalloDelServicioIa fallo(HttpStatusCode estado, ErrorResponse error) {
		String codigo = error != null && error.errorCode() != null ? error.errorCode() : "http_" + estado.value();
		return new FalloDelServicioIa(codigo, desenlace(estado, codigo, error == null ? null : error.retryable()));
	}

	static Desenlace desenlace(HttpStatusCode estado, String codigo, Boolean reintentable) {
		if (URL_VENCIDA.equals(codigo)) {
			return Desenlace.FIRMAR_DE_NUEVO;
		}
		if (DE_CONFIGURACION.contains(codigo)) {
			return Desenlace.REVISION;
		}
		if (DEFINITIVOS.contains(codigo)) {
			return Desenlace.DEFINITIVO;
		}
		if (PASAJEROS.contains(codigo)) {
			return Desenlace.REINTENTAR;
		}
		log.warn("El servicio de análisis respondió un código que no está en su contrato: {} ({}).", codigo, estado);
		if (reintentable != null) {
			return reintentable ? Desenlace.REINTENTAR : Desenlace.DEFINITIVO;
		}
		// Una ruta que no existe o un token rechazado son configuración, no la evidencia.
		if (estado.value() == HttpStatus.UNAUTHORIZED.value() || estado.value() == HttpStatus.FORBIDDEN.value()
				|| estado.value() == HttpStatus.NOT_FOUND.value()) {
			return Desenlace.REVISION;
		}
		return estado.is5xxServerError() || estado.value() == HttpStatus.TOO_MANY_REQUESTS.value()
				? Desenlace.REINTENTAR
				: Desenlace.DEFINITIVO;
	}

	private static Procedencia procedencia(Provenance procedencia) {
		if (procedencia == null) {
			return new Procedencia(null, null, null, null, null);
		}
		return new Procedencia(procedencia.provider(), procedencia.model(), procedencia.promptVersion(),
				instante(procedencia.generatedAt()), procedencia.method());
	}

	/** La fecha del servicio puede venir con {@code Z} o con desfase ({@code +00:00}). Si no se entiende, queda vacía. */
	private static Instant instante(String texto) {
		if (texto == null) {
			return null;
		}
		try {
			return OffsetDateTime.parse(texto).toInstant();
		} catch (DateTimeParseException e) {
			return null;
		}
	}

	private static String texto(Instant instante) {
		return instante == null ? null : instante.toString();
	}

	private static <T> List<T> lista(List<T> elementos) {
		return elementos == null ? List.of() : elementos;
	}

}
