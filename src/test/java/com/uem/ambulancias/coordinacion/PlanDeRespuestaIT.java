package com.uem.ambulancias.coordinacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import com.uem.ambulancias.coordinacion.service.EvaluadorDeCasos;
import com.uem.ambulancias.emergencias.service.IncidentePublicado;
import com.uem.ambulancias.evidencias.service.FalloDelServicioIa.Desenlace;
import com.uem.ambulancias.flota.domain.TipoUnidad;
import com.uem.ambulancias.soporte.Escenario;
import com.uem.ambulancias.soporte.Escenario.Alerta;
import com.uem.ambulancias.soporte.Escenario.Ciudadano;
import com.uem.ambulancias.soporte.Escenario.Paramedico;
import com.uem.ambulancias.soporte.Json;
import com.uem.ambulancias.soporte.PruebaIT;

/**
 * Las condiciones que se cumplen fijan lo que el incidente necesita: cuántas unidades y de qué tipo como mínimo. El
 * sistema solo suma: asigna las unidades que faltan, cada una con su confirmación, y nunca retira una que ya va.
 */
@Tag("PB-36")
class PlanDeRespuestaIT extends PruebaIT {

	private static final String ACCIDENTE = "ACCIDENTE_DE_TRANSITO";
	private static final String INCONSCIENTE = "PERSONA_INCONSCIENTE";
	private static final String ATRAPADA = "PERSONA_ATRAPADA";
	private static final String NO_RESPIRA = "NO_RESPIRA";

	private static final double CERCA = Escenario.CIEN_METROS;
	private static final double MEDIO = 3 * Escenario.CIEN_METROS;
	private static final double LEJOS = 6 * Escenario.CIEN_METROS;

	private static final String PLAN = "$[?(@.tipo == 'PLAN_DE_RESPUESTA_ACTUALIZADO')]";
	private static final String SIN_UNIDAD = "$[?(@.tipo == 'SIN_UNIDAD_PARA_ASIGNAR')]";

	/** Un protocolo con una condición que se resuelve por cantidad: desde dos afectados hace falta otra unidad. */
	private static final String PROTOCOLO_POR_CANTIDAD = """
			{"nota": "Protocolo de prueba con regla por cantidad", "tipos": [
			  {"codigo": "ACCIDENTE_DE_TRANSITO", "nombre": "Accidente de tránsito", "condiciones": [
			    {"codigo": "VARIOS_HERIDOS", "enunciado": "Hay más de un herido",
			     "umbralActuar": 0.8, "umbralPreguntar": 0.4, "unidadesAdicionales": 1, "afectadosDesde": 2}
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

	private void declararAfectados(Ciudadano ciudadano, Alerta alerta, int cantidad) throws Exception {
		pedir(post("/alertas/" + alerta.alertaId() + "/detalles"), ciudadano.token(),
				Json.objeto("cantidadAfectados", cantidad)).andExpect(status().isOk());
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

	private List<String> avisosA(String tokenPush) {
		return publicaciones.avisosAlParamedico(tokenPush).stream().map(aviso -> aviso.datos().get("tipo")).toList();
	}

	private Paramedico unidad(String tipo, double alNorte) {
		return escenario.unidadParaTraslados(tipo, alNorte);
	}

	@Test
	@DisplayName("PB-36 · CP-36-01 · si el testigo dice que hay alguien atrapado, el incidente pasa a necesitar dos unidades y se asigna la segunda")
	void sumaLaSegundaUnidad() throws Exception {
		escenario.modoDeDespacho("ASIGNACION");
		escenario.protocoloVigente();
		Paramedico cerca = unidad("II", CERCA);
		Paramedico medio = unidad("II", MEDIO);
		Paramedico lejos = unidad("II", LEJOS);
		Ciudadano ciudadano = escenario.ciudadano();
		Alerta alerta = escenario.alertarCon(ciudadano, ACCIDENTE);
		// La primera unidad sale con la alerta, sin esperar ninguna evaluación.
		oferta(cerca).andExpect(status().isOk());
		oferta(medio).andExpect(status().isNoContent());
		incidente(alerta.incidenteId())
				.andExpect(jsonPath("$.unidadesRequeridas").value(1))
				.andExpect(jsonPath("$.tipoMinimoRequerido").doesNotExist());

		responder(ciudadano, alerta, ATRAPADA, "SI");
		evaluador.barrer();

		incidente(alerta.incidenteId())
				.andExpect(jsonPath("$.unidadesRequeridas").value(2))
				.andExpect(jsonPath("$.tipoMinimoRequerido").value("II"));
		oferta(cerca).andExpect(status().isOk());
		oferta(medio)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.incidenteId").value(alerta.incidenteId()));
		oferta(lejos).andExpect(status().isNoContent());
		bitacora(alerta.incidenteId())
				.andExpect(jsonPath(PLAN, hasSize(1)))
				.andExpect(jsonPath(PLAN + ".origen").value("SISTEMA"))
				.andExpect(jsonPath(PLAN + ".datos.unidades").value(2))
				.andExpect(jsonPath(PLAN + ".datos.tipoMinimo").value("II"))
				.andExpect(jsonPath(PLAN + ".datos.unidadesAntes").value(1))
				.andExpect(jsonPath(PLAN + ".datos.tipoMinimoAntes").isEmpty())
				.andExpect(jsonPath(PLAN + ".datos.evaluacionId").isNotEmpty())
				.andExpect(jsonPath(PLAN + ".datos.porCondiciones[0]").value(ATRAPADA))
				.andExpect(jsonPath("$[?(@.tipo == 'UNIDAD_ASIGNADA')]", hasSize(2)))
				.andExpect(jsonPath("$[?(@.tipo == 'UNIDAD_ASIGNADA' && @.datos.orden == 2)].datos.ambulanciaId")
						.value(medio.ambulanciaId().intValue()))
				.andExpect(jsonPath("$[?(@.tipo == 'UNIDAD_ASIGNADA' && @.datos.orden == 2)].datos.alcanzaElTipoPedido")
						.value(true));

		// Las dos confirman: el caso tiene lo que pidió y una evaluación más no manda a nadie.
		confirmar(cerca);
		confirmar(medio);
		declararAfectados(ciudadano, alerta, 2);
		evaluador.barrer();

		incidente(alerta.incidenteId())
				.andExpect(jsonPath("$.estado").value("EN_ATENCION"))
				.andExpect(jsonPath("$.atenciones.length()").value(2))
				.andExpect(jsonPath("$.unidadesRequeridas").value(2));
		oferta(lejos).andExpect(status().isNoContent());
		bitacora(alerta.incidenteId()).andExpect(jsonPath(SIN_UNIDAD).isEmpty());
	}

	@Test
	@DisplayName("PB-36 · CP-36-02 · si la condición pide un tipo de unidad y la asignada no lo alcanza, se suma una que sí, sin retirar la primera")
	void sumaUnaUnidadDelTipoPedido() throws Exception {
		escenario.modoDeDespacho("ASIGNACION");
		escenario.protocoloVigente();
		Paramedico basica = unidad("IA", CERCA);
		Paramedico intermedia = unidad("II", MEDIO);
		Paramedico avanzada = unidad("III", LEJOS);
		escenario.recibirAvisos(basica, "push-basica");
		Ciudadano ciudadano = escenario.ciudadano();
		Alerta alerta = escenario.alertarCon(ciudadano, INCONSCIENTE);
		oferta(basica).andExpect(status().isOk());

		responder(ciudadano, alerta, NO_RESPIRA, "SI");
		evaluador.barrer();

		// Sigue haciendo falta una sola unidad, pero de tipo III: la más cercana que lo alcanza es la avanzada.
		incidente(alerta.incidenteId())
				.andExpect(jsonPath("$.unidadesRequeridas").value(1))
				.andExpect(jsonPath("$.tipoMinimoRequerido").value("III"));
		oferta(avanzada)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.incidenteId").value(alerta.incidenteId()));
		oferta(intermedia).andExpect(status().isNoContent());
		// La básica llega antes y no se le retira la asignación.
		oferta(basica).andExpect(status().isOk());
		assertThat(avisosA("push-basica")).containsExactly("INCIDENTE_POR_CONFIRMAR");
		bitacora(alerta.incidenteId())
				.andExpect(jsonPath(PLAN + ".datos.unidades").value(1))
				.andExpect(jsonPath(PLAN + ".datos.tipoMinimo").value("III"))
				.andExpect(jsonPath("$[?(@.tipo == 'UNIDAD_ASIGNADA' && @.datos.orden == 2)].datos.tipoUnidad")
						.value("III"))
				.andExpect(jsonPath("$[?(@.tipo == 'UNIDAD_ASIGNADA' && @.datos.orden == 2)].datos.tipoMinimoPedido")
						.value("III"));

		confirmar(basica);
		confirmar(avanzada);

		incidente(alerta.incidenteId()).andExpect(jsonPath("$.atenciones.length()").value(2));
		oferta(intermedia).andExpect(status().isNoContent());
	}

	@Test
	@DisplayName("PB-36 · CP-36-03 · si no hay ninguna unidad del tipo pedido queda anotado una sola vez y el incidente se abre a todas")
	void sinUnidadDelTipoPedido() throws Exception {
		escenario.modoDeDespacho("ASIGNACION");
		escenario.protocoloVigente();
		Paramedico basica = unidad("IA", CERCA);
		Paramedico intermedia = unidad("II", MEDIO);
		Ciudadano ciudadano = escenario.ciudadano();
		Alerta alerta = escenario.alertarCon(ciudadano, INCONSCIENTE);
		responder(ciudadano, alerta, NO_RESPIRA, "SI");

		evaluador.barrer();

		oferta(basica).andExpect(status().isOk());
		// Por cantidad ya está cubierto, y la intermedia tampoco alcanza el tipo III: no se la manda sola.
		oferta(intermedia).andExpect(status().isNoContent());
		assertThat(publicaciones.cantidadDeAvisosDeIncidenteNuevo()).isEqualTo(1);
		bitacora(alerta.incidenteId())
				.andExpect(jsonPath(SIN_UNIDAD, hasSize(1)))
				.andExpect(jsonPath(SIN_UNIDAD + ".datos.unidadesQueFaltan").value(0))
				.andExpect(jsonPath(SIN_UNIDAD + ".datos.tipoQueFalta").value("III"));

		// Otro cambio en el incidente no lo vuelve a anotar ni a avisar.
		declararAfectados(ciudadano, alerta, 1);
		evaluador.barrer();

		assertThat(publicaciones.cantidadDeAvisosDeIncidenteNuevo()).isEqualTo(1);
		bitacora(alerta.incidenteId()).andExpect(jsonPath(SIN_UNIDAD, hasSize(1)));
	}

	@Test
	@DisplayName("PB-36 · CP-36-04 · lo que el incidente necesita solo sube: si el testigo cambia su respuesta, no se retira nada")
	void soloSube() throws Exception {
		escenario.modoDeDespacho("ASIGNACION");
		escenario.protocoloVigente();
		Paramedico cerca = unidad("II", CERCA);
		Paramedico medio = unidad("II", MEDIO);
		Ciudadano ciudadano = escenario.ciudadano();
		Alerta alerta = escenario.alertarCon(ciudadano, ACCIDENTE);
		responder(ciudadano, alerta, ATRAPADA, "SI");
		evaluador.barrer();

		responder(ciudadano, alerta, ATRAPADA, "NO");
		evaluador.barrer();

		pedir(get("/incidentes/" + alerta.incidenteId() + "/evaluaciones"), escenario.admin())
				.andExpect(jsonPath("$[1].veredictos[0].resultado").value("NO_CUMPLIDA"));
		incidente(alerta.incidenteId())
				.andExpect(jsonPath("$.unidadesRequeridas").value(2))
				.andExpect(jsonPath("$.tipoMinimoRequerido").value("II"));
		oferta(cerca).andExpect(status().isOk());
		oferta(medio).andExpect(status().isOk());
		bitacora(alerta.incidenteId())
				.andExpect(jsonPath(PLAN, hasSize(1)))
				.andExpect(jsonPath("$[?(@.tipo == 'ASIGNACION_RETIRADA')]").isEmpty());
	}

	@Test
	@DisplayName("PB-36 · CP-36-05 · una condición por cantidad la resuelve la regla con los afectados declarados, sin consultar al modelo")
	void reglaPorCantidad() throws Exception {
		escenario.publicarProtocolo(PROTOCOLO_POR_CANTIDAD);
		pedir(get("/protocolos/vigente"), escenario.admin())
				.andExpect(jsonPath("$.tipos[0].condiciones[0].afectadosDesde").value(2));
		Ciudadano ciudadano = escenario.ciudadano();
		Alerta alerta = escenario.alertarCon(ciudadano, ACCIDENTE);

		// Sin la cantidad no hay con qué aplicar la regla, y al modelo no se le pide que cuente.
		evaluador.barrer();
		declararAfectados(ciudadano, alerta, 1);
		evaluador.barrer();
		declararAfectados(ciudadano, alerta, 3);
		evaluador.barrer();

		assertThat(ia.evaluacionesPedidas()).isEmpty();
		pedir(get("/incidentes/" + alerta.incidenteId() + "/evaluaciones"), escenario.admin())
				.andExpect(jsonPath("$.length()").value(3))
				.andExpect(jsonPath("$[0].metodo").value("REGLAS"))
				.andExpect(jsonPath("$[0].veredictos[0].resultado").value("SIN_EVALUAR"))
				.andExpect(jsonPath("$[1].veredictos[0].resultado").value("NO_CUMPLIDA"))
				.andExpect(jsonPath("$[1].veredictos[0].fuente").value("REGLA"))
				.andExpect(jsonPath("$[2].veredictos[0].resultado").value("CUMPLIDA"))
				.andExpect(jsonPath("$[2].veredictos[0].fuente").value("REGLA"));
		incidente(alerta.incidenteId())
				.andExpect(jsonPath("$.cantidadAfectados").value(3))
				.andExpect(jsonPath("$.unidadesRequeridas").value(2))
				.andExpect(jsonPath("$.tipoMinimoRequerido").doesNotExist());
		bitacora(alerta.incidenteId())
				.andExpect(jsonPath(PLAN, hasSize(1)))
				.andExpect(jsonPath(PLAN + ".datos.porCondiciones[0]").value("VARIOS_HERIDOS"));
	}

	@Test
	@DisplayName("PB-36 · CP-36-06 · lo que el testigo ya contestó se aplica sin esperar al modelo, aunque el modelo esté caído")
	void seAdelantaAlModelo() throws Exception {
		escenario.modoDeDespacho("ASIGNACION");
		escenario.protocoloVigente();
		unidad("II", CERCA);
		Paramedico medio = unidad("II", MEDIO);
		Ciudadano ciudadano = escenario.ciudadano();
		Alerta alerta = escenario.alertarCon(ciudadano, ACCIDENTE);
		responder(ciudadano, alerta, ATRAPADA, "SI");
		ia.fallarEvaluaciones("provider_unavailable", Desenlace.REINTENTAR);

		evaluador.barrer();

		// La evaluación sigue esperando al modelo, pero la segunda unidad ya tiene su asignación.
		pedir(get("/incidentes/" + alerta.incidenteId() + "/evaluaciones"), escenario.admin())
				.andExpect(jsonPath("$[0].estado").value("PENDIENTE"));
		oferta(medio).andExpect(status().isOk());
		bitacora(alerta.incidenteId())
				.andExpect(jsonPath(PLAN + ".datos.unidades").value(2))
				.andExpect(jsonPath(PLAN + ".datos.antesDelModelo").value(true))
				.andExpect(jsonPath("$[?(@.tipo == 'CONDICIONES_EVALUADAS')]").isEmpty());
	}

	@Test
	@DisplayName("PB-36 · CP-36-07 · si otra unidad se suma por su cuenta, solo se retira la asignación pendiente que sobra")
	void retiraSoloLaQueSobra() throws Exception {
		escenario.modoDeDespacho("ASIGNACION");
		escenario.protocoloVigente();
		Paramedico cerca = unidad("II", CERCA);
		Paramedico medio = unidad("II", MEDIO);
		escenario.recibirAvisos(medio, "push-medio");
		// En turno pero sin posición reportada: el sistema no le asigna nada, y aun así puede tomar el caso.
		Paramedico porSuCuenta = escenario.paramedicoEnTurno();
		Ciudadano ciudadano = escenario.ciudadano();
		Alerta alerta = escenario.alertarCon(ciudadano, ACCIDENTE);
		responder(ciudadano, alerta, ATRAPADA, "SI");
		evaluador.barrer();
		oferta(cerca).andExpect(status().isOk());
		oferta(medio).andExpect(status().isOk());

		escenario.tomar(porSuCuenta, alerta.incidenteId());

		// Hacen falta dos y ya va una: queda la asignación de la más cercana y se retira la otra.
		oferta(cerca).andExpect(status().isOk());
		oferta(medio).andExpect(status().isNoContent());
		assertThat(avisosA("push-medio")).containsExactly("INCIDENTE_POR_CONFIRMAR", "ASIGNACION_RETIRADA");
		bitacora(alerta.incidenteId())
				.andExpect(jsonPath("$[?(@.tipo == 'ASIGNACION_RETIRADA')]", hasSize(1)))
				.andExpect(jsonPath("$[?(@.tipo == 'ASIGNACION_RETIRADA')].datos.ambulanciaId")
						.value(medio.ambulanciaId().intValue()));
	}

	@Test
	@DisplayName("PB-36 · CP-36-08 · si la unidad adicional dice que no puede y no queda otra, se anota cuántas faltan y se abre a todas")
	void sinUnidadAdicional() throws Exception {
		escenario.modoDeDespacho("ASIGNACION");
		escenario.protocoloVigente();
		Paramedico cerca = unidad("II", CERCA);
		Paramedico medio = unidad("II", MEDIO);
		Ciudadano ciudadano = escenario.ciudadano();
		Alerta alerta = escenario.alertarCon(ciudadano, ACCIDENTE);
		confirmar(cerca);
		responder(ciudadano, alerta, ATRAPADA, "SI");
		evaluador.barrer();
		oferta(medio).andExpect(status().isOk());

		pedir(post("/paramedicos/actual/oferta/rechazo"), medio.token(), Json.objeto("motivo", "Sin combustible"))
				.andExpect(status().isNoContent());

		bitacora(alerta.incidenteId())
				.andExpect(jsonPath(SIN_UNIDAD, hasSize(1)))
				.andExpect(jsonPath(SIN_UNIDAD + ".datos.unidadesQueFaltan").value(1))
				.andExpect(jsonPath(SIN_UNIDAD + ".datos.tipoQueFalta").doesNotExist());
		assertThat(publicaciones.cantidadDeAvisosDeIncidenteNuevo()).isEqualTo(1);
		// La que ya va en camino sigue en el caso.
		incidente(alerta.incidenteId())
				.andExpect(jsonPath("$.estado").value("EN_ATENCION"))
				.andExpect(jsonPath("$.atenciones[0].ambulanciaId").value(cerca.ambulanciaId()));
	}

	@Test
	@DisplayName("PB-36 · CP-36-09 · cuando una unidad queda libre, se le asigna el incidente al que le faltaba una")
	void alLiberarseUnaUnidad() throws Exception {
		escenario.modoDeDespacho("ASIGNACION");
		escenario.protocoloVigente();
		long centro = escenario.centroDeSalud("Hospital Japonés", true);
		// La única unidad en la calle se va a otro incidente, a un kilómetro.
		Paramedico ocupada = unidad("II", MEDIO);
		escenario.alertar(escenario.ciudadano(), Escenario.LATITUD - 0.01, Escenario.LONGITUD);
		long otraAtencion = confirmar(ocupada);
		Paramedico cerca = unidad("II", CERCA);
		Ciudadano ciudadano = escenario.ciudadano();
		Alerta alerta = escenario.alertarCon(ciudadano, ACCIDENTE);
		confirmar(cerca);
		responder(ciudadano, alerta, ATRAPADA, "SI");
		evaluador.barrer();
		bitacora(alerta.incidenteId()).andExpect(jsonPath(SIN_UNIDAD + ".datos.unidadesQueFaltan").value(1));

		escenario.hastaElHospital(ocupada, otraAtencion);
		pedir(post("/atenciones/" + otraAtencion + "/entrega"), ocupada.token(), Json.objeto("latitud",
				Escenario.LATITUD, "longitud", Escenario.LONGITUD, "centroSaludId", centro)).andExpect(status().isOk());
		escenario.liberar(ocupada, otraAtencion);

		oferta(ocupada)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.incidenteId").value(alerta.incidenteId()));
		bitacora(alerta.incidenteId())
				.andExpect(jsonPath("$[-1:].tipo").value("UNIDAD_ASIGNADA"))
				.andExpect(jsonPath("$[-1:].datos.ronda").value(2));
	}

	@Test
	@DisplayName("PB-36 · CP-36-10 · en modo difusión no se asigna a nadie: se avisa a las de turno que hace falta apoyo")
	void enModoDifusion() throws Exception {
		escenario.protocoloVigente();
		Paramedico una = unidad("II", CERCA);
		Paramedico otra = unidad("II", MEDIO);
		escenario.recibirAvisos(una, "push-una");
		escenario.recibirAvisos(otra, "push-otra");
		Ciudadano ciudadano = escenario.ciudadano();
		Alerta alerta = escenario.alertarCon(ciudadano, ACCIDENTE);
		assertThat(publicaciones.cantidadDeAvisosDeIncidenteNuevo()).isEqualTo(1);
		escenario.tomar(una, alerta.incidenteId());

		responder(ciudadano, alerta, ATRAPADA, "SI");
		evaluador.barrer();

		assertThat(publicaciones.cantidadDeAvisosDeIncidenteNuevo()).isEqualTo(2);
		oferta(otra).andExpect(status().isNoContent());
		IncidentePublicado publicado = publicaciones.incidentesAbiertos(alerta.incidenteId()).getLast();
		assertThat(publicado.unidadesRequeridas()).isEqualTo(2);
		assertThat(publicado.tipoMinimoRequerido()).isEqualTo(TipoUnidad.II);
		assertThat(publicado.unidadesAcudiendo()).isEqualTo(1);
		incidente(alerta.incidenteId()).andExpect(jsonPath("$.unidadesRequeridas").value(2));
	}

	@Test
	@DisplayName("PB-36 · CP-36-11 · el protocolo no acepta una unidad de rescate como tipo mínimo ni una cantidad de afectados menor a uno")
	void validacionesDelProtocolo() throws Exception {
		pedir(post("/protocolos/borrador"), escenario.admin()).andExpect(status().isCreated());

		pedir(put("/protocolos/borrador"), escenario.admin(),
				Escenario.PROTOCOLO_DE_EJEMPLO.replace("\"tipoMinimo\": \"III\"", "\"tipoMinimo\": \"IB\""))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.codigo").value("PROTOCOLO_INVALIDO"));
		pedir(put("/protocolos/borrador"), escenario.admin(),
				PROTOCOLO_POR_CANTIDAD.replace("\"afectadosDesde\": 2", "\"afectadosDesde\": 0"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.codigo").value("VALIDACION"));
		pedir(put("/protocolos/borrador"), escenario.admin(), PROTOCOLO_POR_CANTIDAD)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tipos[0].condiciones[0].afectadosDesde").value(2));
	}

}
