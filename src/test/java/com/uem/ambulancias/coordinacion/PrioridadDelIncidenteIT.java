package com.uem.ambulancias.coordinacion;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import com.uem.ambulancias.coordinacion.service.EvaluadorDeCasos;
import com.uem.ambulancias.soporte.Escenario;
import com.uem.ambulancias.soporte.Escenario.Alerta;
import com.uem.ambulancias.soporte.Escenario.Ciudadano;
import com.uem.ambulancias.soporte.Escenario.Paramedico;
import com.uem.ambulancias.soporte.Json;
import com.uem.ambulancias.soporte.PruebaIT;

/**
 * Las condiciones que se cumplen también fijan la prioridad del incidente, con los códigos del triaje telefónico de
 * la norma: roja, amarilla o verde. La prioridad solo sube, y cuando las unidades no alcanzan se atiende primero el
 * caso más urgente. Quitarle una unidad a un caso menor lo decide una persona: el sistema solo se lo muestra.
 */
@Tag("PB-43")
class PrioridadDelIncidenteIT extends PruebaIT {

	private static final String ACCIDENTE = "ACCIDENTE_DE_TRANSITO";
	private static final String NO_RESPIRA = "NO_RESPIRA";
	private static final String CONSCIENTE = "HERIDO_CONSCIENTE";

	private static final String PLAN = "$[?(@.tipo == 'PLAN_DE_RESPUESTA_ACTUALIZADO')]";
	private static final String SIN_UNIDAD = "$[?(@.motivo == 'SIN_UNIDAD')]";

	private static final String PROTOCOLO = """
			{"nota": "Protocolo de prueba con prioridades", "tipos": [
			  {"codigo": "ACCIDENTE_DE_TRANSITO", "nombre": "Accidente de tránsito", "condiciones": [
			    {"codigo": "NO_RESPIRA", "enunciado": "Hay una persona que no respira",
			     "pregunta": "¿Hay alguien que no respira?", "umbralActuar": 0.8, "umbralPreguntar": 0.4,
			     "prioridad": "ROJA"},
			    {"codigo": "HERIDO_CONSCIENTE", "enunciado": "Hay un herido que responde",
			     "pregunta": "¿El herido responde?", "umbralActuar": 0.8, "umbralPreguntar": 0.4,
			     "prioridad": "AMARILLA"}
			  ]}
			]}
			""";

	@Autowired
	private EvaluadorDeCasos evaluador;

	/** Contesta las dos preguntas del accidente de una vez. */
	private void responder(Ciudadano ciudadano, Alerta alerta, String noRespira, String consciente) throws Exception {
		pedir(post("/alertas/" + alerta.alertaId() + "/respuestas"), ciudadano.token(),
				"{\"respuestas\": [" + Json.objeto("condicion", NO_RESPIRA, "valor", noRespira) + ","
						+ Json.objeto("condicion", CONSCIENTE, "valor", consciente) + "]}")
				.andExpect(status().isOk());
	}

	/** Un accidente a esa latitud: lejos de los demás, para que no se agrupe con otro incidente. */
	private Alerta accidenteEn(Ciudadano ciudadano, double latitud) throws Exception {
		String respuesta = cuerpo(pedir(post("/alertas"), ciudadano.token(), Json.objeto("latitud", latitud,
				"longitud", Escenario.LONGITUD, "origenUbicacion", "GPS", "tipoSuceso", ACCIDENTE))
				.andExpect(status().isCreated()));
		return new Alerta(Json.numero(respuesta, "$.alertaId"), Json.numero(respuesta, "$.incidenteId"));
	}

	private ResultActions oferta(Paramedico paramedico) throws Exception {
		return pedir(get("/paramedicos/actual/oferta"), paramedico.token());
	}

	private long confirmar(Paramedico paramedico) throws Exception {
		return Json.numero(cuerpo(pedir(post("/paramedicos/actual/oferta/confirmacion"), paramedico.token())
				.andExpect(status().isCreated())), "$.id");
	}

	private ResultActions incidente(long id) throws Exception {
		return pedir(get("/incidentes/" + id), escenario.admin()).andExpect(status().isOk());
	}

	private ResultActions bitacora(long incidenteId) throws Exception {
		return pedir(get("/incidentes/" + incidenteId + "/bitacora"), escenario.admin()).andExpect(status().isOk());
	}

	@Test
	@DisplayName("PB-43 · CP-43-01 · el protocolo guarda la prioridad que fija cada condición")
	void elProtocoloGuardaLaPrioridad() throws Exception {
		escenario.publicarProtocolo(PROTOCOLO);

		pedir(get("/protocolos/vigente"), escenario.admin())
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tipos[0].condiciones[0].codigo").value(NO_RESPIRA))
				.andExpect(jsonPath("$.tipos[0].condiciones[0].prioridad").value("ROJA"))
				.andExpect(jsonPath("$.tipos[0].condiciones[1].prioridad").value("AMARILLA"));
	}

	@Test
	@DisplayName("PB-43 · CP-43-02 · una condición sin prioridad deja al incidente sin ella")
	void sinPrioridad() throws Exception {
		escenario.modoDeDespacho("ASIGNACION");
		escenario.protocoloVigente();
		Paramedico cerca = escenario.unidadParaTraslados("II", Escenario.CIEN_METROS);
		Ciudadano ciudadano = escenario.ciudadano();
		Alerta alerta = escenario.alertarCon(ciudadano, ACCIDENTE);
		pedir(post("/alertas/" + alerta.alertaId() + "/respuestas"), ciudadano.token(),
				"{\"respuestas\": [" + Json.objeto("condicion", "PERSONA_ATRAPADA", "valor", "SI") + "]}")
				.andExpect(status().isOk());
		evaluador.barrer();

		incidente(alerta.incidenteId())
				.andExpect(jsonPath("$.unidadesRequeridas").value(2))
				.andExpect(jsonPath("$.prioridad").doesNotExist());
		oferta(cerca)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.prioridad").doesNotExist());
		bitacora(alerta.incidenteId())
				.andExpect(jsonPath(PLAN, hasSize(1)))
				.andExpect(jsonPath(PLAN + ".datos.prioridad").isEmpty());
	}

	@Test
	@DisplayName("PB-43 · CP-43-03 · si se cumple una condición con prioridad, el incidente la toma y la tripulación la ve en su asignación")
	void laCondicionCumplidaFijaLaPrioridad() throws Exception {
		escenario.modoDeDespacho("ASIGNACION");
		escenario.publicarProtocolo(PROTOCOLO);
		Paramedico cerca = escenario.unidadParaTraslados("II", Escenario.CIEN_METROS);
		Ciudadano ciudadano = escenario.ciudadano();
		Alerta alerta = escenario.alertarCon(ciudadano, ACCIDENTE);
		incidente(alerta.incidenteId()).andExpect(jsonPath("$.prioridad").doesNotExist());

		responder(ciudadano, alerta, "NO", "SI");
		evaluador.barrer();

		incidente(alerta.incidenteId())
				.andExpect(jsonPath("$.prioridad").value("AMARILLA"))
				// La prioridad no pide más unidades: sigue haciendo falta una.
				.andExpect(jsonPath("$.unidadesRequeridas").value(1));
		oferta(cerca)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.prioridad").value("AMARILLA"));
		bitacora(alerta.incidenteId())
				.andExpect(jsonPath(PLAN, hasSize(1)))
				.andExpect(jsonPath(PLAN + ".origen").value("SISTEMA"))
				.andExpect(jsonPath(PLAN + ".datos.prioridad").value("AMARILLA"))
				.andExpect(jsonPath(PLAN + ".datos.prioridadAntes").isEmpty())
				.andExpect(jsonPath(PLAN + ".datos.unidades").value(1))
				.andExpect(jsonPath(PLAN + ".datos.porCondiciones[0]").value(CONSCIENTE));
	}

	@Test
	@DisplayName("PB-43 · CP-43-04 · entre varias condiciones cumplidas vale la prioridad más urgente")
	void valeLaMasUrgente() throws Exception {
		escenario.publicarProtocolo(PROTOCOLO);
		Ciudadano ciudadano = escenario.ciudadano();
		Alerta alerta = escenario.alertarCon(ciudadano, ACCIDENTE);

		responder(ciudadano, alerta, "SI", "SI");
		evaluador.barrer();

		incidente(alerta.incidenteId()).andExpect(jsonPath("$.prioridad").value("ROJA"));
		bitacora(alerta.incidenteId())
				.andExpect(jsonPath(PLAN, hasSize(1)))
				.andExpect(jsonPath(PLAN + ".datos.prioridad").value("ROJA"));
	}

	@Test
	@DisplayName("PB-43 · CP-43-05 · con una sola unidad y dos incidentes esperando, al liberarse va al más urgente aunque haya llegado después")
	void primeroElMasUrgente() throws Exception {
		escenario.modoDeDespacho("ASIGNACION");
		escenario.publicarProtocolo(PROTOCOLO);
		long centro = escenario.centroDeSalud("Hospital Japonés", true);
		Paramedico unica = escenario.unidadParaTraslados("II", Escenario.CIEN_METROS);
		Alerta enCurso = escenario.alertar(escenario.ciudadano());
		long atencion = confirmar(unica);
		// Con la única unidad ocupada, dos incidentes se quedan esperando: primero llega uno sin prioridad...
		Alerta viejo = escenario.alertar(escenario.ciudadano(), Escenario.LATITUD - 0.01, Escenario.LONGITUD);
		// ...y después uno en el que alguien no respira.
		Ciudadano testigo = escenario.ciudadano();
		Alerta rojo = accidenteEn(testigo, Escenario.LATITUD + 0.01);
		responder(testigo, rojo, "SI", "NO");
		evaluador.barrer();
		incidente(rojo.incidenteId()).andExpect(jsonPath("$.prioridad").value("ROJA"));

		// A la persona le llega primero el rojo, con las unidades que están en un caso menos urgente.
		pedir(get("/derivaciones"), escenario.admin())
				.andExpect(status().isOk())
				.andExpect(jsonPath(SIN_UNIDAD + ".incidenteId")
						.value(contains((int) rojo.incidenteId(), (int) viejo.incidenteId())))
				.andExpect(jsonPath("$[0].prioridad").value("ROJA"))
				.andExpect(jsonPath("$[0].detalle.prioridad").value("ROJA"))
				.andExpect(jsonPath("$[0].detalle.unidadesEnCasosMenores", hasSize(1)))
				.andExpect(jsonPath("$[0].detalle.unidadesEnCasosMenores[0].incidenteId")
						.value(enCurso.incidenteId()))
				.andExpect(jsonPath("$[0].detalle.unidadesEnCasosMenores[0].placa").isNotEmpty())
				.andExpect(jsonPath("$[1].prioridad").doesNotExist())
				.andExpect(jsonPath("$[1].detalle.unidadesEnCasosMenores").doesNotExist());
		// El sistema no le quitó la unidad al caso que ya atendía.
		oferta(unica).andExpect(status().isNoContent());

		escenario.hastaElHospital(unica, atencion);
		pedir(post("/atenciones/" + atencion + "/entrega"), unica.token(), Json.objeto("latitud", Escenario.LATITUD,
				"longitud", Escenario.LONGITUD, "centroSaludId", centro)).andExpect(status().isOk());
		escenario.liberar(unica, atencion);

		oferta(unica)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.incidenteId").value(rojo.incidenteId()))
				.andExpect(jsonPath("$.prioridad").value("ROJA"));
	}

}
