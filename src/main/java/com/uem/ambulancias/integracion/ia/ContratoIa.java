package com.uem.ambulancias.integracion.ia;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonRawValue;
import tools.jackson.databind.JsonNode;

/**
 * Los cuerpos de {@code /v1/analyses}, {@code /v1/summaries}, {@code /v1/evaluations} y
 * {@code /v1/reasonings} tal como los define el
 * servicio, con sus nombres en inglés. Las respuestas ignoran los campos que no conocen, para que el servicio pueda sumar datos sin romper nada.
 */
final class ContratoIa {

	private ContratoIa() {
	}

	record AnalysisRequest(String jobId, Long evidenceId, Long alertId, Long incidentId, String downloadUrl,
			String mimeType, String checksumSha256) {

		@Override
		public String toString() {
			// La URL firmada es un secreto temporal: no puede terminar en un registro por un toString.
			return "AnalysisRequest[jobId=" + jobId + ", evidenceId=" + evidenceId + "]";
		}

	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record AnalysisResponse(String jobId, Long evidenceId, Long alertId, Long incidentId, String modality,
			String schemaVersion, JsonNode analysis, Provenance provenance) {
	}

	record SummaryRequest(Long incidentId, List<Alert> alerts, List<Evidence> evidences) {
	}

	record Alert(Long alertId, String reportedAt, String description, Integer affectedCount,
			Boolean reporterIsPatient) {
	}

	/** {@code analysis} ya es JSON: se escribe tal cual, sin volver a pasarlo a texto. */
	record Evidence(Long evidenceId, Long alertId, String modality, String receivedAt,
			@JsonRawValue String analysis) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record SummaryResponse(JsonNode summary, List<Long> usedEvidenceIds, List<Long> usedAlertIds,
			Provenance provenance) {
	}

	/** {@code state} ya es JSON: se escribe tal cual. */
	record EvaluationRequest(String jobId, Long incidentId, @JsonRawValue String state, List<Question> questions) {

		@Override
		public String toString() {
			// El estado lleva lo que se sabe del caso: no tiene por qué terminar en un registro por un toString.
			return "EvaluationRequest[jobId=" + jobId + ", incidentId=" + incidentId + "]";
		}

	}

	record Question(String code, String statement) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record EvaluationResponse(List<Answer> answers, Provenance provenance, Usage usage) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Answer(String code, Double probability) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Usage(Integer inputTokens, Double cost) {
	}

	/** El pedido de razonamiento tiene la misma forma que el de evaluación. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	record ReasoningResponse(List<Conclusion> conclusions, List<String> toolCalls, Provenance provenance,
			ReasoningUsage usage) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Conclusion(String code, String verdict, String rationale, List<String> sources) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record ReasoningUsage(Integer inputTokens, Integer outputTokens, Integer requests) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Provenance(String provider, String model, String promptVersion, String generatedAt, String method) {
	}

	record EmbeddingRequest(String jobId, List<String> texts) {

		@Override
		public String toString() {
			// Los textos llevan lo que se sabe de los casos: no tienen por qué terminar en un registro.
			return "EmbeddingRequest[jobId=" + jobId + ", texts=" + (texts == null ? 0 : texts.size()) + "]";
		}

	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record EmbeddingResponse(List<List<Double>> vectors, Provenance provenance) {
	}

	record ProtocolReviewRequest(String jobId, @JsonRawValue String protocol, List<ReviewFact> facts,
			List<ReviewNormExcerpt> normExcerpts) {

		@Override
		public String toString() {
			return "ProtocolReviewRequest[jobId=" + jobId + ", facts=" + (facts == null ? 0 : facts.size()) + "]";
		}

	}

	record ReviewFact(String id, String eventType, String condition, String text) {
	}

	record ReviewNormExcerpt(String id, String citation, String text) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record ProtocolReviewResponse(List<ReviewProposal> proposals, Integer discarded, Provenance provenance) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record ReviewProposal(String kind, String eventType, String condition, String proposal, String rationale,
			List<String> factIds, List<String> normIds) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record ErrorResponse(String errorCode, Boolean retryable) {
	}

}
