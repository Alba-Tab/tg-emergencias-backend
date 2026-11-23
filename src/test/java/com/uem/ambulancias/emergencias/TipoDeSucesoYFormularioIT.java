package com.uem.ambulancias.emergencias;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import com.uem.ambulancias.soporte.Escenario;
import com.uem.ambulancias.soporte.Escenario.Alerta;
import com.uem.ambulancias.soporte.Escenario.Ciudadano;
import com.uem.ambulancias.soporte.Escenario.Paramedico;
import com.uem.ambulancias.soporte.Json;
import com.uem.ambulancias.soporte.PruebaIT;

/**
 * El ciudadano marca qué pasó y contesta el formulario de ese tipo de suceso. Nada de esto frena la alerta: con un
 * tipo desconocido o sin protocolo publicado, la alerta sale igual.
 */
@Tag("PB-30")
class TipoDeSucesoYFormularioIT extends PruebaIT {

	private static final String ACCIDENTE = "ACCIDENTE_DE_TRANSITO";
	private static final String INCONSCIENTE = "PERSONA_INCONSCIENTE";

	private static String respuestas(String... condicionesYValores) {
		StringBuilder json = new StringBuilder("{\"respuestas\": [");
		for (int i = 0; i < condicionesYValores.length; i += 2) {
			json.append(i > 0 ? "," : "").append(Json.objeto("condicion", condicionesYValores[i], "valor",
					condicionesYValores[i + 1]));
		}
		return json.append("]}").toString();
	}

	private ResultActions responder(Ciudadano ciudadano, Alerta alerta, String cuerpo) throws Exception {
		return pedir(post("/alertas/" + alerta.alertaId() + "/respuestas"), ciudadano.token(), cuerpo);
	}

	private ResultActions incidente(long id) throws Exception {
		return pedir(get("/incidentes/" + id), escenario.admin()).andExpect(status().isOk());
	}

	private ResultActions bitacora(long incidenteId) throws Exception {
		return pedir(get("/incidentes/" + incidenteId + "/bitacora"), escenario.admin()).andExpect(status().isOk());
	}

	@Test
	@DisplayName("PB-30 · CP-30-01 · la alerta sale con el tipo de suceso que marcó el ciudadano y el incidente lo conserva")
	void alertaConTipo() throws Exception {
		escenario.protocoloVigente();
		Ciudadano ciudadano = escenario.ciudadano();

		String respuesta = cuerpo(pedir(post("/alertas"), ciudadano.token(), Json.objeto("latitud", Escenario.LATITUD,
				"longitud", Escenario.LONGITUD, "origenUbicacion", "GPS", "tipoSuceso", "  " + ACCIDENTE + " "))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.tipoSuceso").value(ACCIDENTE)));
		long incidenteId = Json.numero(respuesta, "$.incidenteId");

		incidente(incidenteId).andExpect(jsonPath("$.tipoSuceso").value(ACCIDENTE));
		bitacora(incidenteId)
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].tipo").value("ALERTA_RECIBIDA"))
				.andExpect(jsonPath("$[1].tipo").value("TIPO_DE_SUCESO_DECLARADO"))
				.andExpect(jsonPath("$[1].origen").value("CIUDADANO"))
				.andExpect(jsonPath("$[1].datos.codigo").value(ACCIDENTE))
				.andExpect(jsonPath("$[1].datos.aceptado").value(true))
				.andExpect(jsonPath("$[1].datos.tipoDelIncidente").value(ACCIDENTE));
	}

	@Test
	@DisplayName("PB-30 · CP-30-02 · un tipo que el protocolo no conoce no frena la alerta: sale sin tipo y queda anotado")
	void tipoDesconocidoNoFrena() throws Exception {
		Ciudadano ciudadano = escenario.ciudadano();

		// Todavía no hay ningún protocolo publicado.
		String sinProtocolo = cuerpo(pedir(post("/alertas"), ciudadano.token(), Json.objeto("latitud",
				Escenario.LATITUD, "longitud", Escenario.LONGITUD, "origenUbicacion", "GPS", "tipoSuceso", ACCIDENTE))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.tipoSuceso").doesNotExist()));
		long incidenteId = Json.numero(sinProtocolo, "$.incidenteId");

		escenario.protocoloVigente();
		pedir(post("/alertas"), escenario.ciudadano().token(), Json.objeto("latitud", Escenario.LATITUD, "longitud",
				Escenario.LONGITUD, "origenUbicacion", "GPS", "tipoSuceso", "INCENDIO"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.incidenteId").value(incidenteId))
				.andExpect(jsonPath("$.tipoSuceso").doesNotExist());

		incidente(incidenteId).andExpect(jsonPath("$.tipoSuceso").doesNotExist());
		bitacora(incidenteId)
				.andExpect(jsonPath("$.length()").value(4))
				.andExpect(jsonPath("$[1].tipo").value("TIPO_DE_SUCESO_DECLARADO"))
				.andExpect(jsonPath("$[1].datos.codigo").value(ACCIDENTE))
				.andExpect(jsonPath("$[1].datos.aceptado").value(false))
				.andExpect(jsonPath("$[1].datos.tipoDelIncidente").doesNotExist())
				.andExpect(jsonPath("$[3].datos.codigo").value("INCENDIO"))
				.andExpect(jsonPath("$[3].datos.aceptado").value(false));
	}

	@Test
	@DisplayName("PB-30 · CP-30-03 · una alerta sin tipo de suceso sigue funcionando como siempre")
	void sinTipo() throws Exception {
		escenario.protocoloVigente();

		Alerta alerta = escenario.alertar(escenario.ciudadano());

		incidente(alerta.incidenteId()).andExpect(jsonPath("$.tipoSuceso").doesNotExist());
		bitacora(alerta.incidenteId())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].tipo").value("ALERTA_RECIBIDA"));
	}

	@Test
	@DisplayName("PB-30 · CP-30-04 · el incidente se queda con el primer tipo declarado; otro distinto queda anotado")
	void valeElPrimerTipo() throws Exception {
		escenario.protocoloVigente();
		Alerta primera = escenario.alertarCon(escenario.ciudadano(), ACCIDENTE);

		pedir(post("/alertas"), escenario.ciudadano().token(), Json.objeto("latitud", Escenario.LATITUD, "longitud",
				Escenario.LONGITUD, "origenUbicacion", "GPS", "tipoSuceso", INCONSCIENTE))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.incidenteId").value(primera.incidenteId()))
				.andExpect(jsonPath("$.tipoSuceso").value(INCONSCIENTE));

		incidente(primera.incidenteId())
				.andExpect(jsonPath("$.cantidadAlertas").value(2))
				.andExpect(jsonPath("$.tipoSuceso").value(ACCIDENTE));
		bitacora(primera.incidenteId())
				.andExpect(jsonPath("$.length()").value(4))
				.andExpect(jsonPath("$[3].tipo").value("TIPO_DE_SUCESO_DECLARADO"))
				.andExpect(jsonPath("$[3].datos.codigo").value(INCONSCIENTE))
				.andExpect(jsonPath("$[3].datos.aceptado").value(true))
				.andExpect(jsonPath("$[3].datos.tipoDelIncidente").value(ACCIDENTE));
	}

	@Test
	@DisplayName("PB-30 · CP-30-05 · quien no marcó el tipo al emitir lo puede marcar después, mientras espera")
	void tipoDespues() throws Exception {
		escenario.protocoloVigente();
		Ciudadano ciudadano = escenario.ciudadano();
		Alerta alerta = escenario.alertar(ciudadano);

		pedir(post("/alertas/" + alerta.alertaId() + "/detalles"), ciudadano.token(),
				Json.objeto("tipoSuceso", INCONSCIENTE))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tipoSuceso").value(INCONSCIENTE));

		incidente(alerta.incidenteId()).andExpect(jsonPath("$.tipoSuceso").value(INCONSCIENTE));
		// Solo llegó el tipo: no hay renglón de detalles.
		bitacora(alerta.incidenteId())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[1].tipo").value("TIPO_DE_SUCESO_DECLARADO"));
	}

	@Test
	@DisplayName("PB-30 · CP-30-06 · el ciudadano contesta el formulario y puede corregir una respuesta: vale la última")
	void contestaElFormulario() throws Exception {
		escenario.protocoloVigente();
		Ciudadano ciudadano = escenario.ciudadano();
		Alerta alerta = escenario.alertarCon(ciudadano, ACCIDENTE);

		pedir(get("/alertas/" + alerta.alertaId() + "/respuestas"), ciudadano.token())
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));

		responder(ciudadano, alerta, respuestas("PERSONA_ATRAPADA", "NO_SE"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].condicion").value("PERSONA_ATRAPADA"))
				.andExpect(jsonPath("$[0].valor").value("NO_SE"));
		responder(ciudadano, alerta, respuestas("PERSONA_ATRAPADA", "SI"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].valor").value("SI"));

		pedir(get("/alertas/" + alerta.alertaId() + "/respuestas"), ciudadano.token())
				.andExpect(jsonPath("$[0].condicion").value("PERSONA_ATRAPADA"))
				.andExpect(jsonPath("$[0].valor").value("SI"));
		// La bitácora conserva las dos respuestas, en orden: la corrección no borra lo que se dijo antes.
		bitacora(alerta.incidenteId())
				.andExpect(jsonPath("$.length()").value(4))
				.andExpect(jsonPath("$[2].tipo").value("RESPUESTA_DEL_TESTIGO"))
				.andExpect(jsonPath("$[2].origen").value("CIUDADANO"))
				.andExpect(jsonPath("$[2].alertaId").value(alerta.alertaId()))
				.andExpect(jsonPath("$[2].datos.condicion").value("PERSONA_ATRAPADA"))
				.andExpect(jsonPath("$[2].datos.valor").value("NO_SE"))
				.andExpect(jsonPath("$[3].datos.valor").value("SI"));
	}

	@Test
	@DisplayName("PB-30 · CP-30-07 · solo se contestan las condiciones que el formulario pregunta, y se anotan todas o ninguna")
	void soloLoQueSePregunta() throws Exception {
		escenario.protocoloVigente();
		Ciudadano ciudadano = escenario.ciudadano();
		Alerta alerta = escenario.alertarCon(ciudadano, ACCIDENTE);

		// Esta condición existe, pero no tiene pregunta: se deduce de la evidencia.
		responder(ciudadano, alerta, respuestas("PERSONA_ATRAPADA", "SI", "FUENTES_CONTRADICTORIAS", "SI"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.codigo").value("CONDICION_DESCONOCIDA"));
		// Y esta es de otro tipo de suceso.
		responder(ciudadano, alerta, respuestas("NO_RESPIRA", "SI"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.codigo").value("CONDICION_DESCONOCIDA"));

		pedir(get("/alertas/" + alerta.alertaId() + "/respuestas"), ciudadano.token())
				.andExpect(jsonPath("$.length()").value(0));
		bitacora(alerta.incidenteId()).andExpect(jsonPath("$.length()").value(2));
	}

	@Test
	@DisplayName("PB-30 · CP-30-08 · sin un tipo de suceso declarado no hay formulario que contestar")
	void sinTipoNoHayFormulario() throws Exception {
		escenario.protocoloVigente();
		Ciudadano ciudadano = escenario.ciudadano();
		Alerta alerta = escenario.alertar(ciudadano);

		responder(ciudadano, alerta, respuestas("PERSONA_ATRAPADA", "SI"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.codigo").value("SIN_TIPO_DE_SUCESO"));
	}

	@Test
	@DisplayName("PB-30 · CP-30-09 · solo el dueño de la alerta contesta, y con respuestas válidas")
	void quienContesta() throws Exception {
		escenario.protocoloVigente();
		Ciudadano ciudadano = escenario.ciudadano();
		Alerta alerta = escenario.alertarCon(ciudadano, ACCIDENTE);
		Ciudadano otro = escenario.ciudadano();

		responder(otro, alerta, respuestas("PERSONA_ATRAPADA", "SI"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.codigo").value("ALERTA_AJENA"));
		pedir(get("/alertas/" + alerta.alertaId() + "/respuestas"), otro.token())
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.codigo").value("ALERTA_AJENA"));
		pedir(post("/alertas/999999/respuestas"), ciudadano.token(), respuestas("PERSONA_ATRAPADA", "SI"))
				.andExpect(status().isNotFound());
		responder(ciudadano, alerta, "{\"respuestas\": []}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.codigo").value("VALIDACION"));
		responder(ciudadano, alerta, respuestas("PERSONA_ATRAPADA", "TAL_VEZ"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.codigo").value("VALIDACION"));
		pedir(post("/alertas/" + alerta.alertaId() + "/respuestas"), escenario.admin(),
				respuestas("PERSONA_ATRAPADA", "SI")).andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("PB-30 · CP-30-10 · cuando una unidad ya llegó al lugar, o el caso se cerró, el formulario ya no se contesta")
	void hastaQueLlegaLaUnidad() throws Exception {
		escenario.protocoloVigente();
		Ciudadano ciudadano = escenario.ciudadano();
		Alerta alerta = escenario.alertarCon(ciudadano, ACCIDENTE);
		Paramedico paramedico = escenario.paramedicoEnTurno();
		long atencion = escenario.tomar(paramedico, alerta.incidenteId());

		// Con la unidad en camino todavía sirve lo que diga el testigo.
		responder(ciudadano, alerta, respuestas("PERSONA_ATRAPADA", "SI")).andExpect(status().isOk());

		escenario.marcar(paramedico, atencion, "llegada");

		responder(ciudadano, alerta, respuestas("PERSONA_ATRAPADA", "NO"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.codigo").value("DETALLES_NO_EDITABLES"));

		Ciudadano otro = escenario.ciudadano();
		Alerta retirada = escenario.alertar(otro, Escenario.LATITUD + 30 * Escenario.CIEN_METROS, Escenario.LONGITUD);
		pedir(post("/alertas/" + retirada.alertaId() + "/cancelacion"), otro.token(),
				Json.objeto("motivo", "FALSA_ALARMA")).andExpect(status().isOk());
		responder(otro, retirada, respuestas("PERSONA_ATRAPADA", "SI"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.codigo").value("DETALLES_NO_EDITABLES"));
	}

	@Test
	@DisplayName("PB-30 · CP-30-11 · el incidente se sigue atendiendo con su protocolo aunque la central publique otro")
	void conservaSuProtocolo() throws Exception {
		escenario.protocoloVigente();
		Ciudadano ciudadano = escenario.ciudadano();
		Alerta alerta = escenario.alertarCon(ciudadano, ACCIDENTE);

		escenario.publicarProtocolo("""
				{"tipos": [{"codigo": "CAIDA", "nombre": "Caída"}]}
				""");

		// El formulario del incidente sigue siendo el de la versión con la que se declaró su tipo.
		responder(ciudadano, alerta, respuestas("PERSONA_ATRAPADA", "SI"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].valor").value("SI"));
		incidente(alerta.incidenteId()).andExpect(jsonPath("$.tipoSuceso").value(ACCIDENTE));
		// Una alerta nueva ya se valida contra la versión nueva.
		pedir(post("/alertas"), escenario.ciudadano().token(), Json.objeto("latitud",
				Escenario.LATITUD + 30 * Escenario.CIEN_METROS, "longitud", Escenario.LONGITUD, "origenUbicacion",
				"GPS", "tipoSuceso", ACCIDENTE))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.tipoSuceso").doesNotExist());
	}

}
