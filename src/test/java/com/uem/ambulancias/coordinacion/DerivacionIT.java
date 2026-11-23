package com.uem.ambulancias.coordinacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
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
import com.uem.ambulancias.coordinacion.service.PedidoDeEvaluacion;
import com.uem.ambulancias.coordinacion.service.VencimientoDeDerivaciones;
import com.uem.ambulancias.evidencias.service.AnalizadorDeEvidencias;
import com.uem.ambulancias.soporte.Escenario;
import com.uem.ambulancias.soporte.Escenario.Alerta;
import com.uem.ambulancias.soporte.Escenario.Ciudadano;
import com.uem.ambulancias.soporte.Escenario.Paramedico;
import com.uem.ambulancias.soporte.Json;
import com.uem.ambulancias.soporte.PruebaIT;

/**
 * Lo que el sistema no puede o no debe decidir solo se le pasa a una persona de la central, con lo que necesita para
 * decidir, sus opciones y un tiempo límite. Si nadie contesta se aplica la opción segura. El reloj es el real y cada
 * prueba lo adelanta cuando quiere.
 */
@Tag("PB-38")
class DerivacionIT extends PruebaIT {

	private static final String ACCIDENTE = "ACCIDENTE_DE_TRANSITO";
	private static final String ATRAPADA = "PERSONA_ATRAPADA";
	private static final String CONTRADICCION = "FUENTES_CONTRADICTORIAS";

	private static final String DERIVADO = "$[?(@.tipo == 'DERIVADO_A_PERSONA')]";
	private static final String DECIDIDA = "$[?(@.tipo == 'CONDICION_DECIDIDA')]";

	private static final String ANALISIS_UTIL = """
			{"summary": "Dos autos chocados; un conductor sigue adentro.", "eventType": "traffic_accident",
			 "hazards": ["traffic"], "severity": {"level": "high", "basis": ["Puerta aplastada."]}, "usable": true}
			""";

	@Autowired
	private EvaluadorDeCasos evaluador;

	@Autowired
	private VencimientoDeDerivaciones vencimiento;

	@Autowired
	private AnalizadorDeEvidencias analizador;

	private void responder(Ciudadano ciudadano, Alerta alerta, String condicion, String valor) throws Exception {
		pedir(post("/alertas/" + alerta.alertaId() + "/respuestas"), ciudadano.token(),
				"{\"respuestas\": [" + Json.objeto("condicion", condicion, "valor", valor) + "]}")
				.andExpect(status().isOk());
	}

	private ResultActions pendientes() throws Exception {
		return pedir(get("/derivaciones"), escenario.admin()).andExpect(status().isOk());
	}

	private ResultActions deIncidente(long incidenteId) throws Exception {
		return pedir(get("/incidentes/" + incidenteId + "/derivaciones"), escenario.admin())
				.andExpect(status().isOk());
	}

	private ResultActions decidir(long derivacionId, String opcion, String nota) throws Exception {
		return pedir(post("/derivaciones/" + derivacionId + "/decision"), escenario.admin(),
				Json.objeto("opcion", opcion, "nota", nota));
	}

	private ResultActions bitacora(long incidenteId) throws Exception {
		return pedir(get("/incidentes/" + incidenteId + "/bitacora"), escenario.admin()).andExpect(status().isOk());
	}

	private ResultActions evaluaciones(long incidenteId) throws Exception {
		return pedir(get("/incidentes/" + incidenteId + "/evaluaciones"), escenario.admin())
				.andExpect(status().isOk());
	}

	private ResultActions oferta(Paramedico paramedico) throws Exception {
		return pedir(get("/paramedicos/actual/oferta"), paramedico.token());
	}

	private long unicaDerivacion(long incidenteId) throws Exception {
		return Json.numero(cuerpo(deIncidente(incidenteId).andExpect(jsonPath("$.length()").value(1))), "$[0].id");
	}

	/** Al testigo se le acabó el tiempo de contestar, y la evaluación que lo esperaba ya se puede tomar. */
	private void pasarElTiempoDelTestigo() {
		jdbc.update("update evento_del_caso set ocurrido_en = ocurrido_en - interval '10 minutes' "
				+ "where tipo = 'PREGUNTA_AL_TESTIGO'");
		jdbc.update("update evaluacion_del_caso set proximo_intento = now() - interval '1 hour' "
				+ "where estado = 'PENDIENTE'");
	}

	/** Deja sin tiempo a las derivaciones abiertas y corre el barrido. */
	private void pasarElTiempoDeLaCentral() {
		jdbc.update("update derivacion set vence_en = now() - interval '10 minutes' where estado = 'ABIERTA'");
		vencimiento.barrer();
	}

	/** Deja una condición en duda derivada porque el testigo no contestó. Devuelve el id de la derivación. */
	private long derivarPorFaltaDeRespuesta(Alerta alerta) throws Exception {
		evaluador.barrer();
		deIncidente(alerta.incidenteId()).andExpect(jsonPath("$.length()").value(0));
		pasarElTiempoDelTestigo();
		evaluador.barrer();
		return unicaDerivacion(alerta.incidenteId());
	}

	@Test
	@DisplayName("PB-38 · CP-38-01 · una condición que el protocolo marca para una persona se le pasa a la central con su paquete, y la persona la toma")
	void condicionQueDeriva() throws Exception {
		escenario.protocoloVigente();
		ia.responderProbabilidad(CONTRADICCION, 0.9);
		Ciudadano ciudadano = escenario.ciudadano();
		Alerta alerta = escenario.alertarCon(ciudadano, ACCIDENTE);

		evaluador.barrer();

		String lista = cuerpo(pendientes()
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].incidenteId").value(alerta.incidenteId()))
				.andExpect(jsonPath("$[0].estado").value("ABIERTA"))
				.andExpect(jsonPath("$[0].motivo").value("CONDICION_QUE_DERIVA"))
				.andExpect(jsonPath("$[0].condicion").value(CONTRADICCION))
				.andExpect(jsonPath("$[0].necesitaAtencion").value(true))
				.andExpect(jsonPath("$[0].opciones.length()").value(1))
				.andExpect(jsonPath("$[0].opciones[0].codigo").value("ENTERADO"))
				.andExpect(jsonPath("$[0].opciones[0].texto").value("Lo tomo yo"))
				.andExpect(jsonPath("$[0].opcionSegura").value("ENTERADO"))
				.andExpect(jsonPath("$[0].venceEn").isNotEmpty())
				.andExpect(jsonPath("$[0].evaluacionId").isNumber())
				.andExpect(jsonPath("$[0].detalle.porQue").value("EL_PROTOCOLO_LO_PIDE"))
				.andExpect(jsonPath("$[0].detalle.condicion.enunciado").value("Las fuentes se contradicen"))
				.andExpect(jsonPath("$[0].detalle.veredicto.resultado").value("CUMPLIDA"))
				.andExpect(jsonPath("$[0].detalle.veredicto.fuente").value("MODELO"))
				.andExpect(jsonPath("$[0].detalle.veredicto.probabilidad").value(0.9))
				.andExpect(jsonPath("$[0].detalle.veredicto.umbralActuar").value(0.7)));
		long derivacionId = Json.numero(lista, "$[0].id");
		bitacora(alerta.incidenteId())
				.andExpect(jsonPath(DERIVADO, hasSize(1)))
				.andExpect(jsonPath(DERIVADO + ".origen").value("SISTEMA"))
				.andExpect(jsonPath(DERIVADO + ".datos.motivo").value("CONDICION_QUE_DERIVA"))
				.andExpect(jsonPath(DERIVADO + ".datos.condicion").value(CONTRADICCION))
				.andExpect(jsonPath(DERIVADO + ".datos.opcionSegura").value("ENTERADO"))
				.andExpect(jsonPath(DERIVADO + ".datos.segundosParaResponder").value(60));
		assertThat(publicaciones.derivacionesPendientes()).containsExactly(1L);

		decidir(derivacionId, "ENTERADO", "  Llamo al testigo  ")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.estado").value("RESUELTA"))
				.andExpect(jsonPath("$.opcionElegida").value("ENTERADO"))
				.andExpect(jsonPath("$.resueltaPor").value(startsWith("Administrador")))
				.andExpect(jsonPath("$.nota").value("Llamo al testigo"))
				.andExpect(jsonPath("$.resueltaEn").isNotEmpty())
				.andExpect(jsonPath("$.necesitaAtencion").value(false));

		pendientes().andExpect(jsonPath("$.length()").value(0));
		assertThat(publicaciones.derivacionesPendientes()).containsExactly(1L, 0L);
		bitacora(alerta.incidenteId())
				.andExpect(jsonPath("$[?(@.tipo == 'DERIVACION_RESUELTA')].origen").value("CENTRAL"))
				.andExpect(jsonPath("$[?(@.tipo == 'DERIVACION_RESUELTA')].datos.opcion").value("ENTERADO"))
				.andExpect(jsonPath("$[?(@.tipo == 'DERIVACION_RESUELTA')].datos.nota").value("Llamo al testigo"))
				// Tomar el caso no decide ninguna condición: eso solo pasa con las que estaban sin resolver.
				.andExpect(jsonPath(DECIDIDA).isEmpty());
		decidir(derivacionId, "ENTERADO", null)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.codigo").value("DERIVACION_CERRADA"));

		// El mismo asunto no se deriva dos veces, aunque otra evaluación lo vuelva a encontrar.
		pedir(post("/alertas/" + alerta.alertaId() + "/detalles"), ciudadano.token(),
				Json.objeto("cantidadAfectados", 2)).andExpect(status().isOk());
		evaluador.barrer();

		deIncidente(alerta.incidenteId()).andExpect(jsonPath("$.length()").value(1));
	}

	@Test
	@DisplayName("PB-38 · CP-38-02 · si el testigo no contesta a tiempo, la condición en duda pasa a una persona; si dice que se cumple, sale la unidad que faltaba")
	void elTestigoNoContestaYLaCentralDecideQueSi() throws Exception {
		escenario.modoDeDespacho("ASIGNACION");
		escenario.protocoloVigente();
		escenario.unidadParaTraslados("II", Escenario.CIEN_METROS);
		Paramedico segunda = escenario.unidadParaTraslados("II", 3 * Escenario.CIEN_METROS);
		Alerta alerta = escenario.alertarCon(escenario.ciudadano(), ACCIDENTE);

		long derivacionId = derivarPorFaltaDeRespuesta(alerta);

		pedir(get("/derivaciones/" + derivacionId), escenario.admin())
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.motivo").value("CONDICION_SIN_RESOLVER"))
				.andExpect(jsonPath("$.condicion").value(ATRAPADA))
				.andExpect(jsonPath("$.opciones[0].codigo").value("SE_CUMPLE"))
				.andExpect(jsonPath("$.opciones[1].codigo").value("NO_SE_CUMPLE"))
				.andExpect(jsonPath("$.opcionSegura").value("SE_CUMPLE"))
				.andExpect(jsonPath("$.detalle.porQue").value("EL_TESTIGO_NO_CONTESTO"))
				.andExpect(jsonPath("$.detalle.condicion.pregunta").value("¿Hay alguien atrapado?"))
				.andExpect(jsonPath("$.detalle.condicion.unidadesAdicionales").value(1))
				.andExpect(jsonPath("$.detalle.condicion.tipoMinimo").value("II"))
				.andExpect(jsonPath("$.detalle.condicion.cita").value("Reglamento de prueba, artículo 1"))
				.andExpect(jsonPath("$.detalle.veredicto.resultado").value("EN_DUDA"))
				.andExpect(jsonPath("$.detalle.veredicto.probabilidad").value(0.5));
		oferta(segunda).andExpect(status().isNoContent());

		decidir(derivacionId, "SE_CUMPLE", null).andExpect(status().isOk());

		// La respuesta se aplica en el momento: el incidente pasa a necesitar dos unidades y sale la segunda.
		pedir(get("/incidentes/" + alerta.incidenteId()), escenario.admin())
				.andExpect(jsonPath("$.unidadesRequeridas").value(2))
				.andExpect(jsonPath("$.tipoMinimoRequerido").value("II"));
		oferta(segunda).andExpect(status().isOk());
		bitacora(alerta.incidenteId())
				.andExpect(jsonPath(DECIDIDA + ".origen").value("CENTRAL"))
				.andExpect(jsonPath(DECIDIDA + ".datos.condicion").value(ATRAPADA))
				.andExpect(jsonPath(DECIDIDA + ".datos.cumple").value(true))
				.andExpect(jsonPath(DECIDIDA + ".datos.por").value("CENTRAL"))
				.andExpect(jsonPath("$[?(@.tipo == 'PLAN_DE_RESPUESTA_ACTUALIZADO')].datos.derivacionId")
						.value((int) derivacionId));

		// La evaluación siguiente respeta lo que decidió la persona y no se lo vuelve a consultar al modelo.
		evaluador.barrer();

		assertThat(ia.evaluacionesPedidas().getLast().preguntas()).extracting(PedidoDeEvaluacion.Pregunta::codigo)
				.containsExactly(CONTRADICCION);
		evaluaciones(alerta.incidenteId())
				.andExpect(jsonPath("$[-1:].pedidaPor").value("CONDICION_DECIDIDA"))
				.andExpect(jsonPath("$[-1:].estadoDelCaso.condicionesDecididas.PERSONA_ATRAPADA.cumple").value(true))
				.andExpect(jsonPath("$[-1:].veredictos[0].resultado").value("CUMPLIDA"))
				.andExpect(jsonPath("$[-1:].veredictos[0].fuente").value("CENTRAL"));
	}

	@Test
	@DisplayName("PB-38 · CP-38-03 · si la persona dice que no se cumple, la condición queda descartada y no se manda nada más")
	void laCentralDecideQueNo() throws Exception {
		escenario.modoDeDespacho("ASIGNACION");
		escenario.protocoloVigente();
		escenario.unidadParaTraslados("II", Escenario.CIEN_METROS);
		Paramedico segunda = escenario.unidadParaTraslados("II", 3 * Escenario.CIEN_METROS);
		Alerta alerta = escenario.alertarCon(escenario.ciudadano(), ACCIDENTE);
		long derivacionId = derivarPorFaltaDeRespuesta(alerta);

		decidir(derivacionId, "NO_SE_CUMPLE", "El testigo confirmó por teléfono que nadie quedó adentro")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.opcionElegida").value("NO_SE_CUMPLE"));
		// Aunque el modelo después diga otra cosa, manda lo que decidió la persona.
		ia.responderProbabilidad(ATRAPADA, 0.95);
		evaluador.barrer();

		pedir(get("/incidentes/" + alerta.incidenteId()), escenario.admin())
				.andExpect(jsonPath("$.unidadesRequeridas").value(1));
		oferta(segunda).andExpect(status().isNoContent());
		bitacora(alerta.incidenteId()).andExpect(jsonPath(DECIDIDA + ".datos.cumple").value(false));
		evaluaciones(alerta.incidenteId())
				.andExpect(jsonPath("$[-1:].veredictos[0].resultado").value("NO_CUMPLIDA"))
				.andExpect(jsonPath("$[-1:].veredictos[0].fuente").value("CENTRAL"));
	}

	@Test
	@DisplayName("PB-38 · CP-38-04 · si nadie contesta a tiempo se aplica la opción segura, y la derivación sigue a la vista hasta que alguien la marca como vista")
	void nadieContesta() throws Exception {
		escenario.modoDeDespacho("ASIGNACION");
		escenario.protocoloVigente();
		escenario.unidadParaTraslados("II", Escenario.CIEN_METROS);
		Paramedico segunda = escenario.unidadParaTraslados("II", 3 * Escenario.CIEN_METROS);
		Alerta alerta = escenario.alertarCon(escenario.ciudadano(), ACCIDENTE);
		long derivacionId = derivarPorFaltaDeRespuesta(alerta);

		// Todavía tiene tiempo: el barrido no la toca.
		vencimiento.barrer();
		pendientes().andExpect(jsonPath("$[0].estado").value("ABIERTA"));

		pasarElTiempoDeLaCentral();

		// Ante la duda se atiende como el caso más grave: la condición se da por cumplida y sale la segunda unidad.
		pendientes()
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].estado").value("VENCIDA"))
				.andExpect(jsonPath("$[0].opcionElegida").value("SE_CUMPLE"))
				.andExpect(jsonPath("$[0].resueltaPor").doesNotExist())
				.andExpect(jsonPath("$[0].necesitaAtencion").value(true));
		pedir(get("/incidentes/" + alerta.incidenteId()), escenario.admin())
				.andExpect(jsonPath("$.unidadesRequeridas").value(2));
		oferta(segunda).andExpect(status().isOk());
		bitacora(alerta.incidenteId())
				.andExpect(jsonPath("$[?(@.tipo == 'DERIVACION_VENCIDA')].datos.opcionAplicada").value("SE_CUMPLE"))
				.andExpect(jsonPath(DECIDIDA + ".origen").value("SISTEMA"))
				.andExpect(jsonPath(DECIDIDA + ".datos.por").value("ANTE_LA_DUDA"));
		decidir(derivacionId, "NO_SE_CUMPLE", null)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.codigo").value("DERIVACION_CERRADA"));

		pedir(post("/derivaciones/" + derivacionId + "/visto"), escenario.admin())
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.estado").value("VENCIDA"))
				.andExpect(jsonPath("$.vistaPor").value(startsWith("Administrador")))
				.andExpect(jsonPath("$.vistaEn").isNotEmpty())
				.andExpect(jsonPath("$.necesitaAtencion").value(false));

		pendientes().andExpect(jsonPath("$.length()").value(0));
		pedir(get("/derivaciones?todas=true"), escenario.admin())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].id").value(derivacionId));
		evaluador.barrer();
		evaluaciones(alerta.incidenteId())
				.andExpect(jsonPath("$[-1:].veredictos[0].resultado").value("CUMPLIDA"))
				.andExpect(jsonPath("$[-1:].veredictos[0].fuente").value("ANTE_LA_DUDA"));
	}

	@Test
	@DisplayName("PB-38 · CP-38-05 · la persona solo puede contestar con una opción del motivo, y marcar como vista solo lo vencido")
	void opcionesDelMotivo() throws Exception {
		escenario.protocoloVigente();
		ia.responderProbabilidad(CONTRADICCION, 0.9);
		Alerta alerta = escenario.alertarCon(escenario.ciudadano(), ACCIDENTE);
		evaluador.barrer();
		long derivacionId = unicaDerivacion(alerta.incidenteId());

		decidir(derivacionId, "SE_CUMPLE", null)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.codigo").value("OPCION_NO_PERMITIDA"));
		pedir(post("/derivaciones/" + derivacionId + "/decision"), escenario.admin(), "{}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.codigo").value("VALIDACION"));
		pedir(post("/derivaciones/" + derivacionId + "/visto"), escenario.admin())
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.codigo").value("DERIVACION_CERRADA"));

		pendientes().andExpect(jsonPath("$[0].estado").value("ABIERTA"));
	}

	@Test
	@DisplayName("PB-38 · CP-38-06 · si no queda ninguna unidad propia para el incidente, se le pasa a una persona, que puede pedir apoyo externo")
	void sinUnidad() throws Exception {
		escenario.modoDeDespacho("ASIGNACION");
		// De turno pero sin posición: el sistema no tiene a quién asignarle el incidente.
		escenario.paramedicoEnTurno();

		Alerta alerta = escenario.alertar(escenario.ciudadano());

		String lista = cuerpo(pendientes()
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].motivo").value("SIN_UNIDAD"))
				.andExpect(jsonPath("$[0].condicion").doesNotExist())
				.andExpect(jsonPath("$[0].opciones[0].codigo").value("PEDIR_APOYO_EXTERNO"))
				.andExpect(jsonPath("$[0].opciones[1].codigo").value("SEGUIR_ESPERANDO"))
				.andExpect(jsonPath("$[0].opcionSegura").value("SEGUIR_ESPERANDO"))
				.andExpect(jsonPath("$[0].detalle.unidadesQueFaltan").value(1))
				.andExpect(jsonPath("$[0].detalle.unidadesRequeridas").value(1)));

		decidir(Json.numero(lista, "$[0].id"), "PEDIR_APOYO_EXTERNO", "Se llamó a la central del SEDES")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.estado").value("RESUELTA"));

		// Pedir apoyo afuera no cambia nada en el incidente: sigue abierto, esperando una unidad propia.
		pedir(get("/incidentes/" + alerta.incidenteId()), escenario.admin())
				.andExpect(jsonPath("$.estado").value("ACTIVO"))
				.andExpect(jsonPath("$.unidadesRequeridas").value(1));
		bitacora(alerta.incidenteId())
				.andExpect(jsonPath("$[?(@.tipo == 'DERIVACION_RESUELTA')].datos.opcion").value("PEDIR_APOYO_EXTERNO"))
				.andExpect(jsonPath(DECIDIDA).isEmpty());
	}

	@Test
	@DisplayName("PB-38 · CP-38-07 · si el incidente se cierra antes de que contesten, la derivación se retira")
	void seRetiraAlCerrarse() throws Exception {
		escenario.protocoloVigente();
		ia.responderProbabilidad(CONTRADICCION, 0.9);
		Alerta alerta = escenario.alertarCon(escenario.ciudadano(), ACCIDENTE);
		evaluador.barrer();
		long derivacionId = unicaDerivacion(alerta.incidenteId());

		pedir(post("/incidentes/" + alerta.incidenteId() + "/cierre"), escenario.admin(),
				Json.objeto("motivo", "SIN_COBERTURA")).andExpect(status().isNoContent());

		pendientes().andExpect(jsonPath("$.length()").value(0));
		pedir(get("/derivaciones/" + derivacionId), escenario.admin())
				.andExpect(jsonPath("$.estado").value("RETIRADA"))
				.andExpect(jsonPath("$.nota").value("incidente_cerrado"))
				.andExpect(jsonPath("$.necesitaAtencion").value(false));
		bitacora(alerta.incidenteId())
				.andExpect(jsonPath("$[?(@.tipo == 'DERIVACION_RETIRADA')].datos.razon").value("incidente_cerrado"));
		// Una retirada no vence: el barrido no le aplica nada.
		pasarElTiempoDeLaCentral();
		pedir(get("/derivaciones/" + derivacionId), escenario.admin())
				.andExpect(jsonPath("$.estado").value("RETIRADA"));
	}

	@Test
	@DisplayName("PB-38 · CP-38-08 · desde que una unidad llega al lugar, lo que esperaba respuesta se retira: decide quien está allí")
	void seRetiraAlLlegarUnaUnidad() throws Exception {
		escenario.protocoloVigente();
		ia.responderProbabilidad(CONTRADICCION, 0.9);
		Paramedico paramedico = escenario.paramedicoEnTurno();
		Alerta alerta = escenario.alertarCon(escenario.ciudadano(), ACCIDENTE);
		evaluador.barrer();
		long derivacionId = unicaDerivacion(alerta.incidenteId());

		escenario.marcar(paramedico, escenario.tomar(paramedico, alerta.incidenteId()), "llegada");

		pedir(get("/derivaciones/" + derivacionId), escenario.admin())
				.andExpect(jsonPath("$.estado").value("RETIRADA"))
				.andExpect(jsonPath("$.nota").value("unidad_en_el_lugar"));
	}

	@Test
	@DisplayName("PB-38 · CP-38-09 · una condición en duda sin a quién preguntarle solo se deriva si hay una evidencia que una persona pueda mirar")
	void soloConEvidencia() throws Exception {
		escenario.protocoloVigente();
		Ciudadano ciudadano = escenario.ciudadano();
		Alerta alerta = escenario.alertarCon(ciudadano, ACCIDENTE);
		responder(ciudadano, alerta, ATRAPADA, "NO_SE");

		// Sin ninguna evidencia, una persona tampoco tendría con qué decidir: no se deriva nada.
		evaluador.barrer();
		deIncidente(alerta.incidenteId()).andExpect(jsonPath("$.length()").value(0));

		ia.analizarCon(ANALISIS_UTIL);
		escenario.evidenciaSubida(ciudadano, alerta.alertaId(), "image/jpeg");
		analizador.barrer();
		evaluador.barrer();

		deIncidente(alerta.incidenteId())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].motivo").value("CONDICION_SIN_RESOLVER"))
				.andExpect(jsonPath("$[0].condicion").value(ATRAPADA))
				.andExpect(jsonPath("$[0].detalle.porQue").value("EL_TESTIGO_NO_SABE"))
				.andExpect(jsonPath("$[0].detalle.veredicto.respuestaDelTestigo").value("NO_SE"))
				.andExpect(jsonPath("$[1].condicion").value(CONTRADICCION))
				.andExpect(jsonPath("$[1].detalle.porQue").value("SIN_PREGUNTA_PARA_EL_TESTIGO"));
	}

	@Test
	@DisplayName("PB-38 · CP-38-10 · si el testigo contesta a tiempo, no hace falta ninguna persona")
	void elTestigoContestaATiempo() throws Exception {
		escenario.protocoloVigente();
		Ciudadano ciudadano = escenario.ciudadano();
		Alerta alerta = escenario.alertarCon(ciudadano, ACCIDENTE);
		evaluador.barrer();

		responder(ciudadano, alerta, ATRAPADA, "NO");
		evaluador.barrer();
		pasarElTiempoDelTestigo();
		evaluador.barrer();

		deIncidente(alerta.incidenteId()).andExpect(jsonPath("$.length()").value(0));
		pendientes().andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	@DisplayName("PB-38 · CP-38-11 · las derivaciones solo las ve y las contesta la central")
	void soloLaCentral() throws Exception {
		escenario.protocoloVigente();
		ia.responderProbabilidad(CONTRADICCION, 0.9);
		Ciudadano ciudadano = escenario.ciudadano();
		Alerta alerta = escenario.alertarCon(ciudadano, ACCIDENTE);
		evaluador.barrer();
		long derivacionId = unicaDerivacion(alerta.incidenteId());
		String paramedico = escenario.paramedicoActivado().token();

		pedir(get("/derivaciones"), null).andExpect(status().isUnauthorized());
		pedir(get("/derivaciones"), ciudadano.token()).andExpect(status().isForbidden());
		pedir(get("/derivaciones/" + derivacionId), paramedico).andExpect(status().isForbidden());
		pedir(post("/derivaciones/" + derivacionId + "/decision"), paramedico, Json.objeto("opcion", "ENTERADO"))
				.andExpect(status().isForbidden());
		pedir(get("/incidentes/" + alerta.incidenteId() + "/derivaciones"), ciudadano.token())
				.andExpect(status().isForbidden());
		pedir(get("/derivaciones/999999"), escenario.admin()).andExpect(status().isNotFound());
		decidir(999999, "ENTERADO", null).andExpect(status().isNotFound());
		pedir(get("/incidentes/999999/derivaciones"), escenario.admin()).andExpect(status().isNotFound());
	}

}
