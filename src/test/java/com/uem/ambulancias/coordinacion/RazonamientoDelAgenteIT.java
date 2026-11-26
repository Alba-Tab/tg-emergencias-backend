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
	private static final String NO_RESPIRA = "NO_RESPIRA";

	/** Un accidente con una condición roja y otra que no fija prioridad, las dos con pregunta. */
	private static final String PROTOCOLO_CON_ROJA = """
			{"nota": "Protocolo de prueba con una condición roja", "tipos": [
			  {"codigo": "ACCIDENTE_DE_TRANSITO", "nombre": "Accidente de tránsito", "condiciones": [
			    {"codigo": "NO_RESPIRA", "enunciado": "Hay una persona que no respira",
			     "pregunta": "¿Hay alguien que no respira?", "umbralActuar": 0.8, "umbralPreguntar": 0.4,
			     "prioridad": "ROJA"},
			    {"codigo": "PERSONA_ATRAPADA", "enunciado": "Hay una persona atrapada",
			     "pregunta": "¿Hay alguien atrapado?", "umbralActuar": 0.8, "umbralPreguntar": 0.4,
			     "unidadesAdicionales": 1, "tipoMinimo": "II"}
			  ]}
			]}
			""";

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

	private void responder(Ciudadano ciudadano, Alerta alerta, String condicion, String valor) throws Exception {
		pedir(post("/alertas/" + alerta.alertaId() + "/respuestas"), ciudadano.token(),
				"{\"respuestas\": [" + Json.objeto("condicion", condicion, "valor", valor) + "]}")
				.andExpect(status().isOk());
	}

	/** Al testigo se le acabó el tiempo de contestar, y la evaluación que lo esperaba ya se puede tomar. */
	private void pasarElTiempoDelTestigo() {
		jdbc.update("update evento_del_caso set ocurrido_en = ocurrido_en - interval '10 minutes' "
				+ "where tipo = 'PREGUNTA_AL_TESTIGO'");
		jdbc.update("update evaluacion_del_caso set proximo_intento = now() - interval '1 hour' "
				+ "where estado = 'PENDIENTE'");
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
	@DisplayName("PB-40 · CP-40-06 · el agente razona mientras se espera al testigo; si no sabe, la condición va a una persona recién al vencer el plazo, sin pedirle otro razonamiento")
	void razonaMientrasEsperaAlTestigo() throws Exception {
		escenario.protocoloVigente();
		ia.encenderRazonador();
		Ciudadano ciudadano = escenario.ciudadano();
		Alerta alerta = escenario.alertarCon(ciudadano, ACCIDENTE);
		otraEvidencia(ciudadano, alerta);

		evaluador.barrer();

		// Al testigo se le acaba de preguntar y ya se le pide el razonamiento al agente, sin esperar su respuesta.
		razonamientos(alerta.incidenteId())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].condiciones[0].codigo").value(ATRAPADA))
				.andExpect(jsonPath("$[0].condiciones[0].esperaAlTestigoHasta").isNotEmpty())
				.andExpect(jsonPath("$[0].condiciones[1].codigo").value(CONTRADICCION))
				.andExpect(jsonPath("$[0].condiciones[1].esperaAlTestigoHasta").doesNotExist());
		derivaciones(alerta.incidenteId()).andExpect(jsonPath("$.length()").value(0));

		razonador.barrer();

		// El agente no sabe ninguna: la que no tiene pregunta va ya a una persona; la otra espera al testigo.
		derivaciones(alerta.incidenteId())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].condicion").value(CONTRADICCION));

		pasarElTiempoDelTestigo();
		evaluador.barrer();

		derivaciones(alerta.incidenteId())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[1].condicion").value(ATRAPADA))
				.andExpect(jsonPath("$[1].detalle.porQue").value("EL_TESTIGO_NO_CONTESTO"))
				.andExpect(jsonPath("$[1].detalle.razonamiento.justificacion").value("La información no alcanza."))
				.andExpect(jsonPath("$[1].detalle.razonamiento.razonamientoId").isNumber());
		assertThat(ia.razonamientosPedidos()).hasSize(1);
		razonamientos(alerta.incidenteId()).andExpect(jsonPath("$.length()").value(1));
	}

	@Test
	@DisplayName("PB-40 · CP-40-07 · lo que el agente resolvió mientras se esperaba al testigo se aplica, y una respuesta del testigo que llega después lo reemplaza")
	void laRespuestaDelTestigoReemplazaAlAgente() throws Exception {
		ia.concluir(ATRAPADA, ConclusionDelAgente.NO_CUMPLE, "La foto muestra los dos autos vacíos.", "evidencia");
		escenario.protocoloVigente();
		ia.encenderRazonador();
		Ciudadano ciudadano = escenario.ciudadano();
		Alerta alerta = escenario.alertarCon(ciudadano, ACCIDENTE);
		otraEvidencia(ciudadano, alerta);
		evaluador.barrer();
		razonador.barrer();
		bitacora(alerta.incidenteId())
				.andExpect(jsonPath(DECIDIDA + ".datos.condicion").value(ATRAPADA))
				.andExpect(jsonPath(DECIDIDA + ".datos.cumple").value(false))
				.andExpect(jsonPath(DECIDIDA + ".datos.por").value("AGENTE"));

		responder(ciudadano, alerta, ATRAPADA, "SI");
		evaluador.barrer();

		pedir(get("/incidentes/" + alerta.incidenteId() + "/evaluaciones"), escenario.admin())
				.andExpect(jsonPath("$[-1:].veredictos[0].resultado").value("CUMPLIDA"))
				.andExpect(jsonPath("$[-1:].veredictos[0].fuente").value("TESTIGO"));
		pedir(get("/incidentes/" + alerta.incidenteId()), escenario.admin())
				.andExpect(jsonPath("$.unidadesRequeridas").value(2));
		assertThat(ia.razonamientosPedidos()).hasSize(1);
	}

	@Test
	@DisplayName("PB-40 · CP-40-08 · sin evidencia, sin casos del mismo tipo y con una sola alerta, el agente no entra: lo que no se resuelve va a una persona, tras el plazo del testigo si tiene pregunta")
	void sinNadaQueRazonar() throws Exception {
		escenario.protocoloVigente();
		ia.encenderRazonador();
		Alerta alerta = escenario.alertarCon(escenario.ciudadano(), ACCIDENTE);

		evaluador.barrer();

		razonamientos(alerta.incidenteId()).andExpect(jsonPath("$.length()").value(0));
		derivaciones(alerta.incidenteId())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].condicion").value(CONTRADICCION))
				.andExpect(jsonPath("$[0].detalle.porQue").value("SIN_PREGUNTA_PARA_EL_TESTIGO"))
				.andExpect(jsonPath("$[0].detalle.razonamiento").doesNotExist());

		pasarElTiempoDelTestigo();
		evaluador.barrer();

		derivaciones(alerta.incidenteId())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[1].condicion").value(ATRAPADA))
				.andExpect(jsonPath("$[1].detalle.porQue").value("EL_TESTIGO_NO_CONTESTO"));
		assertThat(ia.razonamientosPedidos()).isEmpty();
	}

	@Test
	@DisplayName("PB-40 · CP-40-09 · con casos cerrados del mismo tipo de suceso el agente sí tiene con qué razonar")
	void conCasosDelMismoTipo() throws Exception {
		escenario.protocoloVigente();
		ia.encenderRazonador();
		Alerta anterior = escenario.alertarCon(escenario.ciudadano(), ACCIDENTE);
		pedir(post("/incidentes/" + anterior.incidenteId() + "/cierre"), escenario.admin(),
				Json.objeto("motivo", "ATENDIDO_EXTERNAMENTE")).andExpect(status().isNoContent());
		Alerta actual = escenario.alertarCon(escenario.ciudadano(), ACCIDENTE);

		evaluador.barrer();

		razonamientos(actual.incidenteId())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].condiciones[0].codigo").value(ATRAPADA))
				.andExpect(jsonPath("$[0].condiciones[1].codigo").value(CONTRADICCION));
		derivaciones(actual.incidenteId()).andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	@DisplayName("PB-40 · CP-40-10 · un «no sé» sin evidencia ya no queda en duda sin nada: con dos personas que alertaron va al agente, y si tampoco lo resuelve, a una persona")
	void elNoSeSinEvidenciaVaAlAgente() throws Exception {
		escenario.protocoloVigente();
		ia.encenderRazonador();
		Ciudadano uno = escenario.ciudadano();
		Ciudadano otro = escenario.ciudadano();
		Alerta deUno = escenario.alertarCon(uno, ACCIDENTE);
		Alerta deOtro = escenario.alertarCon(otro, ACCIDENTE);
		responder(uno, deUno, ATRAPADA, "NO_SE");
		responder(otro, deOtro, ATRAPADA, "NO_SE");

		evaluador.barrer();

		razonamientos(deUno.incidenteId())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].condiciones[0].codigo").value(ATRAPADA))
				.andExpect(jsonPath("$[0].condiciones[0].paquete.porQue").value("EL_TESTIGO_NO_SABE"));

		razonador.barrer();

		derivaciones(deUno.incidenteId())
				.andExpect(jsonPath("$[?(@.condicion == 'PERSONA_ATRAPADA')].detalle.porQue")
						.value("EL_TESTIGO_NO_SABE"))
				.andExpect(jsonPath("$[?(@.condicion == 'PERSONA_ATRAPADA')].detalle.razonamiento.justificacion")
						.value("La información no alcanza."));
	}

	@Test
	@DisplayName("PB-40 · CP-40-11 · en una condición roja el agente solo confirma: su «no se cumple» no se aplica y la decide una persona con el porqué del agente; en una que no es roja, se aplica")
	void enUnaRojaElAgenteSoloConfirma() throws Exception {
		escenario.publicarProtocolo(PROTOCOLO_CON_ROJA);
		ia.encenderRazonador();
		ia.concluir(NO_RESPIRA, ConclusionDelAgente.NO_CUMPLE, "En la foto la persona está sentada y habla.",
				"evidencia: resumen de la foto");
		ia.concluir(ATRAPADA, ConclusionDelAgente.NO_CUMPLE, "La foto muestra los dos autos vacíos.", "evidencia");
		Ciudadano ciudadano = escenario.ciudadano();
		Alerta alerta = escenario.alertarCon(ciudadano, ACCIDENTE);
		pedir(post("/alertas/" + alerta.alertaId() + "/respuestas"), ciudadano.token(), "{\"respuestas\": ["
				+ Json.objeto("condicion", NO_RESPIRA, "valor", "NO_SE") + ","
				+ Json.objeto("condicion", ATRAPADA, "valor", "NO_SE") + "]}").andExpect(status().isOk());
		otraEvidencia(ciudadano, alerta);
		evaluador.barrer();

		razonador.barrer();

		derivaciones(alerta.incidenteId())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].motivo").value("CONDICION_SIN_RESOLVER"))
				.andExpect(jsonPath("$[0].condicion").value(NO_RESPIRA))
				.andExpect(jsonPath("$[0].detalle.porQue").value("EL_AGENTE_NO_LA_CONFIRMA"))
				.andExpect(jsonPath("$[0].detalle.condicion.prioridad").value("ROJA"))
				.andExpect(jsonPath("$[0].detalle.razonamiento.veredicto").value("NO_CUMPLE"))
				.andExpect(jsonPath("$[0].detalle.razonamiento.justificacion")
						.value("En la foto la persona está sentada y habla."));
		bitacora(alerta.incidenteId())
				// El razonamiento queda entero en la bitácora, con las dos conclusiones.
				.andExpect(jsonPath(RAZONADO + ".datos.conclusiones[0].condicion").value(NO_RESPIRA))
				.andExpect(jsonPath(RAZONADO + ".datos.conclusiones[0].veredicto").value("NO_CUMPLE"))
				// Pero solo queda decidida la que no es roja.
				.andExpect(jsonPath(DECIDIDA + ".datos.condicion").value(ATRAPADA))
				.andExpect(jsonPath(DECIDIDA + ".datos.cumple").value(false));

		// La evaluación siguiente no le vuelve a pedir lo mismo al agente ni deriva otra vez.
		evaluador.barrer();

		assertThat(ia.razonamientosPedidos()).hasSize(1);
		derivaciones(alerta.incidenteId()).andExpect(jsonPath("$.length()").value(1));
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
