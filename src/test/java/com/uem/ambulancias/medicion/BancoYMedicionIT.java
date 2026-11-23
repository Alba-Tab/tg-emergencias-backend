package com.uem.ambulancias.medicion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import com.uem.ambulancias.evidencias.service.FalloDelServicioIa.Desenlace;
import com.uem.ambulancias.medicion.service.MedidorDeCasos;
import com.uem.ambulancias.soporte.Escenario.Alerta;
import com.uem.ambulancias.soporte.Escenario.Ciudadano;
import com.uem.ambulancias.soporte.Json;
import com.uem.ambulancias.soporte.PruebaIT;

/**
 * El banco de casos, la medición del modelo de decisión y la repetición de un incidente. El medidor es el real y cada
 * prueba lo dispara cuando quiere; quien responde por el modelo es el servicio falso, que contesta según lo que el
 * testigo marcó en cada caso.
 */
@Tag("PB-41")
class BancoYMedicionIT extends PruebaIT {

	private static final String ACCIDENTE = "ACCIDENTE_DE_TRANSITO";
	private static final String ATRAPADA = "PERSONA_ATRAPADA";

	@Autowired
	private MedidorDeCasos medidor;

	@Autowired
	private EvaluadorDeCasos evaluador;

	private ResultActions generar(int cantidad, long semilla) throws Exception {
		return pedir(post("/banco-de-casos/generacion"), escenario.admin(),
				Json.objeto("cantidad", cantidad, "semilla", semilla));
	}

	private long medir(String cuerpo) throws Exception {
		return Json.numero(cuerpo(pedir(post("/mediciones"), escenario.admin(), cuerpo)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.estado").value("PENDIENTE"))), "$.id");
	}

	private ResultActions medicion(long id) throws Exception {
		return pedir(get("/mediciones/" + id), escenario.admin()).andExpect(status().isOk());
	}

	@Test
	@DisplayName("PB-41 · CP-41-01 · el generador llena el banco con casos inventados del protocolo vigente, marcados como generados, y con la misma semilla salen los mismos")
	void generaCasos() throws Exception {
		generar(5, 7).andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("PROTOCOLO_INVALIDO"));
		escenario.protocoloVigente();

		generar(6, 7)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.generados").value(6))
				.andExpect(jsonPath("$.semilla").value(7));
		generar(6, 7).andExpect(status().isCreated());

		pedir(get("/banco-de-casos/resumen"), escenario.admin())
				.andExpect(jsonPath("$.GENERADO").value(12))
				.andExpect(jsonPath("$.REAL").value(0));
		String casos = cuerpo(pedir(get("/banco-de-casos?origen=GENERADO"), escenario.admin())
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(12))
				.andExpect(jsonPath("$[0].origen").value("GENERADO"))
				.andExpect(jsonPath("$[0].estado.tipoDeSuceso.codigo").isNotEmpty())
				.andExpect(jsonPath("$[0].estado.respuestasDelTestigo").isMap())
				.andExpect(jsonPath("$[0].verdades").isMap()));
		// Los seis de la segunda tanda son iguales, uno a uno, a los seis de la primera. Y se reparten entre los tipos.
		assertThat(Json.<Object>leer(casos, "$[0].estado")).isEqualTo(Json.<Object>leer(casos, "$[6].estado"));
		assertThat(Json.<Object>leer(casos, "$[5].verdades")).isEqualTo(Json.<Object>leer(casos, "$[11].verdades"));
		assertThat(Json.texto(casos, "$[5].tipoSuceso")).isEqualTo(ACCIDENTE);
		assertThat(Json.texto(casos, "$[4].tipoSuceso")).isEqualTo("PERSONA_INCONSCIENTE");
		generar(0, 1).andExpect(status().isBadRequest());
		generar(501, 1).andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("PB-41 · CP-41-02 · una medición pasa los casos por el modelo y deja, por condición, aciertos, curva de calibración y umbrales sugeridos")
	void mideAlModelo() throws Exception {
		pedir(post("/mediciones"), escenario.admin(), "{}")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.codigo").value("SIN_CASOS_DE_PRUEBA"));
		escenario.protocoloVigente();
		generar(60, 11).andExpect(status().isCreated());

		long id = medir(Json.objeto("origen", "GENERADO", "objetivoDePrecision", 0.9));
		medidor.barrer();

		medicion(id)
				.andExpect(jsonPath("$.estado").value("HECHA"))
				.andExpect(jsonPath("$.casos").value(60))
				.andExpect(jsonPath("$.casosMedidos").value(60))
				.andExpect(jsonPath("$.casosConError").value(0))
				.andExpect(jsonPath("$.modelo").value("decisor-de-prueba"))
				.andExpect(jsonPath("$.objetivoDePrecision").value(0.9))
				.andExpect(jsonPath("$.objetivoDeCobertura").value(0.95))
				.andExpect(jsonPath("$.tokensDeEntrada").value(60 * 120))
				.andExpect(jsonPath("$.terminadaEn").isNotEmpty())
				// Tres condiciones entre los dos tipos de suceso del protocolo de prueba.
				.andExpect(jsonPath("$.resultados.length()").value(3))
				.andExpect(jsonPath("$.resultados[?(@.condicion == 'PERSONA_ATRAPADA')].tipoDeSuceso").value(ACCIDENTE))
				.andExpect(jsonPath("$.resultados[?(@.condicion == 'PERSONA_ATRAPADA')].medido.casos").value(30))
				.andExpect(jsonPath("$.resultados[?(@.condicion == 'PERSONA_ATRAPADA')].medido.actuales.umbralActuar")
						.value(0.8))
				.andExpect(jsonPath("$.resultados[?(@.condicion == 'PERSONA_ATRAPADA')].medido.brier").isNotEmpty())
				// El primer caso es un accidente, con sus dos condiciones: la del otro tipo queda tercera.
				.andExpect(jsonPath("$.resultados[2].condicion").value("NO_RESPIRA"))
				.andExpect(jsonPath("$.resultados[2].enunciado").value("La persona no respira"))
				.andExpect(jsonPath("$.resultados[2].medido.casos").value(30))
				.andExpect(jsonPath("$.resultados[2].medido.curva.length()").value(5))
				.andExpect(jsonPath("$.resultados[2].medido.actuales.umbralActuar").value(0.6));
		pedir(get("/mediciones"), escenario.admin()).andExpect(jsonPath("$.length()").value(1));
		// Ya terminada, otro barrido no la vuelve a correr.
		int consultas = ia.evaluacionesPedidas().size();
		medidor.barrer();
		assertThat(ia.evaluacionesPedidas()).hasSize(consultas).hasSize(60);
	}

	@Test
	@DisplayName("PB-41 · CP-41-03 · si el modelo falla en un caso la medición sigue con los demás; si está mal configurado, se corta")
	void fallasDelModelo() throws Exception {
		escenario.protocoloVigente();
		generar(4, 3).andExpect(status().isCreated());
		ia.fallarEvaluaciones("provider_unavailable", Desenlace.REINTENTAR);
		long conErrores = medir("{}");

		medidor.barrer();

		medicion(conErrores)
				.andExpect(jsonPath("$.estado").value("HECHA"))
				.andExpect(jsonPath("$.casosMedidos").value(0))
				.andExpect(jsonPath("$.casosConError").value(4))
				.andExpect(jsonPath("$.resultados.length()").value(0));

		ia.fallarEvaluaciones("provider_misconfigured", Desenlace.REVISION);
		long cortada = medir("{}");
		medidor.barrer();

		medicion(cortada)
				.andExpect(jsonPath("$.estado").value("FALLIDA"))
				.andExpect(jsonPath("$.error").value("provider_misconfigured"))
				.andExpect(jsonPath("$.resultados").doesNotExist());
	}

	@Test
	@DisplayName("PB-41 · CP-41-04 · un incidente se guarda como caso real con la verdad que confirma la central, y los generados sin medir se pueden borrar")
	void casoRealYLimpieza() throws Exception {
		escenario.protocoloVigente();
		Ciudadano ciudadano = escenario.ciudadano();
		Alerta alerta = escenario.alertarCon(ciudadano, ACCIDENTE);
		String ruta = "/incidentes/" + alerta.incidenteId() + "/caso-de-prueba";
		String verdades = "{\"verdades\": {\"PERSONA_ATRAPADA\": true, \"FUENTES_CONTRADICTORIAS\": false}, "
				+ "\"nota\": \"Lo confirmó la tripulación\"}";

		// Todavía no se evaluó: no hay un estado del caso que guardar.
		pedir(post(ruta), escenario.admin(), verdades).andExpect(status().isConflict());
		evaluador.barrer();
		pedir(post(ruta), escenario.admin(), "{\"verdades\": {\"PERSONA_ATRAPADA\": true}}")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.codigo").value("CONDICION_DESCONOCIDA"));

		pedir(post(ruta), escenario.admin(), verdades)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.origen").value("REAL"))
				.andExpect(jsonPath("$.incidenteId").value(alerta.incidenteId()))
				.andExpect(jsonPath("$.tipoSuceso").value(ACCIDENTE))
				.andExpect(jsonPath("$.nota").value("Lo confirmó la tripulación"))
				.andExpect(jsonPath("$.estado.tipoDeSuceso.codigo").value(ACCIDENTE))
				.andExpect(jsonPath("$.verdades.PERSONA_ATRAPADA").value(true));

		generar(3, 5).andExpect(status().isCreated());
		long id = medir(Json.objeto("origen", "REAL"));
		medidor.barrer();
		medicion(id).andExpect(jsonPath("$.casos").value(1)).andExpect(jsonPath("$.casosMedidos").value(1));
		generar(2, 9).andExpect(status().isCreated());
		long deTodos = medir("{}");
		medidor.barrer();
		medicion(deTodos).andExpect(jsonPath("$.casosMedidos").value(6));
		generar(4, 13).andExpect(status().isCreated());

		// Solo se borran los generados que ninguna medición usó: los cuatro últimos.
		pedir(delete("/banco-de-casos/generados"), escenario.admin())
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.borrados").value(4));
		pedir(get("/banco-de-casos/resumen"), escenario.admin())
				.andExpect(jsonPath("$.GENERADO").value(5))
				.andExpect(jsonPath("$.REAL").value(1));
	}

	@Test
	@Tag("PB-42")
	@DisplayName("PB-42 · CP-42-01 · repetir un incidente vuelve a evaluar lo guardado con el modelo de ahora y dice qué veredicto cambiaría, sin tocar el incidente")
	void repiteUnIncidente() throws Exception {
		escenario.protocoloVigente();
		Alerta alerta = escenario.alertarCon(escenario.ciudadano(), ACCIDENTE);
		evaluador.barrer();
		String bitacoraAntes = cuerpo(pedir(get("/incidentes/" + alerta.incidenteId() + "/bitacora"), escenario.admin()));

		// Como si se cambiara la versión del modelo: ahora ve con claridad a la persona atrapada.
		ia.responderProbabilidad(ATRAPADA, 0.93);
		pedir(post("/incidentes/" + alerta.incidenteId() + "/repeticion"), escenario.admin())
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].pedidaPor").value("TIPO_DE_SUCESO_DECLARADO"))
				.andExpect(jsonPath("$[0].conModelo").value(true))
				.andExpect(jsonPath("$[0].antes[0].resultado").value("EN_DUDA"))
				.andExpect(jsonPath("$[0].ahora[0].condicion").value(ATRAPADA))
				.andExpect(jsonPath("$[0].ahora[0].resultado").value("CUMPLIDA"))
				.andExpect(jsonPath("$[0].ahora[0].probabilidad").value(0.93))
				.andExpect(jsonPath("$[0].cambios.length()").value(1))
				.andExpect(jsonPath("$[0].cambios[0]").value("PERSONA_ATRAPADA: EN_DUDA → CUMPLIDA"));

		// Es solo una comparación: ni un renglón más en la bitácora, ni una unidad más pedida.
		assertThat(cuerpo(pedir(get("/incidentes/" + alerta.incidenteId() + "/bitacora"), escenario.admin())))
				.isEqualTo(bitacoraAntes);
		pedir(get("/incidentes/" + alerta.incidenteId()), escenario.admin())
				.andExpect(jsonPath("$.unidadesRequeridas").value(1));
		pedir(post("/incidentes/" + escenario.alertar(escenario.ciudadano(), -17.70, -63.10).incidenteId()
				+ "/repeticion"), escenario.admin()).andExpect(status().isConflict());
		pedir(post("/incidentes/999999/repeticion"), escenario.admin()).andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("PB-41 · CP-41-05 · el banco, las mediciones y la repetición son solo de la central")
	void soloLaCentral() throws Exception {
		String ciudadano = escenario.ciudadano().token();
		String paramedico = escenario.paramedicoActivado().token();

		pedir(get("/banco-de-casos"), null).andExpect(status().isUnauthorized());
		pedir(get("/banco-de-casos"), ciudadano).andExpect(status().isForbidden());
		pedir(post("/banco-de-casos/generacion"), paramedico, Json.objeto("cantidad", 1)).andExpect(status().isForbidden());
		pedir(post("/mediciones"), ciudadano, "{}").andExpect(status().isForbidden());
		pedir(get("/mediciones"), paramedico).andExpect(status().isForbidden());
		pedir(post("/incidentes/1/repeticion"), ciudadano).andExpect(status().isForbidden());
		pedir(post("/incidentes/1/caso-de-prueba"), paramedico, "{}").andExpect(status().isForbidden());
		pedir(get("/mediciones/999999"), escenario.admin()).andExpect(status().isNotFound());
	}

}
