package com.uem.ambulancias.memoria;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.uem.ambulancias.evidencias.service.FalloDelServicioIa.Desenlace;
import com.uem.ambulancias.memoria.service.IndexadorDeCasos;
import com.uem.ambulancias.soporte.Escenario;
import com.uem.ambulancias.soporte.Escenario.Alerta;
import com.uem.ambulancias.soporte.Escenario.Ciudadano;
import com.uem.ambulancias.soporte.Json;
import com.uem.ambulancias.soporte.PruebaIT;
import com.uem.ambulancias.soporte.ServicioIaFalso;

/**
 * La búsqueda por parecido: los extractos de las normas y la memoria de casos se guardan con un vector, y el agente
 * los encuentra por lo que dicen. Apagada, o si el servicio de IA no responde, todo sigue como antes.
 */
@Tag("PB-44")
class MemoriaYNormaIT extends PruebaIT {

	private static final String TOKEN_DEL_AGENTE = "token-del-agente-de-prueba";
	private static final String ACCIDENTE = "ACCIDENTE_DE_TRANSITO";
	private static final String INCONSCIENTE = "PERSONA_INCONSCIENTE";
	private static final String PRIORIDADES = "el recurso movilizado saldrá de forma inmediata dejando otro servicio "
			+ "de prioridad menor";

	@Autowired
	private IndexadorDeCasos indexador;

	/** Lo que devolvió una herramienta del agente: el JSON que viaja como texto dentro de la respuesta. */
	private String herramienta(String nombre, String argumentos) throws Exception {
		String pedido = "{\"jsonrpc\": \"2.0\", \"id\": 7, \"method\": \"tools/call\", \"params\": {\"name\": \""
				+ nombre + "\", \"arguments\": " + argumentos + "}}";
		return Json.texto(cuerpo(pedir(post("/mcp").header("MCP-Protocol-Version", "2025-06-18"), TOKEN_DEL_AGENTE,
				pedido)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.result.isError").value(false))), "$.result.content[0].text");
	}

	/** Un incidente de ese tipo a esa latitud, con una respuesta del testigo. */
	private Alerta incidente(String tipo, double latitud, String condicion, String valor) throws Exception {
		Ciudadano ciudadano = escenario.ciudadano();
		String respuesta = cuerpo(pedir(post("/alertas"), ciudadano.token(), Json.objeto("latitud", latitud,
				"longitud", Escenario.LONGITUD, "origenUbicacion", "GPS", "tipoSuceso", tipo))
				.andExpect(status().isCreated()));
		Alerta alerta = new Alerta(Json.numero(respuesta, "$.alertaId"), Json.numero(respuesta, "$.incidenteId"));
		pedir(post("/alertas/" + alerta.alertaId() + "/respuestas"), ciudadano.token(),
				"{\"respuestas\": [" + Json.objeto("condicion", condicion, "valor", valor) + "]}")
				.andExpect(status().isOk());
		return alerta;
	}

	private void cerrar(long incidenteId) throws Exception {
		pedir(post("/incidentes/" + incidenteId + "/cierre"), escenario.admin(), Json.objeto("motivo", "OTRO",
				"detalle", "Cierre de prueba.")).andExpect(status().isNoContent());
	}

	@Test
	@DisplayName("PB-44 · CP-44-01 · con la búsqueda por parecido apagada, la norma se ve pero no se puede indexar ni buscar")
	void apagada() throws Exception {
		pedir(get("/norma"), escenario.admin())
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.extractos", greaterThanOrEqualTo(15)))
				.andExpect(jsonPath("$.indexados").value(0))
				.andExpect(jsonPath("$.busquedaEncendida").value(false));
		pedir(post("/norma/indexacion"), escenario.admin())
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.codigo").value("EMBEDDINGS_NO_DISPONIBLES"));
		pedir(get("/norma/busqueda").param("consulta", PRIORIDADES), escenario.admin())
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(0)));
		assertThat(ia.textosEmbebidos()).isEmpty();
	}

	@Test
	@DisplayName("PB-44 · CP-44-02 · al indexar, todos los extractos de la norma quedan con su vector, y volver a indexar no los duplica")
	void indexarLaNorma() throws Exception {
		ia.encenderEmbeddings();

		String estado = cuerpo(pedir(post("/norma/indexacion"), escenario.admin())
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.busquedaEncendida").value(true))
				.andExpect(jsonPath("$.modelos[0]").value(ServicioIaFalso.MODELO_DE_EMBEDDINGS)));
		long extractos = Json.numero(estado, "$.extractos");
		assertThat(Json.numero(estado, "$.indexados")).isEqualTo(extractos);

		pedir(post("/norma/indexacion"), escenario.admin())
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.extractos").value((int) extractos))
				.andExpect(jsonPath("$.indexados").value((int) extractos))
				.andExpect(jsonPath("$.modelos", hasSize(1)));
		assertThat(jdbc.queryForObject("select count(*) from fragmento_de_norma", Long.class)).isEqualTo(extractos);
	}

	@Test
	@DisplayName("PB-44 · CP-44-03 · buscar en la norma devuelve primero el extracto más cercano a la consulta, con su cita")
	void buscarEnLaNorma() throws Exception {
		ia.encenderEmbeddings();
		pedir(post("/norma/indexacion"), escenario.admin()).andExpect(status().isOk());

		pedir(get("/norma/busqueda").param("consulta", PRIORIDADES), escenario.admin())
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].seccion", containsString("prioridades P1 a P4")))
				.andExpect(jsonPath("$[0].titulo", containsString("Sistema Nacional de Emergencias en Salud")))
				.andExpect(jsonPath("$[0].referencia", containsString("433")))
				.andExpect(jsonPath("$[0].pagina").value(57))
				.andExpect(jsonPath("$[0].texto", containsString("código rojo")))
				.andExpect(jsonPath("$[0].similitud", greaterThan(0.25)));
	}

	@Test
	@DisplayName("PB-44 · CP-44-04 · el agente recibe los extractos de la norma en buscar_en_norma, y si el servicio de IA falla la herramienta igual responde")
	void elAgenteBuscaEnLaNorma() throws Exception {
		ia.encenderEmbeddings();
		pedir(post("/norma/indexacion"), escenario.admin()).andExpect(status().isOk());

		String conNorma = herramienta("buscar_en_norma", Json.objeto("consulta", PRIORIDADES));
		assertThat(Json.texto(conNorma, "$.extractosDeLaNorma[0].seccion")).contains("prioridades P1 a P4");
		assertThat(Json.texto(conNorma, "$.extractosDeLaNorma[0].referencia")).contains("433");
		assertThat(Json.texto(conNorma, "$.extractosDeLaNorma[0].texto")).contains("código rojo");
		// Sin protocolo publicado, la parte del protocolo sigue diciendo que no hay.
		assertThat(Json.texto(conNorma, "$.nota")).contains("no tiene un protocolo publicado");

		ia.fallarEmbeddings("provider_unavailable", Desenlace.REINTENTAR);
		String sinNorma = herramienta("buscar_en_norma", Json.objeto("consulta", PRIORIDADES));
		assertThat(Json.textoOpcional(sinNorma, "$.extractosDeLaNorma[0].seccion")).isNull();
		assertThat(Json.texto(sinNorma, "$.nota")).contains("no tiene un protocolo publicado");
	}

	@Test
	@DisplayName("PB-44 · CP-44-05 · un incidente cerrado entra a la memoria de casos, y el agente encuentra primero el que más se parece")
	void casosParecidos() throws Exception {
		escenario.protocoloVigente();
		// Dos casos cerrados: un accidente con una persona atrapada y una persona inconsciente que no respira.
		Alerta atrapado = incidente(ACCIDENTE, Escenario.LATITUD, "PERSONA_ATRAPADA", "SI");
		Alerta inconsciente = incidente(INCONSCIENTE, Escenario.LATITUD - 0.02, "NO_RESPIRA", "SI");
		cerrar(atrapado.incidenteId());
		cerrar(inconsciente.incidenteId());
		// Un caso nuevo, abierto: otro accidente con una persona atrapada.
		Alerta nuevo = incidente(ACCIDENTE, Escenario.LATITUD + 0.02, "PERSONA_ATRAPADA", "SI");
		String argumentos = Json.objeto("incidenteId", nuevo.incidenteId());

		// Con la búsqueda apagada sigue como antes: los últimos del mismo tipo de suceso.
		indexador.barrer();
		pedir(get("/memoria-de-casos"), escenario.admin())
				.andExpect(jsonPath("$.casos").value(0))
				.andExpect(jsonPath("$.busquedaEncendida").value(false));
		String porTipo = herramienta("buscar_casos_parecidos", argumentos);
		assertThat(Json.texto(porTipo, "$.busqueda")).isEqualTo("POR_TIPO_DE_SUCESO");
		assertThat(Json.numero(porTipo, "$.casos[0].incidenteId")).isEqualTo(atrapado.incidenteId());

		ia.encenderEmbeddings();
		indexador.barrer();

		// Solo entran los cerrados: el caso nuevo sigue abierto.
		pedir(get("/memoria-de-casos"), escenario.admin())
				.andExpect(jsonPath("$.casos").value(2))
				.andExpect(jsonPath("$.indexados").value(2))
				.andExpect(jsonPath("$.busquedaEncendida").value(true));
		String porParecido = herramienta("buscar_casos_parecidos", argumentos);
		assertThat(Json.texto(porParecido, "$.busqueda")).isEqualTo("POR_PARECIDO");
		assertThat(Json.numero(porParecido, "$.casos[0].incidenteId")).isEqualTo(atrapado.incidenteId());
		assertThat(Json.texto(porParecido, "$.casos[0].tipoDeSuceso")).isEqualTo(ACCIDENTE);
		assertThat(Json.texto(porParecido, "$.casos[0].comoTermino")).isNotBlank();
		assertThat(Json.<Number>leer(porParecido, "$.casos[0].similitud").doubleValue()).isGreaterThan(0.8);
		// El resumen que se guardó dice la situación en frases, sin datos de nadie.
		assertThat(jdbc.queryForObject("select texto from memoria_del_caso where incidente_id = ?", String.class,
				atrapado.incidenteId()))
				.contains("Tipo de suceso: Accidente de tránsito.")
				.contains("Hay una persona atrapada")
				.doesNotContain("Ciudadano");

		// Si el servicio de IA no responde, la herramienta vuelve a la búsqueda por tipo en vez de fallar.
		ia.fallarEmbeddings("provider_unavailable", Desenlace.REINTENTAR);
		assertThat(Json.texto(herramienta("buscar_casos_parecidos", argumentos), "$.busqueda"))
				.isEqualTo("POR_TIPO_DE_SUCESO");
	}

}
