package com.uem.ambulancias.revision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
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

import com.uem.ambulancias.coordinacion.service.EvaluadorDeCasos;
import com.uem.ambulancias.evidencias.service.FalloDelServicioIa.Desenlace;
import com.uem.ambulancias.revision.service.AnalistaDelProtocolo.Hecho;
import com.uem.ambulancias.revision.service.AnalistaDelProtocolo.PedidoDeRevision;
import com.uem.ambulancias.revision.service.AnalistaDelProtocolo.Propuesta;
import com.uem.ambulancias.revision.service.RevisorDelProtocolo;
import com.uem.ambulancias.soporte.Escenario.Alerta;
import com.uem.ambulancias.soporte.Escenario.Ciudadano;
import com.uem.ambulancias.soporte.Json;
import com.uem.ambulancias.soporte.PruebaIT;

/**
 * El analista revisa el protocolo vigente fuera de los casos: el backend cuenta cómo funcionó, el analista propone y
 * una persona decide. Ninguna propuesta cambia el protocolo, y la que no tiene un hecho detrás no llega.
 */
@Tag("PB-45")
class RevisionDelProtocoloIT extends PruebaIT {

	private static final String ACCIDENTE = "ACCIDENTE_DE_TRANSITO";
	private static final String ATRAPADA = "PERSONA_ATRAPADA";

	@Autowired
	private EvaluadorDeCasos evaluador;

	@Autowired
	private RevisorDelProtocolo revisor;

	/** Un accidente en el que el testigo dice que hay alguien atrapado, ya evaluado. */
	private Alerta casoEvaluado() throws Exception {
		Ciudadano ciudadano = escenario.ciudadano();
		Alerta alerta = escenario.alertarCon(ciudadano, ACCIDENTE);
		pedir(post("/alertas/" + alerta.alertaId() + "/respuestas"), ciudadano.token(),
				"{\"respuestas\": [" + Json.objeto("condicion", ATRAPADA, "valor", "SI") + "]}")
				.andExpect(status().isOk());
		evaluador.barrer();
		return alerta;
	}

	private long pedirRevision() throws Exception {
		return Json.numero(cuerpo(pedir(post("/protocolos/revisiones"), escenario.admin())
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.estado").value("PENDIENTE"))), "$.id");
	}

	private ResultActions revision(long id) throws Exception {
		return pedir(get("/protocolos/revisiones/" + id), escenario.admin()).andExpect(status().isOk());
	}

	@Test
	@DisplayName("PB-45 · CP-45-01 · sin el analista encendido o sin un protocolo publicado no se puede pedir una revisión")
	void noSePuedePedir() throws Exception {
		escenario.protocoloVigente();
		pedir(post("/protocolos/revisiones"), escenario.admin())
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.codigo").value("ANALISTA_NO_DISPONIBLE"));
		pedir(post("/protocolos/revisiones"), escenario.ciudadano().token()).andExpect(status().isForbidden());
		assertThat(ia.revisionesPedidas()).isEmpty();
	}

	@Test
	@DisplayName("PB-45 · CP-45-02 · sin protocolo publicado no hay qué revisar")
	void sinProtocolo() throws Exception {
		ia.encenderAnalista();

		pedir(post("/protocolos/revisiones"), escenario.admin())
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.codigo").value("PROTOCOLO_INVALIDO"));
	}

	@Test
	@DisplayName("PB-45 · CP-45-03 · con el protocolo sin usar, la revisión termina sin llamar al analista")
	void sinCasos() throws Exception {
		ia.encenderAnalista();
		escenario.protocoloVigente();
		long id = pedirRevision();

		revisor.barrer();

		revision(id)
				.andExpect(jsonPath("$.estado").value("HECHA"))
				.andExpect(jsonPath("$.hechos", hasSize(0)))
				.andExpect(jsonPath("$.propuestas", hasSize(0)))
				.andExpect(jsonPath("$.nota", containsString("no hay nada que revisar")));
		assertThat(ia.revisionesPedidas()).isEmpty();
	}

	@Test
	@DisplayName("PB-45 · CP-45-04 · el analista recibe los hechos ya contados y su propuesta llega con el hecho que la respalda")
	void propuestaConRespaldo() throws Exception {
		ia.encenderAnalista();
		escenario.protocoloVigente();
		casoEvaluado();
		long id = pedirRevision();

		revisor.barrer();

		revision(id)
				.andExpect(jsonPath("$.estado").value("HECHA"))
				.andExpect(jsonPath("$.versionDelProtocolo").value(1))
				.andExpect(jsonPath("$.modelo").value("analista-de-prueba"))
				.andExpect(jsonPath("$.descartadas").value(0))
				.andExpect(jsonPath("$.propuestas", hasSize(1)))
				.andExpect(jsonPath("$.propuestas[0].tipo").value("OTRA"))
				.andExpect(jsonPath("$.propuestas[0].tipoDeSuceso").value(ACCIDENTE))
				.andExpect(jsonPath("$.propuestas[0].condicion").value(ATRAPADA))
				.andExpect(jsonPath("$.propuestas[0].hechos[0]", containsString("Hay una persona atrapada")))
				.andExpect(jsonPath("$.propuestas[0].hechos[0]", containsString("se evaluó en 1 caso")));
		// Lo que recibió el analista: el protocolo con su contrato y los hechos en frases, con sus cuentas.
		PedidoDeRevision pedido = ia.revisionesPedidas().getFirst();
		assertThat(Json.texto(pedido.protocoloJson(), "$.types[0].code")).isEqualTo(ACCIDENTE);
		assertThat(Json.texto(pedido.protocoloJson(), "$.types[0].conditions[0].code")).isEqualTo(ATRAPADA);
		assertThat(pedido.hechos()).extracting(Hecho::id).doesNotHaveDuplicates().allMatch(uno -> uno.startsWith("H"));
		assertThat(pedido.hechos()).anySatisfy(hecho -> {
			assertThat(hecho.condicion()).isNull();
			assertThat(hecho.texto()).contains("hubo 1 incidente de este tipo");
		});
		assertThat(pedido.hechos()).anySatisfy(hecho -> {
			assertThat(hecho.condicion()).isEqualTo(ATRAPADA);
			assertThat(hecho.texto()).contains("Se cumplió en 1").contains("Son pocos casos para cambiar umbrales");
			assertThat(hecho.casos()).isEqualTo(1);
		});
		assertThat(pedido.hechos()).anySatisfy(hecho -> assertThat(hecho.texto())
				.contains("¿Hay alguien atrapado?").contains("1 sí, 0 no y 0 «no sé»"));
		// El protocolo no cambió: sigue la misma versión vigente y nadie abrió un borrador.
		pedir(get("/protocolos/vigente"), escenario.admin()).andExpect(jsonPath("$.version").value(1));
		pedir(get("/protocolos/borrador"), escenario.admin()).andExpect(status().isNotFound());
		pedir(get("/protocolos/revisiones"), escenario.admin())
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].id").value((int) id));
	}

	@Test
	@DisplayName("PB-45 · CP-45-05 · una propuesta de cambiar un umbral con pocos casos, o que cita un hecho que no se mandó, no llega a la persona")
	void sinRespaldoNoLlega() throws Exception {
		ia.encenderAnalista();
		escenario.protocoloVigente();
		casoEvaluado();
		long id = pedirRevision();
		revisor.barrer();
		String hechoDeLaCondicion = ia.revisionesPedidas().getFirst().hechos().stream()
				.filter(hecho -> ATRAPADA.equals(hecho.condicion())).findFirst().orElseThrow().id();
		ia.proponer(
				new Propuesta("adjust_threshold", ACCIDENTE, ATRAPADA, "Bajar la certeza para actuar.",
						"Se cumple seguido.", List.of(hechoDeLaCondicion), List.of()),
				new Propuesta("change_question", ACCIDENTE, ATRAPADA, "Preguntar distinto.", "Nadie contesta.",
						List.of("H99"), List.of()),
				new Propuesta("change_question", ACCIDENTE, ATRAPADA, "Preguntar más corto.", "Se entiende mejor.",
						List.of(hechoDeLaCondicion), List.of("N9")));
		long otra = pedirRevision();

		revisor.barrer();

		revision(otra)
				.andExpect(jsonPath("$.estado").value("HECHA"))
				.andExpect(jsonPath("$.descartadas").value(2))
				.andExpect(jsonPath("$.propuestas", hasSize(1)))
				.andExpect(jsonPath("$.propuestas[0].tipo").value("CAMBIAR_PREGUNTA"))
				.andExpect(jsonPath("$.propuestas[0].propuesta").value("Preguntar más corto."))
				// La cita a un extracto de la norma que no se mandó se quita.
				.andExpect(jsonPath("$.propuestas[0].norma", hasSize(0)));
		assertThat(id).isNotEqualTo(otra);
	}

	@Test
	@DisplayName("PB-45 · CP-45-06 · si el analista no responde, la revisión queda fallida con el motivo y se puede pedir otra")
	void elAnalistaNoResponde() throws Exception {
		ia.encenderAnalista();
		escenario.protocoloVigente();
		casoEvaluado();
		ia.fallarAnalista("provider_unavailable", Desenlace.REINTENTAR);
		long id = pedirRevision();

		revisor.barrer();
		revisor.barrer();

		revision(id)
				.andExpect(jsonPath("$.estado").value("FALLIDA"))
				.andExpect(jsonPath("$.nota", containsString("provider_unavailable")))
				.andExpect(jsonPath("$.propuestas").doesNotExist());
		// No se reintenta sola: quedó cerrada.
		assertThat(ia.revisionesPedidas()).hasSize(1);
	}

}
