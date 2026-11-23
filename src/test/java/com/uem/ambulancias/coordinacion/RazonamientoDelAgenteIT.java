package com.uem.ambulancias.coordinacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import com.uem.ambulancias.coordinacion.domain.ConclusionDelAgente;
import com.uem.ambulancias.coordinacion.service.EvaluadorDeCasos;
import com.uem.ambulancias.coordinacion.service.PedidoDeEvaluacion;
import com.uem.ambulancias.coordinacion.service.RazonadorDeCasos;
import com.uem.ambulancias.evidencias.service.AnalizadorDeEvidencias;
import com.uem.ambulancias.evidencias.service.FalloDelServicioIa.Desenlace;
import com.uem.ambulancias.soporte.Escenario.Alerta;
import com.uem.ambulancias.soporte.Escenario.Ciudadano;
import com.uem.ambulancias.soporte.Json;
import com.uem.ambulancias.soporte.PruebaIT;

/**
 * Con el agente encendido, una condición en duda pasa por él antes de ir a una persona. Lo que resuelve con su porqué
 * queda decidido hasta que llegue un dato nuevo; lo que no resuelve, o si no responde, va a una persona con lo que
 * el agente alcanzó a decir. Los workers son los reales; quien responde por el agente es el servicio falso.
 */
@Tag("PB-40")
class RazonamientoDelAgenteIT extends PruebaIT {

	private static final String ACCIDENTE = "ACCIDENTE_DE_TRANSITO";
	private static final String ATRAPADA = "PERSONA_ATRAPADA";
	private static final String CONTRADICCION = "FUENTES_CONTRADICTORIAS";

	private static final String DECIDIDA = "$[?(@.tipo == 'CONDICION_DECIDIDA')]";
	private static final String RAZONADO = "$[?(@.tipo == 'RAZONAMIENTO_DEL_AGENTE')]";

	private static final String ANALISIS_UTIL = """
			{"summary": "Dos autos chocados; un conductor sigue adentro.", "eventType": "traffic_accident",
			 "hazards": ["traffic"], "severity": {"level": "high", "basis": ["Puerta aplastada."]}, "usable": true}
			""";

	@Autowired
	private EvaluadorDeCasos evaluador;

	@Autowired
	private RazonadorDeCasos razonador;

	@Autowired
	private AnalizadorDeEvidencias analizador;

	private ResultActions razonamientos(long incidenteId) throws Exception {
		return pedir(get("/incidentes/" + incidenteId + "/razonamientos"), escenario.admin())
				.andExpect(status().isOk());
	}

	private ResultActions derivaciones(long incidenteId) throws Exception {
		return pedir(get("/incidentes/" + incidenteId + "/derivaciones"), escenario.admin())
				.andExpect(status().isOk());
	}

	private ResultActions bitacora(long incidenteId) throws Exception {
		return pedir(get("/incidentes/" + incidenteId + "/bitacora"), escenario.admin()).andExpect(status().isOk());
	}

	/**
	 * Un accidente en el que el testigo no sabe si hay alguien atrapado y hay una foto analizada: las dos condiciones
	 * quedan en duda y sin a quién preguntarle. Con el agente encendido, la evaluación le pide un razonamiento.
	 */
	private Alerta casoEnDuda(Ciudadano ciudadano) throws Exception {
		escenario.protocoloVigente();
		ia.encenderRazonador();
		Alerta alerta = escenario.alertarCon(ciudadano, ACCIDENTE);
		pedir(post("/alertas/" + alerta.alertaId() + "/respuestas"), ciudadano.token(),
				"{\"respuestas\": [" + Json.objeto("condicion", ATRAPADA, "valor", "NO_SE") + "]}")
				.andExpect(status().isOk());
		otraEvidencia(ciudadano, alerta);
		evaluador.barrer();
		return alerta;
	}

	private void otraEvidencia(Ciudadano ciudadano, Alerta alerta) {
		ia.analizarCon(ANALISIS_UTIL);
		escenario.evidenciaSubida(ciudadano, alerta.alertaId(), "image/jpeg");
		analizador.barrer();
	}

	@Test
	@DisplayName("PB-40 · CP-40-01 · lo que el agente resuelve queda decidido con su porqué y sale la unidad; lo que no resuelve va a una persona con lo que el agente dijo")
	void elAgenteResuelveUnaYDerivaLaOtra() throws Exception {
		ia.concluir(ATRAPADA, ConclusionDelAgente.CUMPLE, "El análisis de la foto dice que un conductor sigue adentro.",
				"evidencia: resumen de la foto");
		Alerta alerta = casoEnDuda(escenario.ciudadano());
		// Todavía no se le pasó nada a nadie: primero razona el agente.
		derivaciones(alerta.incidenteId()).andExpect(jsonPath("$.length()").value(0));
		razonamientos(alerta.incidenteId())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].estado").value("PENDIENTE"))
				.andExpect(jsonPath("$[0].condiciones[0].codigo").value(ATRAPADA))
				.andExpect(jsonPath("$[0].condiciones[1].codigo").value(CONTRADICCION));

		razonador.barrer();

		assertThat(ia.razonamientosPedidos().getFirst().preguntas()).extracting(PedidoDeEvaluacion.Pregunta::codigo)
				.containsExactly(ATRAPADA, CONTRADICCION);
		assertThat(ia.razonamientosPedidos().getFirst().estadoJson()).contains("Dos autos chocados");
		razonamientos(alerta.incidenteId())
				.andExpect(jsonPath("$[0].estado").value("HECHA"))
				.andExpect(jsonPath("$[0].modelo").value("razonador-de-prueba"))
				.andExpect(jsonPath("$[0].versionDelPrompt").value("reasoning-v1"))
				.andExpect(jsonPath("$[0].pedidosAlModelo").value(3))
				.andExpect(jsonPath("$[0].herramientas[0]").value("ver_caso"))
				.andExpect(jsonPath("$[0].conclusiones[0].veredicto").value("CUMPLE"))
				.andExpect(jsonPath("$[0].conclusiones[0].fuentes[0]").value("evidencia: resumen de la foto"))
				.andExpect(jsonPath("$[0].conclusiones[1].veredicto").value("NO_SE_SABE"));
		pedir(get("/incidentes/" + alerta.incidenteId()), escenario.admin())
				.andExpect(jsonPath("$.unidadesRequeridas").value(2))
				.andExpect(jsonPath("$.tipoMinimoRequerido").value("II"));
		derivaciones(alerta.incidenteId())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].motivo").value("CONDICION_SIN_RESOLVER"))
				.andExpect(jsonPath("$[0].condicion").value(CONTRADICCION))
				.andExpect(jsonPath("$[0].detalle.porQue").value("SIN_PREGUNTA_PARA_EL_TESTIGO"))
				.andExpect(jsonPath("$[0].detalle.razonamiento.justificacion").value("La información no alcanza."))
				.andExpect(jsonPath("$[0].detalle.razonamiento.herramientas[0]").value("ver_caso"));
		bitacora(alerta.incidenteId())
				.andExpect(jsonPath(RAZONADO + ".origen").value("AGENTE"))
				.andExpect(jsonPath(RAZONADO + ".datos.conclusiones[0].veredicto").value("CUMPLE"))
				.andExpect(jsonPath(DECIDIDA + ".origen").value("AGENTE"))
				.andExpect(jsonPath(DECIDIDA + ".datos.condicion").value(ATRAPADA))
				.andExpect(jsonPath(DECIDIDA + ".datos.cumple").value(true))
				.andExpect(jsonPath(DECIDIDA + ".datos.por").value("AGENTE"))
				.andExpect(jsonPath(DECIDIDA + ".datos.justificacion")
						.value("El análisis de la foto dice que un conductor sigue adentro."));

		// La evaluación siguiente toma lo que resolvió el agente y no vuelve a pedir otro razonamiento.
		evaluador.barrer();

		pedir(get("/incidentes/" + alerta.incidenteId() + "/evaluaciones"), escenario.admin())
				.andExpect(jsonPath("$[-1:].veredictos[0].resultado").value("CUMPLIDA"))
				.andExpect(jsonPath("$[-1:].veredictos[0].fuente").value("AGENTE"));
		razonamientos(alerta.incidenteId()).andExpect(jsonPath("$.length()").value(1));
	}

	@Test
	@DisplayName("PB-40 · CP-40-02 · lo que decidió el agente vale hasta que llega un dato nuevo: con otra evidencia, la condición se vuelve a evaluar desde el principio")
	void unDatoNuevoReabreLoQueDecidioElAgente() throws Exception {
		ia.concluir(ATRAPADA, ConclusionDelAgente.NO_CUMPLE, "La foto muestra los dos autos vacíos.", "evidencia");
		Ciudadano ciudadano = escenario.ciudadano();
		Alerta alerta = casoEnDuda(ciudadano);
		razonador.barrer();
		evaluador.barrer();
		pedir(get("/incidentes/" + alerta.incidenteId() + "/evaluaciones"), escenario.admin())
				.andExpect(jsonPath("$[-1:].veredictos[0].resultado").value("NO_CUMPLIDA"))
				.andExpect(jsonPath("$[-1:].veredictos[0].fuente").value("AGENTE"));
		pedir(get("/incidentes/" + alerta.incidenteId()), escenario.admin())
				.andExpect(jsonPath("$.unidadesRequeridas").value(1));

		otraEvidencia(ciudadano, alerta);
		evaluador.barrer();

		// Con el dato nuevo, lo del agente ya no cuenta: la condición vuelve al modelo y, si sigue en duda, al agente.
		assertThat(ia.evaluacionesPedidas().getLast().preguntas()).extracting(PedidoDeEvaluacion.Pregunta::codigo)
				.contains(ATRAPADA);
		razonamientos(alerta.incidenteId())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[1].estado").value("PENDIENTE"))
				.andExpect(jsonPath("$[1].condiciones.length()").value(1))
				.andExpect(jsonPath("$[1].condiciones[0].codigo").value(ATRAPADA));
	}

	@Test
	@DisplayName("PB-40 · CP-40-03 · si el agente no responde se reintenta una vez, y después las condiciones van a una persona")
	void elAgenteNoResponde() throws Exception {
		ia.fallarRazonamientos("tools_unavailable", Desenlace.REINTENTAR);
		Alerta alerta = casoEnDuda(escenario.ciudadano());

		razonador.barrer();

		razonamientos(alerta.incidenteId())
				.andExpect(jsonPath("$[0].estado").value("PENDIENTE"))
				.andExpect(jsonPath("$[0].intentos").value(1))
				.andExpect(jsonPath("$[0].error").value("tools_unavailable"));
		derivaciones(alerta.incidenteId()).andExpect(jsonPath("$.length()").value(0));

		jdbc.update("update razonamiento_del_caso set proximo_intento = now() - interval '1 hour'");
		razonador.barrer();

		assertThat(ia.razonamientosPedidos()).hasSize(2);
		razonamientos(alerta.incidenteId())
				.andExpect(jsonPath("$[0].estado").value("FALLIDA"))
				.andExpect(jsonPath("$[0].intentos").value(2));
		derivaciones(alerta.incidenteId())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].condicion").value(ATRAPADA))
				.andExpect(jsonPath("$[0].detalle.porQue").value("EL_TESTIGO_NO_SABE"))
				.andExpect(jsonPath("$[0].detalle.razonamiento.error").value("tools_unavailable"))
				.andExpect(jsonPath("$[1].condicion").value(CONTRADICCION));
	}

	@Test
	@DisplayName("PB-40 · CP-40-04 · mientras el agente razona no se le pide otro razonamiento, y si una persona decide antes, el agente ya no la pisa")
	void unoALaVezYLaPersonaManda() throws Exception {
		ia.concluir(ATRAPADA, ConclusionDelAgente.NO_CUMPLE, "La foto muestra los dos autos vacíos.", "evidencia");
		Ciudadano ciudadano = escenario.ciudadano();
		Alerta alerta = casoEnDuda(ciudadano);

		// Llega otro dato mientras el razonamiento espera: la evaluación no pide un segundo.
		pedir(post("/alertas/" + alerta.alertaId() + "/detalles"), ciudadano.token(),
				Json.objeto("cantidadAfectados", 2)).andExpect(status().isOk());
		evaluador.barrer();
		razonamientos(alerta.incidenteId()).andExpect(jsonPath("$.length()").value(1));

		// Una persona decide la condición antes de que el agente termine (acá, directo en la bitácora).
		jdbc.update("""
				insert into evento_del_caso (incidente_id, tipo, origen, datos, ocurrido_en, registrado_en)
				values (?, 'CONDICION_DECIDIDA', 'CENTRAL',
				        '{"condicion": "PERSONA_ATRAPADA", "cumple": true, "por": "CENTRAL"}'::jsonb, now(), now())
				""", alerta.incidenteId());
		razonador.barrer();

		// Al agente ya no se le pregunta por esa condición, y su «no se cumple» preparado no se aplica.
		assertThat(ia.razonamientosPedidos().getLast().preguntas()).extracting(PedidoDeEvaluacion.Pregunta::codigo)
				.containsExactly(CONTRADICCION);
		bitacora(alerta.incidenteId())
				.andExpect(jsonPath("$[?(@.tipo == 'CONDICION_DECIDIDA' && @.origen == 'AGENTE')]").isEmpty());
	}

	@Test
	@DisplayName("PB-40 · CP-40-05 · los razonamientos de un incidente solo los ve la central")
	void soloLaCentral() throws Exception {
		Ciudadano ciudadano = escenario.ciudadano();
		Alerta alerta = casoEnDuda(ciudadano);
		String ruta = "/incidentes/" + alerta.incidenteId() + "/razonamientos";

		pedir(get(ruta), null).andExpect(status().isUnauthorized());
		pedir(get(ruta), ciudadano.token()).andExpect(status().isForbidden());
		pedir(get("/incidentes/999999/razonamientos"), escenario.admin()).andExpect(status().isNotFound());
	}

}
