package com.uem.ambulancias.coordinacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import com.uem.ambulancias.coordinacion.service.EvaluadorDeCasos;
import com.uem.ambulancias.evidencias.service.FalloDelServicioIa.Desenlace;
import com.uem.ambulancias.soporte.Escenario.Alerta;
import com.uem.ambulancias.soporte.Escenario.Ciudadano;
import com.uem.ambulancias.soporte.Json;
import com.uem.ambulancias.soporte.PruebaIT;

/**
 * Cuando una condición queda en duda y el protocolo tiene una pregunta para ella, el sistema se la pide al testigo:
 * queda anotado, le llega un aviso y la app la muestra primero. Cada condición se pide una sola vez.
 */
@Tag("PB-37")
class PreguntasAlTestigoIT extends PruebaIT {

	private static final String ACCIDENTE = "ACCIDENTE_DE_TRANSITO";
	private static final String ATRAPADA = "PERSONA_ATRAPADA";
	private static final String PEDIDA = "$[?(@.tipo == 'PREGUNTA_AL_TESTIGO')]";

	/** Dos condiciones con pregunta y una sin: sirve para ver el orden y cuáles se piden. */
	private static final String PROTOCOLO_CON_DOS_PREGUNTAS = """
			{"nota": "Protocolo de prueba con dos preguntas", "tipos": [
			  {"codigo": "ACCIDENTE_DE_TRANSITO", "nombre": "Accidente de tránsito", "condiciones": [
			    {"codigo": "HAY_HERIDOS", "enunciado": "Hay personas heridas", "pregunta": "¿Hay heridos?",
			     "umbralActuar": 0.8, "umbralPreguntar": 0.4},
			    {"codigo": "HAY_FUEGO", "enunciado": "Hay fuego o humo", "pregunta": "¿Ves fuego o humo?",
			     "umbralActuar": 0.8, "umbralPreguntar": 0.4, "unidadesAdicionales": 1},
			    {"codigo": "FUENTES_CONTRADICTORIAS", "enunciado": "Las fuentes se contradicen",
			     "umbralActuar": 0.7, "umbralPreguntar": 0.4, "derivaAPersona": true}
			  ]}
			]}
			""";

	@Autowired
	private EvaluadorDeCasos evaluador;

	private void responder(Ciudadano ciudadano, Alerta alerta, String condicion, String valor) throws Exception {
		pedir(post("/alertas/" + alerta.alertaId() + "/respuestas"), ciudadano.token(),
				"{\"respuestas\": [" + Json.objeto("condicion", condicion, "valor", valor) + "]}")
				.andExpect(status().isOk());
	}

	private ResultActions preguntas(Ciudadano ciudadano, Alerta alerta) throws Exception {
		return pedir(get("/alertas/" + alerta.alertaId() + "/preguntas"), ciudadano.token());
	}

	private ResultActions bitacora(long incidenteId) throws Exception {
		return pedir(get("/incidentes/" + incidenteId + "/bitacora"), escenario.admin()).andExpect(status().isOk());
	}

	/** Los tipos de push que recibió ese teléfono de ciudadano, en orden. */
	private List<String> avisosA(String tokenPush) {
		return publicaciones.avisosAlCiudadano(tokenPush).stream().map(aviso -> aviso.datos().get("tipo")).toList();
	}

	private Ciudadano ciudadanoConAvisos(String tokenPush) {
		Ciudadano ciudadano = escenario.ciudadano();
		escenario.recibirAvisos(ciudadano, tokenPush);
		return ciudadano;
	}

	@Test
	@DisplayName("PB-37 · CP-37-01 · si una condición con pregunta queda en duda, se le pide al testigo, le llega el aviso y la app la muestra primero")
	void pideLaRespuesta() throws Exception {
		escenario.protocoloVigente();
		Ciudadano ciudadano = ciudadanoConAvisos("push-ciudadano");
		Alerta alerta = escenario.alertarCon(ciudadano, ACCIDENTE);
		// Antes de evaluar, el formulario está completo pero nada es urgente.
		preguntas(ciudadano, alerta)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].condicion").value(ATRAPADA))
				.andExpect(jsonPath("$[0].pregunta").value("¿Hay alguien atrapado?"))
				.andExpect(jsonPath("$[0].respuesta").doesNotExist())
				.andExpect(jsonPath("$[0].prioritaria").value(false));

		evaluador.barrer();

		bitacora(alerta.incidenteId())
				.andExpect(jsonPath(PEDIDA, hasSize(1)))
				.andExpect(jsonPath(PEDIDA + ".origen").value("SISTEMA"))
				// Solo la que tiene pregunta: la otra condición en duda no se le puede preguntar a nadie.
				.andExpect(jsonPath(PEDIDA + ".datos.condiciones.length()").value(1))
				.andExpect(jsonPath(PEDIDA + ".datos.condiciones[0]").value(ATRAPADA))
				.andExpect(jsonPath(PEDIDA + ".datos.evaluacionId").isNotEmpty());
		assertThat(avisosA("push-ciudadano")).containsExactly("PREGUNTA_PENDIENTE");
		assertThat(publicaciones.avisosAlCiudadano("push-ciudadano").getLast().datos())
				.containsEntry("alertaId", String.valueOf(alerta.alertaId()))
				.containsEntry("incidenteId", String.valueOf(alerta.incidenteId()));
		preguntas(ciudadano, alerta)
				.andExpect(jsonPath("$[0].condicion").value(ATRAPADA))
				.andExpect(jsonPath("$[0].prioritaria").value(true));

		responder(ciudadano, alerta, ATRAPADA, "SI");

		preguntas(ciudadano, alerta)
				.andExpect(jsonPath("$[0].respuesta").value("SI"))
				.andExpect(jsonPath("$[0].prioritaria").value(false));
	}

	@Test
	@DisplayName("PB-37 · CP-37-02 · cada condición se pide una sola vez: otra evaluación que sigue en duda no vuelve a avisar")
	void sePideUnaSolaVez() throws Exception {
		escenario.protocoloVigente();
		Ciudadano ciudadano = ciudadanoConAvisos("push-ciudadano");
		Alerta alerta = escenario.alertarCon(ciudadano, ACCIDENTE);
		evaluador.barrer();

		pedir(post("/alertas/" + alerta.alertaId() + "/detalles"), ciudadano.token(),
				Json.objeto("cantidadAfectados", 1)).andExpect(status().isOk());
		evaluador.barrer();

		pedir(get("/incidentes/" + alerta.incidenteId() + "/evaluaciones"), escenario.admin())
				// La tercera es la que queda programada para cuando al testigo se le acabe el tiempo de contestar.
				.andExpect(jsonPath("$.length()").value(3))
				.andExpect(jsonPath("$[1].veredictos[0].resultado").value("EN_DUDA"))
				.andExpect(jsonPath("$[2].estado").value("PENDIENTE"))
				.andExpect(jsonPath("$[2].pedidaPor").value("ESPERA_DE_RESPUESTA"));
		bitacora(alerta.incidenteId()).andExpect(jsonPath(PEDIDA, hasSize(1)));
		assertThat(avisosA("push-ciudadano")).containsExactly("PREGUNTA_PENDIENTE");
	}

	@Test
	@DisplayName("PB-37 · CP-37-03 · a quien ya contestó que no sabe no se le insiste")
	void noInsisteAlQueNoSabe() throws Exception {
		escenario.protocoloVigente();
		Ciudadano ciudadano = ciudadanoConAvisos("push-ciudadano");
		Alerta alerta = escenario.alertarCon(ciudadano, ACCIDENTE);
		responder(ciudadano, alerta, ATRAPADA, "NO_SE");

		evaluador.barrer();

		pedir(get("/incidentes/" + alerta.incidenteId() + "/evaluaciones"), escenario.admin())
				.andExpect(jsonPath("$[0].veredictos[0].resultado").value("EN_DUDA"))
				.andExpect(jsonPath("$[0].veredictos[0].respuestaDelTestigo").value("NO_SE"));
		bitacora(alerta.incidenteId()).andExpect(jsonPath(PEDIDA).isEmpty());
		assertThat(avisosA("push-ciudadano")).isEmpty();
		preguntas(ciudadano, alerta)
				.andExpect(jsonPath("$[0].respuesta").value("NO_SE"))
				.andExpect(jsonPath("$[0].prioritaria").value(false));
	}

	@Test
	@DisplayName("PB-37 · CP-37-04 · sin el modelo, lo que quedó sin evaluar y tiene pregunta se le pide al testigo")
	void sinModeloSePregunta() throws Exception {
		escenario.protocoloVigente();
		Ciudadano ciudadano = ciudadanoConAvisos("push-ciudadano");
		Alerta alerta = escenario.alertarCon(ciudadano, ACCIDENTE);
		ia.fallarEvaluaciones("provider_misconfigured", Desenlace.REVISION);

		evaluador.barrer();

		pedir(get("/incidentes/" + alerta.incidenteId() + "/evaluaciones"), escenario.admin())
				.andExpect(jsonPath("$[0].metodo").value("REGLAS_SIN_MODELO"))
				.andExpect(jsonPath("$[0].veredictos[0].resultado").value("SIN_EVALUAR"));
		bitacora(alerta.incidenteId()).andExpect(jsonPath(PEDIDA + ".datos.condiciones[0]").value(ATRAPADA));
		assertThat(avisosA("push-ciudadano")).containsExactly("PREGUNTA_PENDIENTE");
	}

	@Test
	@DisplayName("PB-37 · CP-37-05 · las preguntas que el sistema pidió van primero, y lo ya contestado no vuelve a ser urgente")
	void lasPedidasVanPrimero() throws Exception {
		escenario.publicarProtocolo(PROTOCOLO_CON_DOS_PREGUNTAS);
		Ciudadano ciudadano = ciudadanoConAvisos("push-ciudadano");
		Alerta alerta = escenario.alertarCon(ciudadano, ACCIDENTE);
		responder(ciudadano, alerta, "HAY_HERIDOS", "SI");
		preguntas(ciudadano, alerta)
				.andExpect(jsonPath("$[0].condicion").value("HAY_HERIDOS"))
				.andExpect(jsonPath("$[1].condicion").value("HAY_FUEGO"));

		evaluador.barrer();

		// HAY_HERIDOS ya estaba contestada: solo se pide HAY_FUEGO, que pasa adelante.
		bitacora(alerta.incidenteId())
				.andExpect(jsonPath(PEDIDA + ".datos.condiciones.length()").value(1))
				.andExpect(jsonPath(PEDIDA + ".datos.condiciones[0]").value("HAY_FUEGO"));
		preguntas(ciudadano, alerta)
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].condicion").value("HAY_FUEGO"))
				.andExpect(jsonPath("$[0].prioritaria").value(true))
				.andExpect(jsonPath("$[1].condicion").value("HAY_HERIDOS"))
				.andExpect(jsonPath("$[1].respuesta").value("SI"))
				.andExpect(jsonPath("$[1].prioritaria").value(false));
	}

	@Test
	@DisplayName("PB-37 · CP-37-06 · el aviso le llega a cada testigo, y para el que todavía no contestó la pregunta sigue siendo urgente")
	void variosTestigos() throws Exception {
		escenario.protocoloVigente();
		Ciudadano uno = ciudadanoConAvisos("push-uno");
		Ciudadano otro = ciudadanoConAvisos("push-otro");
		Alerta deUno = escenario.alertarCon(uno, ACCIDENTE);
		Alerta deOtro = escenario.alertarCon(otro, ACCIDENTE);
		evaluador.barrer();
		assertThat(avisosA("push-uno")).containsExactly("PREGUNTA_PENDIENTE");
		assertThat(avisosA("push-otro")).containsExactly("PREGUNTA_PENDIENTE");

		responder(uno, deUno, ATRAPADA, "NO");

		preguntas(uno, deUno).andExpect(jsonPath("$[0].prioritaria").value(false));
		preguntas(otro, deOtro)
				.andExpect(jsonPath("$[0].respuesta").doesNotExist())
				.andExpect(jsonPath("$[0].prioritaria").value(true));
	}

	@Test
	@DisplayName("PB-37 · CP-37-07 · el formulario de una alerta solo lo ve su emisor, y sin tipo de suceso no hay preguntas")
	void soloElEmisor() throws Exception {
		escenario.protocoloVigente();
		Ciudadano ciudadano = escenario.ciudadano();
		Alerta conTipo = escenario.alertarCon(ciudadano, ACCIDENTE);

		preguntas(escenario.ciudadano(), conTipo)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.codigo").value("ALERTA_AJENA"));
		pedir(get("/alertas/" + conTipo.alertaId() + "/preguntas"), escenario.paramedicoActivado().token())
				.andExpect(status().isForbidden());
		pedir(get("/alertas/999999/preguntas"), ciudadano.token()).andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("PB-37 · CP-37-08 · una alerta sin tipo de suceso no tiene preguntas")
	void sinTipoNoHayPreguntas() throws Exception {
		escenario.protocoloVigente();
		Ciudadano ciudadano = escenario.ciudadano();
		Alerta sinTipo = escenario.alertar(ciudadano);

		preguntas(ciudadano, sinTipo)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
	}

}
