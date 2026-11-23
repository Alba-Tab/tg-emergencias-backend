package com.uem.ambulancias.integracion.ia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatusCode;

import com.uem.ambulancias.coordinacion.service.PedidoDeEvaluacion;
import com.uem.ambulancias.evidencias.service.FalloDelServicioIa;
import com.uem.ambulancias.evidencias.service.FalloDelServicioIa.Desenlace;
import com.uem.ambulancias.integracion.ia.ContratoIa.Answer;
import com.uem.ambulancias.integracion.ia.ContratoIa.EvaluationResponse;

/** La tabla con la que el backend decide qué hacer con cada error del servicio de IA. */
@Tag("PB-19")
class ClienteIaHttpTest {

	@ParameterizedTest(name = "{0} → {1}")
	@CsvSource({
			"download_forbidden, FIRMAR_DE_NUEVO",
			"unauthorized, REVISION",
			"provider_misconfigured, REVISION",
			"unreadable_media, DEFINITIVO",
			"unsupported_media_type, DEFINITIVO",
			"checksum_mismatch, DEFINITIVO",
			"nothing_to_summarize, DEFINITIVO",
			"state_too_large, DEFINITIVO",
			"provider_unavailable, REINTENTAR",
			"invalid_model_output, REINTENTAR" })
	@DisplayName("PB-19 · cada error conocido del servicio tiene su desenlace: reintentar, firmar otra vez, dejarlo o revisarlo")
	void erroresConocidos(String codigo, Desenlace desenlace) {
		assertThat(ClienteIaHttp.desenlace(HttpStatusCode.valueOf(422), codigo, null)).isEqualTo(desenlace);
	}

	@Test
	@DisplayName("PB-19 · un error desconocido se resuelve con lo que diga el servicio sobre reintentarlo")
	void desconocidoConReintentable() {
		assertThat(ClienteIaHttp.desenlace(HttpStatusCode.valueOf(400), "algo_nuevo", true))
				.isEqualTo(Desenlace.REINTENTAR);
		assertThat(ClienteIaHttp.desenlace(HttpStatusCode.valueOf(503), "algo_nuevo", false))
				.isEqualTo(Desenlace.DEFINITIVO);
	}

	@ParameterizedTest(name = "HTTP {0} → {1}")
	@CsvSource({ "401, REVISION", "403, REVISION", "404, REVISION", "500, REINTENTAR", "503, REINTENTAR",
			"429, REINTENTAR", "400, DEFINITIVO", "422, DEFINITIVO" })
	@DisplayName("PB-19 · sin código ni pista, decide el estado HTTP: configuración, pasajero o definitivo")
	void soloElEstado(int estado, Desenlace desenlace) {
		assertThat(ClienteIaHttp.desenlace(HttpStatusCode.valueOf(estado), "http_" + estado, null))
				.isEqualTo(desenlace);
	}

	private static PedidoDeEvaluacion pedido(String... condiciones) {
		return new PedidoDeEvaluacion(UUID.randomUUID(), 7L, "{}", Arrays.stream(condiciones)
				.map(condicion -> new PedidoDeEvaluacion.Pregunta(condicion, "Enunciado de " + condicion))
				.toList());
	}

	@Test
	@Tag("PB-35")
	@DisplayName("PB-35 · la respuesta del modelo de decisión sirve si trae una probabilidad de 0 a 1 por cada condición consultada")
	void probabilidadesCompletas() {
		EvaluationResponse respuesta = new EvaluationResponse(
				List.of(new Answer("ATRAPADO", 0.2), new Answer("FUEGO", 1.0), new Answer("OTRA", 0.5)), null, null);

		assertThat(ClienteIaHttp.probabilidades(pedido("ATRAPADO", "FUEGO"), respuesta))
				.containsOnly(entry("ATRAPADO", 0.2), entry("FUEGO", 1.0));
	}

	@Test
	@Tag("PB-35")
	@DisplayName("PB-35 · si al modelo le falta una condición o da una probabilidad fuera de rango, la respuesta entera se reintenta")
	void probabilidadesIncompletas() {
		List<EvaluationResponse> invalidas = List.of(
				new EvaluationResponse(null, null, null),
				new EvaluationResponse(List.of(new Answer("ATRAPADO", 0.2)), null, null),
				new EvaluationResponse(List.of(new Answer("ATRAPADO", 0.2), new Answer("FUEGO", null)), null, null),
				new EvaluationResponse(List.of(new Answer("ATRAPADO", 0.2), new Answer("FUEGO", 1.2)), null, null),
				new EvaluationResponse(List.of(new Answer("ATRAPADO", -0.1), new Answer("FUEGO", 0.5)), null, null),
				new EvaluationResponse(List.of(new Answer("ATRAPADO", Double.NaN), new Answer("FUEGO", 0.5)), null,
						null));

		assertThat(invalidas).allSatisfy(respuesta -> assertThatThrownBy(
				() -> ClienteIaHttp.probabilidades(pedido("ATRAPADO", "FUEGO"), respuesta))
				.isInstanceOfSatisfying(FalloDelServicioIa.class, fallo -> {
					assertThat(fallo.getCodigo()).isEqualTo("respuesta_incompleta");
					assertThat(fallo.getDesenlace()).isEqualTo(Desenlace.REINTENTAR);
				}));
	}

}
