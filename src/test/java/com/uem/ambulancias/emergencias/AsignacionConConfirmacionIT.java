package com.uem.ambulancias.emergencias;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import com.uem.ambulancias.emergencias.service.VencimientoDeOfertas;
import com.uem.ambulancias.soporte.Escenario;
import com.uem.ambulancias.soporte.Escenario.Alerta;
import com.uem.ambulancias.soporte.Escenario.Ciudadano;
import com.uem.ambulancias.soporte.Escenario.Paramedico;
import com.uem.ambulancias.soporte.Json;
import com.uem.ambulancias.soporte.PruebaIT;

/**
 * El despacho en modo asignación: el incidente se le asigna a la unidad libre más cercana, que confirma, rechaza o
 * deja pasar el tiempo. El reloj es el real y cada prueba lo adelanta cuando quiere.
 */
@Tag("PB-34")
class AsignacionConConfirmacionIT extends PruebaIT {

	private static final double LEJOS = 0.03;

	@Autowired
	private VencimientoDeOfertas vencimiento;

	private ResultActions oferta(Paramedico paramedico) throws Exception {
		return pedir(get("/paramedicos/actual/oferta"), paramedico.token());
	}

	private ResultActions confirmar(Paramedico paramedico) throws Exception {
		return pedir(post("/paramedicos/actual/oferta/confirmacion"), paramedico.token());
	}

	private ResultActions rechazar(Paramedico paramedico, String motivo) throws Exception {
		return pedir(post("/paramedicos/actual/oferta/rechazo"), paramedico.token(), Json.objeto("motivo", motivo));
	}

	private ResultActions bitacora(long incidenteId) throws Exception {
		return pedir(get("/incidentes/" + incidenteId + "/bitacora"), escenario.admin()).andExpect(status().isOk());
	}

	/** Deja sin tiempo a las asignaciones pendientes y corre el barrido. */
	private void pasarElTiempoDeEspera() {
		jdbc.update("update oferta_de_incidente set vence_en = now() - interval '10 minutes' where estado = 'PENDIENTE'");
		vencimiento.barrer();
	}

	/** Los tipos de push que recibió ese teléfono de paramédico, en orden. */
	private List<String> avisosA(String tokenPush) {
		return publicaciones.avisosAlParamedico(tokenPush).stream().map(aviso -> aviso.datos().get("tipo")).toList();
	}

	private Paramedico unidad(double alNorte, String tokenPush) {
		Paramedico paramedico = escenario.unidadEnLaCalle(alNorte);
		escenario.recibirAvisos(paramedico, tokenPush);
		return paramedico;
	}

	@Test
	@DisplayName("PB-34 · CP-34-01 · el incidente se le asigna a la unidad libre más cercana, y solo a ella le llega el aviso")
	void asignaALaMasCercana() throws Exception {
		escenario.modoDeDespacho("ASIGNACION");
		Paramedico lejos = unidad(LEJOS, "push-lejos");
		Paramedico cerca = unidad(Escenario.CIEN_METROS, "push-cerca");

		Alerta alerta = escenario.alertar(escenario.ciudadano());

		oferta(cerca)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").isNumber())
				.andExpect(jsonPath("$.incidenteId").value(alerta.incidenteId()))
				.andExpect(jsonPath("$.latitud").value(Escenario.LATITUD))
				.andExpect(jsonPath("$.longitud").value(Escenario.LONGITUD))
				.andExpect(jsonPath("$.ofrecidaEn").isNotEmpty())
				.andExpect(jsonPath("$.venceEn").isNotEmpty())
				.andExpect(jsonPath("$.minutosDeLlegada").isNumber());
		oferta(lejos).andExpect(status().isNoContent());
		assertThat(avisosA("push-cerca")).containsExactly("INCIDENTE_POR_CONFIRMAR");
		assertThat(avisosA("push-lejos")).isEmpty();
		assertThat(publicaciones.cantidadDeAvisosDeIncidenteNuevo()).isZero();
		// Todavía nadie va en camino: falta la confirmación.
		pedir(get("/incidentes/" + alerta.incidenteId()), escenario.admin())
				.andExpect(jsonPath("$.estado").value("ACTIVO"));
		bitacora(alerta.incidenteId())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[1].tipo").value("UNIDAD_ASIGNADA"))
				.andExpect(jsonPath("$[1].origen").value("SISTEMA"))
				.andExpect(jsonPath("$[1].datos.ambulanciaId").value(cerca.ambulanciaId()))
				.andExpect(jsonPath("$[1].datos.ronda").value(1))
				.andExpect(jsonPath("$[1].datos.orden").value(1))
				.andExpect(jsonPath("$[1].datos.segundosParaConfirmar").value(30));
	}

	@Test
	@DisplayName("PB-34 · CP-34-02 · al confirmar, la unidad queda en camino y la asignación deja de esperar respuesta")
	void confirma() throws Exception {
		escenario.modoDeDespacho("ASIGNACION");
		Paramedico cerca = unidad(Escenario.CIEN_METROS, "push-cerca");
		Alerta alerta = escenario.alertar(escenario.ciudadano());

		confirmar(cerca)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").isNumber())
				.andExpect(jsonPath("$.estado").value("EN_CAMINO"));

		pedir(get("/incidentes/" + alerta.incidenteId()), escenario.admin())
				.andExpect(jsonPath("$.estado").value("EN_ATENCION"))
				.andExpect(jsonPath("$.atenciones[0].ambulanciaId").value(cerca.ambulanciaId()))
				.andExpect(jsonPath("$.atenciones[0].paramedicoResponsable.id").value(cerca.id()));
		oferta(cerca).andExpect(status().isNoContent());
		confirmar(cerca)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.codigo").value("SIN_OFERTA"));
		assertThat(avisosA("push-cerca")).containsExactly("INCIDENTE_POR_CONFIRMAR");
		bitacora(alerta.incidenteId())
				.andExpect(jsonPath("$.length()").value(3))
				.andExpect(jsonPath("$[2].tipo").value("ASIGNACION_CONFIRMADA"))
				.andExpect(jsonPath("$[2].origen").value("TRIPULACION"))
				.andExpect(jsonPath("$[2].datos.ambulanciaId").value(cerca.ambulanciaId()))
				.andExpect(jsonPath("$[2].datos.atencionId").isNumber())
				.andExpect(jsonPath("$[2].datos.segundosEnResponder").isNumber());
	}

	@Test
	@DisplayName("PB-34 · CP-34-03 · si la tripulación dice que no puede, el incidente pasa en el momento a la siguiente")
	void rechaza() throws Exception {
		escenario.modoDeDespacho("ASIGNACION");
		Paramedico lejos = unidad(LEJOS, "push-lejos");
		Paramedico cerca = unidad(Escenario.CIEN_METROS, "push-cerca");
		Alerta alerta = escenario.alertar(escenario.ciudadano());

		rechazar(cerca, "   ")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.codigo").value("VALIDACION"));
		rechazar(cerca, "  Estamos cargando combustible  ").andExpect(status().isNoContent());

		oferta(cerca).andExpect(status().isNoContent());
		oferta(lejos)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.incidenteId").value(alerta.incidenteId()));
		assertThat(avisosA("push-lejos")).containsExactly("INCIDENTE_POR_CONFIRMAR");
		bitacora(alerta.incidenteId())
				.andExpect(jsonPath("$.length()").value(4))
				.andExpect(jsonPath("$[2].tipo").value("ASIGNACION_RECHAZADA"))
				.andExpect(jsonPath("$[2].origen").value("TRIPULACION"))
				.andExpect(jsonPath("$[2].datos.ambulanciaId").value(cerca.ambulanciaId()))
				.andExpect(jsonPath("$[2].datos.motivo").value("Estamos cargando combustible"))
				.andExpect(jsonPath("$[3].tipo").value("UNIDAD_ASIGNADA"))
				.andExpect(jsonPath("$[3].datos.ambulanciaId").value(lejos.ambulanciaId()))
				.andExpect(jsonPath("$[3].datos.ronda").value(1))
				.andExpect(jsonPath("$[3].datos.orden").value(2));
	}

	@Test
	@DisplayName("PB-34 · CP-34-04 · si nadie contesta a tiempo, el incidente pasa a la siguiente y a la primera se le avisa")
	void vence() throws Exception {
		escenario.modoDeDespacho("ASIGNACION");
		Paramedico lejos = unidad(LEJOS, "push-lejos");
		Paramedico cerca = unidad(Escenario.CIEN_METROS, "push-cerca");
		Alerta alerta = escenario.alertar(escenario.ciudadano());

		// Con tiempo todavía, el barrido no toca nada.
		vencimiento.barrer();
		oferta(cerca).andExpect(status().isOk());

		pasarElTiempoDeEspera();

		oferta(cerca).andExpect(status().isNoContent());
		oferta(lejos)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.incidenteId").value(alerta.incidenteId()));
		assertThat(avisosA("push-cerca")).containsExactly("INCIDENTE_POR_CONFIRMAR", "ASIGNACION_RETIRADA");
		confirmar(cerca)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.codigo").value("SIN_OFERTA"));
		bitacora(alerta.incidenteId())
				.andExpect(jsonPath("$.length()").value(4))
				.andExpect(jsonPath("$[2].tipo").value("ASIGNACION_VENCIDA"))
				.andExpect(jsonPath("$[2].origen").value("SISTEMA"))
				.andExpect(jsonPath("$[3].tipo").value("UNIDAD_ASIGNADA"))
				.andExpect(jsonPath("$[3].datos.ambulanciaId").value(lejos.ambulanciaId()));
	}

	@Test
	@DisplayName("PB-34 · CP-34-05 · cuando no queda a quién asignarlo, el incidente se abre a todas las de turno")
	void seAbreATodas() throws Exception {
		escenario.modoDeDespacho("ASIGNACION");
		Paramedico cerca = unidad(Escenario.CIEN_METROS, "push-cerca");
		// De turno pero sin posición: el sistema no le asigna nada sola, aunque sí puede tomar.
		Paramedico sinPosicion = escenario.paramedicoEnTurno();
		escenario.recibirAvisos(sinPosicion, "push-sin-posicion");
		Alerta alerta = escenario.alertar(escenario.ciudadano());

		rechazar(cerca, "No podemos salir").andExpect(status().isNoContent());

		oferta(cerca).andExpect(status().isNoContent());
		oferta(sinPosicion).andExpect(status().isNoContent());
		assertThat(publicaciones.cantidadDeAvisosDeIncidenteNuevo()).isEqualTo(1);
		assertThat(publicaciones.ultimoAvisoDeIncidenteNuevo())
				.containsExactlyInAnyOrder("push-cerca", "push-sin-posicion");
		bitacora(alerta.incidenteId())
				// Después de quedarse sin unidad, el caso se le pasa además a una persona de la central (PB-38).
				.andExpect(jsonPath("$.length()").value(5))
				.andExpect(jsonPath("$[3].tipo").value("SIN_UNIDAD_PARA_ASIGNAR"))
				.andExpect(jsonPath("$[3].origen").value("SISTEMA"))
				.andExpect(jsonPath("$[3].datos.unidadesQueLoTuvieron").value(1))
				.andExpect(jsonPath("$[4].tipo").value("DERIVADO_A_PERSONA"));

		// Abierto a todas, lo toma la que pueda, como en el modo de siempre.
		escenario.tomar(sinPosicion, alerta.incidenteId());
		pedir(get("/incidentes/" + alerta.incidenteId()), escenario.admin())
				.andExpect(jsonPath("$.estado").value("EN_ATENCION"));
	}

	@Test
	@DisplayName("PB-34 · CP-34-06 · sin ninguna unidad con posición reciente, el incidente se abre a todas de entrada")
	void sinCandidatas() throws Exception {
		escenario.modoDeDespacho("ASIGNACION");
		Paramedico sinPosicion = escenario.paramedicoEnTurno();
		escenario.recibirAvisos(sinPosicion, "push-sin-posicion");

		Alerta alerta = escenario.alertar(escenario.ciudadano());

		oferta(sinPosicion).andExpect(status().isNoContent());
		assertThat(publicaciones.cantidadDeAvisosDeIncidenteNuevo()).isEqualTo(1);
		assertThat(publicaciones.ultimoAvisoDeIncidenteNuevo()).containsExactly("push-sin-posicion");
		bitacora(alerta.incidenteId())
				.andExpect(jsonPath("$.length()").value(3))
				.andExpect(jsonPath("$[1].tipo").value("SIN_UNIDAD_PARA_ASIGNAR"))
				.andExpect(jsonPath("$[1].datos.unidadesQueLoTuvieron").value(0))
				.andExpect(jsonPath("$[2].tipo").value("DERIVADO_A_PERSONA"));
	}

	@Test
	@DisplayName("PB-34 · CP-34-07 · si otra unidad toma el incidente por su cuenta, la asignación pendiente se retira")
	void laTomaOtraUnidad() throws Exception {
		escenario.modoDeDespacho("ASIGNACION");
		Paramedico lejos = unidad(LEJOS, "push-lejos");
		Paramedico cerca = unidad(Escenario.CIEN_METROS, "push-cerca");
		Alerta alerta = escenario.alertar(escenario.ciudadano());
		oferta(cerca).andExpect(status().isOk());

		escenario.tomar(lejos, alerta.incidenteId());

		oferta(cerca).andExpect(status().isNoContent());
		assertThat(avisosA("push-cerca")).containsExactly("INCIDENTE_POR_CONFIRMAR", "ASIGNACION_RETIRADA");
		confirmar(cerca)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.codigo").value("SIN_OFERTA"));
		bitacora(alerta.incidenteId())
				.andExpect(jsonPath("$.length()").value(3))
				.andExpect(jsonPath("$[2].tipo").value("ASIGNACION_RETIRADA"))
				.andExpect(jsonPath("$[2].datos.ambulanciaId").value(cerca.ambulanciaId()))
				.andExpect(jsonPath("$[2].datos.estadoDelIncidente").value("EN_ATENCION"));
	}

	@Test
	@DisplayName("PB-34 · CP-34-08 · si el incidente se cierra antes de la confirmación, la asignación se retira")
	void seCierraElIncidente() throws Exception {
		escenario.modoDeDespacho("ASIGNACION");
		Paramedico cerca = unidad(Escenario.CIEN_METROS, "push-cerca");
		Ciudadano ciudadano = escenario.ciudadano();
		Alerta alerta = escenario.alertar(ciudadano);
		oferta(cerca).andExpect(status().isOk());

		pedir(post("/alertas/" + alerta.alertaId() + "/cancelacion"), ciudadano.token(),
				Json.objeto("motivo", "FALSA_ALARMA")).andExpect(status().isOk());

		pedir(get("/incidentes/" + alerta.incidenteId()), escenario.admin())
				.andExpect(jsonPath("$.estado").value("CANCELADO"));
		oferta(cerca).andExpect(status().isNoContent());
		assertThat(avisosA("push-cerca")).containsExactly("INCIDENTE_POR_CONFIRMAR", "ASIGNACION_RETIRADA");
		bitacora(alerta.incidenteId())
				.andExpect(jsonPath("$.length()").value(4))
				.andExpect(jsonPath("$[3].tipo").value("ASIGNACION_RETIRADA"))
				.andExpect(jsonPath("$[3].datos.estadoDelIncidente").value("CANCELADO"));
	}

	@Test
	@DisplayName("PB-34 · CP-34-09 · a una unidad que ya tiene una asignación esperando no se le asigna otro incidente")
	void unaALaVez() throws Exception {
		escenario.modoDeDespacho("ASIGNACION");
		Paramedico lejos = unidad(LEJOS, "push-lejos");
		Paramedico cerca = unidad(Escenario.CIEN_METROS, "push-cerca");
		Alerta primero = escenario.alertar(escenario.ciudadano());
		// Otro incidente, al sur: la más cercana sigue siendo la misma unidad, pero ya está esperando el primero.
		Alerta segundo = escenario.alertar(escenario.ciudadano(), Escenario.LATITUD - 0.01, Escenario.LONGITUD);

		assertThat(segundo.incidenteId()).isNotEqualTo(primero.incidenteId());
		oferta(cerca).andExpect(jsonPath("$.incidenteId").value(primero.incidenteId()));
		oferta(lejos).andExpect(jsonPath("$.incidenteId").value(segundo.incidenteId()));
	}

	@Test
	@DisplayName("PB-34 · CP-34-10 · solo la tripulación de la unidad asignada ve y contesta la asignación")
	void soloLaUnidadAsignada() throws Exception {
		escenario.modoDeDespacho("ASIGNACION");
		Paramedico lejos = unidad(LEJOS, "push-lejos");
		Paramedico cerca = unidad(Escenario.CIEN_METROS, "push-cerca");
		Alerta alerta = escenario.alertar(escenario.ciudadano());

		confirmar(lejos)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.codigo").value("SIN_OFERTA"));
		rechazar(lejos, "No es para nosotros")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.codigo").value("SIN_OFERTA"));
		oferta(escenario.paramedicoActivado())
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.codigo").value("SIN_TURNO"));
		pedir(get("/paramedicos/actual/oferta"), escenario.ciudadano().token()).andExpect(status().isForbidden());
		pedir(post("/paramedicos/actual/oferta/confirmacion"), escenario.admin()).andExpect(status().isForbidden());
		pedir(get("/paramedicos/actual/oferta"), null).andExpect(status().isUnauthorized());

		// Nada de eso tocó la asignación de la unidad que sí la tiene.
		oferta(cerca).andExpect(jsonPath("$.incidenteId").value(alerta.incidenteId()));
	}

	@Test
	@DisplayName("PB-34 · CP-34-11 · en modo difusión nada cambia: se avisa a todas y nadie recibe una asignación")
	void enDifusionNadaCambia() throws Exception {
		Paramedico cerca = unidad(Escenario.CIEN_METROS, "push-cerca");
		Paramedico lejos = unidad(LEJOS, "push-lejos");

		Alerta alerta = escenario.alertar(escenario.ciudadano());

		oferta(cerca).andExpect(status().isNoContent());
		oferta(lejos).andExpect(status().isNoContent());
		assertThat(publicaciones.cantidadDeAvisosDeIncidenteNuevo()).isEqualTo(1);
		assertThat(publicaciones.ultimoAvisoDeIncidenteNuevo()).containsExactly("push-cerca", "push-lejos");
		assertThat(avisosA("push-cerca")).isEmpty();
		bitacora(alerta.incidenteId()).andExpect(jsonPath("$.length()").value(1));
	}

	@Test
	@DisplayName("PB-34 · CP-34-12 · cuando una unidad queda libre, se le asigna el incidente que estaba esperando")
	void alLiberarseUnaUnidad() throws Exception {
		escenario.modoDeDespacho("ASIGNACION");
		Paramedico unica = unidad(Escenario.CIEN_METROS, "push-unica");
		long centro = escenario.centroDeSalud("Hospital Japonés", true);
		Alerta primero = escenario.alertar(escenario.ciudadano());
		long atencion = Json.numero(cuerpo(confirmar(unica).andExpect(status().isCreated())), "$.id");
		// Con la única unidad ocupada, el segundo incidente se queda esperando.
		Alerta segundo = escenario.alertar(escenario.ciudadano(), Escenario.LATITUD - 0.01, Escenario.LONGITUD);
		oferta(unica).andExpect(status().isNoContent());
		bitacora(segundo.incidenteId()).andExpect(jsonPath("$[1].tipo").value("SIN_UNIDAD_PARA_ASIGNAR"));

		escenario.hastaElHospital(unica, atencion);
		pedir(post("/atenciones/" + atencion + "/entrega"), unica.token(), Json.objeto("latitud", Escenario.LATITUD,
				"longitud", Escenario.LONGITUD, "centroSaludId", centro)).andExpect(status().isOk());
		escenario.liberar(unica, atencion);

		oferta(unica)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.incidenteId").value(segundo.incidenteId()));
		assertThat(primero.incidenteId()).isNotEqualTo(segundo.incidenteId());
	}

	@Test
	@DisplayName("PB-34 · CP-34-13 · si la unidad que confirmó deja el caso, empieza otra ronda y no se le vuelve a asignar a ella")
	void otraRonda() throws Exception {
		escenario.modoDeDespacho("ASIGNACION");
		Paramedico lejos = unidad(LEJOS, "push-lejos");
		Paramedico cerca = unidad(Escenario.CIEN_METROS, "push-cerca");
		Alerta alerta = escenario.alertar(escenario.ciudadano());
		long atencion = Json.numero(cuerpo(confirmar(cerca).andExpect(status().isCreated())), "$.id");

		pedir(post("/atenciones/" + atencion + "/cancelar"), cerca.token(), Json.objeto("motivo", "DESVIADA"))
				.andExpect(status().isOk());

		pedir(get("/incidentes/" + alerta.incidenteId()), escenario.admin())
				.andExpect(jsonPath("$.estado").value("ACTIVO"));
		oferta(cerca).andExpect(status().isNoContent());
		oferta(lejos)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.incidenteId").value(alerta.incidenteId()));
		bitacora(alerta.incidenteId())
				.andExpect(jsonPath("$[-1:].tipo").value("UNIDAD_ASIGNADA"))
				.andExpect(jsonPath("$[-1:].datos.ambulanciaId").value(lejos.ambulanciaId().intValue()))
				.andExpect(jsonPath("$[-1:].datos.ronda").value(2))
				.andExpect(jsonPath("$[-1:].datos.orden").value(1));
	}

}
