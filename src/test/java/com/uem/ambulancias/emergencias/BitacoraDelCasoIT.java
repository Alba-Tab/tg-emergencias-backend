package com.uem.ambulancias.emergencias;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import com.uem.ambulancias.evidencias.service.AnalizadorDeEvidencias;
import com.uem.ambulancias.soporte.Escenario;
import com.uem.ambulancias.soporte.Escenario.Alerta;
import com.uem.ambulancias.soporte.Escenario.Ciudadano;
import com.uem.ambulancias.soporte.Escenario.Evidencia;
import com.uem.ambulancias.soporte.Escenario.Paramedico;
import com.uem.ambulancias.soporte.Json;
import com.uem.ambulancias.soporte.PruebaIT;

/**
 * La bitácora del caso: lo que llega a un incidente queda anotado en orden, con quién lo aportó, y la central lo
 * puede leer entero.
 */
@Tag("PB-32")
class BitacoraDelCasoIT extends PruebaIT {

	@Autowired
	private AnalizadorDeEvidencias analizador;

	private ResultActions bitacora(long incidenteId) throws Exception {
		return pedir(get("/incidentes/" + incidenteId + "/bitacora"), escenario.admin()).andExpect(status().isOk());
	}

	@Test
	@DisplayName("PB-32 · CP-32-01 · la alerta queda anotada con lo que el ciudadano declaró al emitirla")
	void anotaLaAlerta() throws Exception {
		Ciudadano ciudadano = escenario.ciudadano();
		String respuesta = cuerpo(pedir(post("/alertas"), ciudadano.token(), Json.objeto("latitud",
				Escenario.LATITUD, "longitud", Escenario.LONGITUD, "origenUbicacion", "MANUAL", "cantidadAfectados", 2,
				"descripcion", "  Choque de dos autos  ")).andExpect(status().isCreated()));
		long alertaId = Json.numero(respuesta, "$.alertaId");

		bitacora(Json.numero(respuesta, "$.incidenteId"))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].id").isNumber())
				.andExpect(jsonPath("$[0].tipo").value("ALERTA_RECIBIDA"))
				.andExpect(jsonPath("$[0].origen").value("CIUDADANO"))
				.andExpect(jsonPath("$[0].alertaId").value(alertaId))
				.andExpect(jsonPath("$[0].ocurridoEn").isNotEmpty())
				.andExpect(jsonPath("$[0].registradoEn").isNotEmpty())
				.andExpect(jsonPath("$[0].datos.incidenteNuevo").value(true))
				.andExpect(jsonPath("$[0].datos.origenUbicacion").value("MANUAL"))
				.andExpect(jsonPath("$[0].datos.cantidadAfectados").value(2))
				.andExpect(jsonPath("$[0].datos.descripcion").value("Choque de dos autos"));
	}

	@Test
	@DisplayName("PB-32 · CP-32-02 · lo que el ciudadano no declaró no figura en el renglón")
	void loQueNoSeSabeNoFigura() throws Exception {
		Alerta alerta = escenario.alertar(escenario.ciudadano());

		bitacora(alerta.incidenteId())
				.andExpect(jsonPath("$[0].datos.origenUbicacion").value("GPS"))
				.andExpect(jsonPath("$[0].datos.cantidadAfectados").doesNotExist())
				.andExpect(jsonPath("$[0].datos.descripcion").doesNotExist());
	}

	@Test
	@DisplayName("PB-32 · CP-32-03 · las alertas que se suman al mismo incidente se anotan en orden")
	void variasAlertas() throws Exception {
		Alerta primera = escenario.alertar(escenario.ciudadano());
		Alerta segunda = escenario.alertar(escenario.ciudadano());

		bitacora(primera.incidenteId())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].alertaId").value(primera.alertaId()))
				.andExpect(jsonPath("$[0].datos.incidenteNuevo").value(true))
				.andExpect(jsonPath("$[1].alertaId").value(segunda.alertaId()))
				.andExpect(jsonPath("$[1].datos.incidenteNuevo").value(false));
	}

	@Test
	@DisplayName("PB-32 · CP-32-04 · completar los detalles agrega un renglón solo con lo que llegó")
	void anotaLosDetalles() throws Exception {
		Ciudadano ciudadano = escenario.ciudadano();
		Alerta alerta = escenario.alertar(ciudadano);

		pedir(post("/alertas/" + alerta.alertaId() + "/detalles"), ciudadano.token(),
				Json.objeto("cantidadAfectados", 3)).andExpect(status().isOk());
		// Un pedido vacío no cambia nada y tampoco deja renglón.
		pedir(post("/alertas/" + alerta.alertaId() + "/detalles"), ciudadano.token(), "{}")
				.andExpect(status().isOk());

		bitacora(alerta.incidenteId())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[1].tipo").value("DETALLES_COMPLETADOS"))
				.andExpect(jsonPath("$[1].origen").value("CIUDADANO"))
				.andExpect(jsonPath("$[1].alertaId").value(alerta.alertaId()))
				.andExpect(jsonPath("$[1].datos.cantidadAfectados").value(3))
				.andExpect(jsonPath("$[1].datos.descripcion").doesNotExist());
	}

	@Test
	@DisplayName("PB-32 · CP-32-05 · retirar el pedido queda anotado con su motivo, y la bitácora sigue ahí al cerrarse el caso")
	void anotaElRetiro() throws Exception {
		Ciudadano ciudadano = escenario.ciudadano();
		Alerta alerta = escenario.alertar(ciudadano);

		pedir(post("/alertas/" + alerta.alertaId() + "/cancelacion"), ciudadano.token(),
				Json.objeto("motivo", "FALSA_ALARMA", "emisorEsPaciente", true)).andExpect(status().isOk());

		pedir(get("/incidentes/" + alerta.incidenteId()), escenario.admin())
				.andExpect(jsonPath("$.estado").value("CANCELADO"));
		bitacora(alerta.incidenteId())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].tipo").value("ALERTA_RECIBIDA"))
				.andExpect(jsonPath("$[1].tipo").value("ALERTA_CANCELADA"))
				.andExpect(jsonPath("$[1].origen").value("CIUDADANO"))
				.andExpect(jsonPath("$[1].datos.motivo").value("FALSA_ALARMA"))
				.andExpect(jsonPath("$[1].datos.emisorEsPaciente").value(true));
	}

	@Test
	@DisplayName("PB-32 · CP-32-06 · cada evidencia analizada queda anotada con dónde encontrar su análisis")
	void anotaElAnalisis() throws Exception {
		Ciudadano ciudadano = escenario.ciudadano();
		Alerta alerta = escenario.alertar(ciudadano);
		Evidencia foto = escenario.evidenciaSubida(ciudadano, alerta.alertaId(), "image/jpeg");

		// Mientras no se analice, la evidencia no aporta nada al caso.
		bitacora(alerta.incidenteId()).andExpect(jsonPath("$.length()").value(1));

		analizador.barrer();

		bitacora(alerta.incidenteId())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[1].tipo").value("EVIDENCIA_ANALIZADA"))
				.andExpect(jsonPath("$[1].origen").value("ANALISIS"))
				.andExpect(jsonPath("$[1].alertaId").value(alerta.alertaId()))
				.andExpect(jsonPath("$[1].datos.evidenciaId").value(foto.id()))
				.andExpect(jsonPath("$[1].datos.analisisId").isNumber())
				.andExpect(jsonPath("$[1].datos.modalidad").isNotEmpty());

		// Volver a barrer no repite el renglón: el análisis ya está hecho.
		analizador.barrer();
		bitacora(alerta.incidenteId()).andExpect(jsonPath("$.length()").value(2));
	}

	@Test
	@DisplayName("PB-32 · CP-32-07 · cada incidente tiene su propia bitácora")
	void porIncidente() throws Exception {
		Alerta cerca = escenario.alertar(escenario.ciudadano());
		Alerta lejos = escenario.alertar(escenario.ciudadano(), Escenario.LATITUD + 30 * Escenario.CIEN_METROS,
				Escenario.LONGITUD);

		bitacora(cerca.incidenteId())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].alertaId").value(cerca.alertaId()));
		bitacora(lejos.incidenteId())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].alertaId").value(lejos.alertaId()));
	}

	@Test
	@DisplayName("PB-32 · CP-32-08 · solo la central lee la bitácora")
	void soloLaCentral() throws Exception {
		Ciudadano ciudadano = escenario.ciudadano();
		Alerta alerta = escenario.alertar(ciudadano);
		Paramedico paramedico = escenario.paramedicoEnTurno();
		String ruta = "/incidentes/" + alerta.incidenteId() + "/bitacora";

		pedir(get(ruta), ciudadano.token()).andExpect(status().isForbidden());
		pedir(get(ruta), paramedico.token()).andExpect(status().isForbidden());
		pedir(get(ruta), null).andExpect(status().isUnauthorized());
		pedir(get("/incidentes/999999/bitacora"), escenario.admin())
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));
	}

}
