package com.uem.ambulancias.protocolo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.uem.ambulancias.soporte.Escenario;
import com.uem.ambulancias.soporte.Json;
import com.uem.ambulancias.soporte.PruebaIT;

@Tag("PB-31")
class AdministracionDelProtocoloIT extends PruebaIT {

	/** Un solo tipo sin condiciones, con el código que se pida. */
	private static String unTipo(String codigo) {
		return """
				{"tipos": [{"codigo": "%s", "nombre": "Un tipo de suceso"}]}
				""".formatted(codigo);
	}

	/** Un tipo con una sola condición, con los umbrales que se pidan. */
	private static String conUmbrales(String actuar, String preguntar) {
		return """
				{"tipos": [{"codigo": "ACCIDENTE", "nombre": "Accidente", "condiciones": [
				  {"codigo": "ATRAPADO", "enunciado": "Hay una persona atrapada",
				   "umbralActuar": %s, "umbralPreguntar": %s}]}]}
				""".formatted(actuar, preguntar);
	}

	@Test
	@DisplayName("PB-31 · CP-31-01 · la central abre un borrador vacío, y no puede abrir otro mientras siga abierto")
	void abreElBorrador() throws Exception {
		String admin = escenario.admin();

		pedir(post("/protocolos/borrador"), admin)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").isNumber())
				.andExpect(jsonPath("$.version").value(1))
				.andExpect(jsonPath("$.estado").value("BORRADOR"))
				.andExpect(jsonPath("$.publicadoEn").doesNotExist())
				.andExpect(jsonPath("$.tipos.length()").value(0));

		pedir(post("/protocolos/borrador"), admin)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.codigo").value("PROTOCOLO_BORRADOR_EXISTENTE"));
	}

	@Test
	@DisplayName("PB-31 · CP-31-02 · guardar el borrador reemplaza todo su contenido y respeta el orden")
	void guardaElContenido() throws Exception {
		String admin = escenario.admin();
		pedir(post("/protocolos/borrador"), admin).andExpect(status().isCreated());

		pedir(put("/protocolos/borrador"), admin, Escenario.PROTOCOLO_DE_EJEMPLO)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.estado").value("BORRADOR"))
				.andExpect(jsonPath("$.nota").value("Protocolo de prueba"))
				.andExpect(jsonPath("$.tipos.length()").value(2))
				.andExpect(jsonPath("$.tipos[0].codigo").value("ACCIDENTE_DE_TRANSITO"))
				.andExpect(jsonPath("$.tipos[0].nombre").value("Accidente de tránsito"))
				.andExpect(jsonPath("$.tipos[0].condiciones.length()").value(2))
				.andExpect(jsonPath("$.tipos[0].condiciones[0].codigo").value("PERSONA_ATRAPADA"))
				.andExpect(jsonPath("$.tipos[0].condiciones[0].pregunta").value("¿Hay alguien atrapado?"))
				.andExpect(jsonPath("$.tipos[0].condiciones[0].umbralActuar").value(0.8))
				.andExpect(jsonPath("$.tipos[0].condiciones[0].umbralPreguntar").value(0.4))
				.andExpect(jsonPath("$.tipos[0].condiciones[0].unidadesAdicionales").value(1))
				.andExpect(jsonPath("$.tipos[0].condiciones[0].tipoMinimo").value("II"))
				.andExpect(jsonPath("$.tipos[0].condiciones[0].cita").value("Reglamento de prueba, artículo 1"))
				.andExpect(jsonPath("$.tipos[0].condiciones[1].codigo").value("FUENTES_CONTRADICTORIAS"))
				.andExpect(jsonPath("$.tipos[0].condiciones[1].pregunta").doesNotExist())
				.andExpect(jsonPath("$.tipos[0].condiciones[1].unidadesAdicionales").value(0))
				.andExpect(jsonPath("$.tipos[0].condiciones[1].derivaAPersona").value(true))
				.andExpect(jsonPath("$.tipos[1].codigo").value("PERSONA_INCONSCIENTE"));

		// Guardar otra vez con un código que ya estaba no choca: lo anterior se borra antes de cargar lo nuevo.
		pedir(put("/protocolos/borrador"), admin, unTipo("ACCIDENTE_DE_TRANSITO"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.nota").doesNotExist())
				.andExpect(jsonPath("$.tipos.length()").value(1))
				.andExpect(jsonPath("$.tipos[0].nombre").value("Un tipo de suceso"))
				.andExpect(jsonPath("$.tipos[0].condiciones.length()").value(0));

		pedir(get("/protocolos/borrador"), admin)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tipos.length()").value(1));
	}

	@Test
	@DisplayName("PB-31 · CP-31-03 · al publicar, el borrador pasa a ser el protocolo vigente y deja de ser editable")
	void publica() throws Exception {
		String admin = escenario.admin();
		pedir(post("/protocolos/borrador"), admin).andExpect(status().isCreated());
		pedir(put("/protocolos/borrador"), admin, Escenario.PROTOCOLO_DE_EJEMPLO).andExpect(status().isOk());

		pedir(post("/protocolos/borrador/publicacion"), admin)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.estado").value("VIGENTE"))
				.andExpect(jsonPath("$.publicadoEn").isNotEmpty());

		pedir(get("/protocolos/vigente"), admin)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.version").value(1))
				.andExpect(jsonPath("$.tipos.length()").value(2));
		pedir(get("/protocolos/borrador"), admin)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));
		pedir(put("/protocolos/borrador"), admin, unTipo("OTRO")).andExpect(status().isNotFound());
		pedir(post("/protocolos/borrador/publicacion"), admin).andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("PB-31 · CP-31-04 · un borrador sin tipos de suceso no se publica")
	void noPublicaVacio() throws Exception {
		String admin = escenario.admin();
		pedir(get("/protocolos/vigente"), admin).andExpect(status().isNotFound());
		pedir(post("/protocolos/borrador"), admin).andExpect(status().isCreated());

		pedir(post("/protocolos/borrador/publicacion"), admin)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.codigo").value("PROTOCOLO_INVALIDO"));

		pedir(get("/protocolos/vigente"), admin).andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("PB-31 · CP-31-05 · la versión siguiente arranca de la vigente y, al publicarse, la retira")
	void versionSiguiente() throws Exception {
		String admin = escenario.admin();
		escenario.protocoloVigente();

		pedir(post("/protocolos/borrador"), admin)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.version").value(2))
				.andExpect(jsonPath("$.estado").value("BORRADOR"))
				.andExpect(jsonPath("$.nota").value("Protocolo de prueba"))
				.andExpect(jsonPath("$.tipos.length()").value(2))
				.andExpect(jsonPath("$.tipos[0].condiciones[0].codigo").value("PERSONA_ATRAPADA"))
				.andExpect(jsonPath("$.tipos[1].codigo").value("PERSONA_INCONSCIENTE"));

		// Mientras el borrador no se publique, sigue rigiendo la versión 1.
		pedir(get("/protocolos/vigente"), admin).andExpect(jsonPath("$.version").value(1));

		pedir(put("/protocolos/borrador"), admin, unTipo("CAIDA")).andExpect(status().isOk());
		pedir(post("/protocolos/borrador/publicacion"), admin)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.version").value(2));

		pedir(get("/protocolos/vigente"), admin)
				.andExpect(jsonPath("$.version").value(2))
				.andExpect(jsonPath("$.tipos[0].codigo").value("CAIDA"));
		pedir(get("/protocolos"), admin)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].version").value(2))
				.andExpect(jsonPath("$[0].estado").value("VIGENTE"))
				.andExpect(jsonPath("$[0].cantidadDeTipos").value(1))
				.andExpect(jsonPath("$[1].version").value(1))
				.andExpect(jsonPath("$[1].estado").value("RETIRADO"))
				.andExpect(jsonPath("$[1].cantidadDeTipos").value(2));

		// La versión retirada se conserva entera: los incidentes que la usaron la siguen señalando.
		long primera = Json.numero(cuerpo(pedir(get("/protocolos"), admin)), "$[1].id");
		pedir(get("/protocolos/" + primera), admin)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tipos.length()").value(2));
		pedir(get("/protocolos/999999"), admin).andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("PB-31 · CP-31-06 · un contenido inválido se rechaza y el borrador queda como estaba")
	void validaElContenido() throws Exception {
		String admin = escenario.admin();
		pedir(post("/protocolos/borrador"), admin).andExpect(status().isCreated());
		pedir(put("/protocolos/borrador"), admin, unTipo("ACCIDENTE")).andExpect(status().isOk());

		pedir(put("/protocolos/borrador"), admin, unTipo("accidente de tránsito"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.codigo").value("VALIDACION"))
				.andExpect(jsonPath("$.errores[0].campo").value("tipos[0].codigo"));
		pedir(put("/protocolos/borrador"), admin, "{}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.codigo").value("VALIDACION"));
		pedir(put("/protocolos/borrador"), admin, conUmbrales("1.5", "0.4"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errores[0].campo").value("tipos[0].condiciones[0].umbralActuar"));
		pedir(put("/protocolos/borrador"), admin, conUmbrales("0.4", "0.8"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.codigo").value("PROTOCOLO_INVALIDO"));
		pedir(put("/protocolos/borrador"), admin, """
				{"tipos": [{"codigo": "CAIDA", "nombre": "Caída"}, {"codigo": "CAIDA", "nombre": "Otra caída"}]}
				""")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.codigo").value("PROTOCOLO_INVALIDO"));
		pedir(put("/protocolos/borrador"), admin, """
				{"tipos": [{"codigo": "CAIDA", "nombre": "Caída", "condiciones": [
				  {"codigo": "FRACTURA", "enunciado": "Hay una fractura", "umbralActuar": 0.8, "umbralPreguntar": 0.4},
				  {"codigo": "FRACTURA", "enunciado": "Hay otra fractura", "umbralActuar": 0.8, "umbralPreguntar": 0.4}
				]}]}
				""")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.codigo").value("PROTOCOLO_INVALIDO"));

		pedir(get("/protocolos/borrador"), admin)
				.andExpect(jsonPath("$.tipos.length()").value(1))
				.andExpect(jsonPath("$.tipos[0].codigo").value("ACCIDENTE"));
	}

	@Test
	@DisplayName("PB-31 · CP-31-07 · descartar el borrador no toca el protocolo vigente")
	void descarta() throws Exception {
		String admin = escenario.admin();
		escenario.protocoloVigente();
		pedir(post("/protocolos/borrador"), admin).andExpect(status().isCreated());

		pedir(delete("/protocolos/borrador"), admin).andExpect(status().isNoContent());

		pedir(get("/protocolos/borrador"), admin).andExpect(status().isNotFound());
		pedir(delete("/protocolos/borrador"), admin).andExpect(status().isNotFound());
		pedir(get("/protocolos/vigente"), admin)
				.andExpect(jsonPath("$.version").value(1))
				.andExpect(jsonPath("$.tipos.length()").value(2));
		pedir(get("/protocolos"), admin).andExpect(jsonPath("$.length()").value(1));
		// El número de versión descartado se vuelve a usar: nunca llegó a regir.
		pedir(post("/protocolos/borrador"), admin).andExpect(jsonPath("$.version").value(2));
	}

	@Test
	@DisplayName("PB-31 · CP-31-08 · solo la central administra el protocolo")
	void soloLaCentral() throws Exception {
		escenario.protocoloVigente();
		String ciudadano = escenario.ciudadano().token();
		String paramedico = escenario.paramedicoActivado().token();

		pedir(get("/protocolos"), ciudadano).andExpect(status().isForbidden());
		pedir(get("/protocolos/vigente"), paramedico).andExpect(status().isForbidden());
		pedir(post("/protocolos/borrador"), ciudadano).andExpect(status().isForbidden());
		pedir(put("/protocolos/borrador"), paramedico, unTipo("CAIDA")).andExpect(status().isForbidden());
		pedir(post("/protocolos/borrador/publicacion"), ciudadano).andExpect(status().isForbidden());
		pedir(delete("/protocolos/borrador"), paramedico).andExpect(status().isForbidden());
		pedir(get("/protocolos"), null).andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("PB-31 · CP-31-09 · la app arma el formulario con los tipos y las preguntas del protocolo vigente")
	void formulario() throws Exception {
		String ciudadano = escenario.ciudadano().token();

		// Sin protocolo publicado no hay tipos que ofrecer, y la app sigue funcionando sin ellos.
		pedir(get("/formulario/tipos"), ciudadano)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
		pedir(get("/formulario/tipos/ACCIDENTE_DE_TRANSITO/preguntas"), ciudadano).andExpect(status().isNotFound());

		escenario.protocoloVigente();

		pedir(get("/formulario/tipos"), ciudadano)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].codigo").value("ACCIDENTE_DE_TRANSITO"))
				.andExpect(jsonPath("$[0].nombre").value("Accidente de tránsito"))
				.andExpect(jsonPath("$[1].codigo").value("PERSONA_INCONSCIENTE"));
		// Solo las condiciones que tienen pregunta: la otra se deduce de la evidencia.
		pedir(get("/formulario/tipos/ACCIDENTE_DE_TRANSITO/preguntas"), ciudadano)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].condicion").value("PERSONA_ATRAPADA"))
				.andExpect(jsonPath("$[0].pregunta").value("¿Hay alguien atrapado?"));
		pedir(get("/formulario/tipos/NO_EXISTE/preguntas"), ciudadano)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));

		pedir(get("/formulario/tipos"), escenario.paramedicoActivado().token()).andExpect(status().isOk());
		pedir(get("/formulario/tipos"), escenario.admin()).andExpect(status().isOk());
		pedir(get("/formulario/tipos"), null).andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("PB-31 · CP-31-10 · un borrador no le cambia el formulario al ciudadano hasta que se publica")
	void elBorradorNoSeVe() throws Exception {
		String admin = escenario.admin();
		String ciudadano = escenario.ciudadano().token();
		escenario.protocoloVigente();
		pedir(post("/protocolos/borrador"), admin).andExpect(status().isCreated());
		pedir(put("/protocolos/borrador"), admin, unTipo("CAIDA")).andExpect(status().isOk());

		pedir(get("/formulario/tipos"), ciudadano)
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].codigo").value("ACCIDENTE_DE_TRANSITO"));

		pedir(post("/protocolos/borrador/publicacion"), admin).andExpect(status().isOk());

		pedir(get("/formulario/tipos"), ciudadano)
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].codigo").value("CAIDA"));
	}

}
