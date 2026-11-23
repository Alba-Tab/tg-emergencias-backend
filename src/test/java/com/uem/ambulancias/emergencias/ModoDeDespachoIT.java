package com.uem.ambulancias.emergencias;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.uem.ambulancias.soporte.Json;
import com.uem.ambulancias.soporte.PruebaIT;

/** El interruptor del despacho: la central ve cómo se despacha y lo cambia sin reiniciar nada. */
@Tag("PB-33")
class ModoDeDespachoIT extends PruebaIT {

	@Test
	@DisplayName("PB-33 · CP-33-01 · mientras la central no lo cambie, se despacha como siempre: avisando a todas")
	void porDefecto() throws Exception {
		pedir(get("/despacho/ajustes"), escenario.admin())
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.modo").value("DIFUSION"))
				.andExpect(jsonPath("$.segundosConfirmacion").value(30))
				.andExpect(jsonPath("$.actualizadoEn").doesNotExist())
				.andExpect(jsonPath("$.actualizadoPor").doesNotExist());
	}

	@Test
	@DisplayName("PB-33 · CP-33-02 · la central cambia el modo y la espera, y queda anotado quién lo hizo")
	void cambia() throws Exception {
		String admin = escenario.admin();

		pedir(put("/despacho/ajustes"), admin, Json.objeto("modo", "ASIGNACION", "segundosConfirmacion", 45))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.modo").value("ASIGNACION"))
				.andExpect(jsonPath("$.segundosConfirmacion").value(45))
				.andExpect(jsonPath("$.actualizadoEn").isNotEmpty())
				.andExpect(jsonPath("$.actualizadoPor").isNotEmpty());

		pedir(get("/despacho/ajustes"), admin)
				.andExpect(jsonPath("$.modo").value("ASIGNACION"))
				.andExpect(jsonPath("$.segundosConfirmacion").value(45))
				.andExpect(jsonPath("$.actualizadoPor").isNotEmpty());

		// Se puede volver atrás cuantas veces haga falta.
		pedir(put("/despacho/ajustes"), admin, Json.objeto("modo", "DIFUSION", "segundosConfirmacion", 30))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.modo").value("DIFUSION"));
		pedir(get("/despacho/ajustes"), admin).andExpect(jsonPath("$.modo").value("DIFUSION"));
	}

	@Test
	@DisplayName("PB-33 · CP-33-03 · la espera tiene piso y techo, y el modo tiene que existir")
	void valida() throws Exception {
		String admin = escenario.admin();

		pedir(put("/despacho/ajustes"), admin, Json.objeto("modo", "ASIGNACION", "segundosConfirmacion", 5))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.codigo").value("VALIDACION"))
				.andExpect(jsonPath("$.errores[0].campo").value("segundosConfirmacion"));
		pedir(put("/despacho/ajustes"), admin, Json.objeto("modo", "ASIGNACION", "segundosConfirmacion", 301))
				.andExpect(status().isBadRequest());
		pedir(put("/despacho/ajustes"), admin, Json.objeto("modo", "ASIGNACION"))
				.andExpect(status().isBadRequest());
		pedir(put("/despacho/ajustes"), admin, Json.objeto("modo", "AL_AZAR", "segundosConfirmacion", 30))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.codigo").value("VALIDACION"));

		pedir(get("/despacho/ajustes"), admin).andExpect(jsonPath("$.modo").value("DIFUSION"));
	}

	@Test
	@DisplayName("PB-33 · CP-33-04 · solo la central ve y cambia cómo se despacha")
	void soloLaCentral() throws Exception {
		String cuerpo = Json.objeto("modo", "ASIGNACION", "segundosConfirmacion", 30);

		pedir(get("/despacho/ajustes"), escenario.ciudadano().token()).andExpect(status().isForbidden());
		pedir(put("/despacho/ajustes"), escenario.paramedicoActivado().token(), cuerpo)
				.andExpect(status().isForbidden());
		pedir(put("/despacho/ajustes"), null, cuerpo).andExpect(status().isUnauthorized());
	}

}
