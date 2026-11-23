package com.uem.ambulancias.coordinacion.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.uem.ambulancias.emergencias.domain.Alerta;
import com.uem.ambulancias.emergencias.domain.EstadoAlerta;
import com.uem.ambulancias.emergencias.domain.EventoDelCaso;
import com.uem.ambulancias.emergencias.domain.Incidente;
import com.uem.ambulancias.emergencias.domain.RespuestaDelTestigo;
import com.uem.ambulancias.emergencias.domain.TipoEventoDelCaso;
import com.uem.ambulancias.emergencias.repository.AlertaRepository;
import com.uem.ambulancias.emergencias.repository.EventoDelCasoRepository;
import com.uem.ambulancias.evidencias.domain.AnalisisEvidencia;
import com.uem.ambulancias.evidencias.repository.AnalisisEvidenciaRepository;
import com.uem.ambulancias.evidencias.repository.ResumenIncidenteRepository;
import com.uem.ambulancias.protocolo.domain.TipoDeSuceso;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Arma el estado del caso: todo lo que se sabe de un incidente, ordenado, para evaluar las condiciones del
 * protocolo. Sale de la bitácora y de lo que el servicio de análisis ya describió.
 *
 * <p>No lleva nada escrito a mano por el testigo, ni su descripción ni la transcripción de sus audios: un texto
 * armado a propósito puede mover la respuesta de un modelo. Lleva lo que marcó en el formulario y lo que el análisis
 * resumió de sus fotos y audios. Tampoco lleva nombres ni teléfonos.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArmadorDelEstado {

	/** Con este nombre lo lee también el modelo de decisión de prueba del servicio de IA. */
	static final String RESPUESTAS = "respuestasDelTestigo";

	/** Cuántas frases de cada lista de un análisis se incluyen: más no suma y alarga el estado. */
	private static final int MAXIMO_DE_FRASES = 8;

	private final EventoDelCasoRepository eventos;
	private final AlertaRepository alertas;
	private final AnalisisEvidenciaRepository analisis;
	private final ResumenIncidenteRepository resumenes;
	private final JsonMapper json;

	/**
	 * @param estado      lo que se guarda con la evaluación y se le manda al modelo
	 * @param hechos      lo que usan las reglas
	 * @param hastaEvento el último renglón de la bitácora que se miró
	 */
	public record EstadoArmado(Map<String, Object> estado, JuicioDelProtocolo.Hechos hechos, Long hastaEvento) {
	}

	/** Exige una transacción abierta: lee el incidente, su bitácora, sus alertas y sus análisis. */
	public EstadoArmado armar(Incidente incidente, TipoDeSuceso tipo) {
		Long incidenteId = incidente.getId();
		List<EventoDelCaso> bitacora = eventos.findByIncidenteIdOrderByIdAsc(incidenteId);
		List<Alerta> delIncidente = alertas.buscarPorIncidente(incidenteId);
		Set<Long> retiradas = new HashSet<>();
		for (Alerta alerta : delIncidente) {
			if (alerta.getEstado() == EstadoAlerta.CANCELADA) {
				retiradas.add(alerta.getId());
			}
		}
		Map<String, RespuestaDelTestigo> respuestas = respuestasDe(bitacora, retiradas);
		List<Map<String, Object>> evidencias = new ArrayList<>();
		for (AnalisisEvidencia uno : analisis.buscarVigentesPorIncidente(incidenteId)) {
			Map<String, Object> descrita = evidenciaDescrita(uno);
			if (descrita != null) {
				evidencias.add(descrita);
			}
		}

		Map<String, Object> estado = new LinkedHashMap<>();
		Map<String, Object> suceso = new LinkedHashMap<>();
		suceso.put("codigo", tipo.getCodigo());
		suceso.put("nombre", tipo.getNombre());
		estado.put("tipoDeSuceso", suceso);
		if (incidente.getCantidadAfectados() != null) {
			estado.put("afectadosDeclarados", incidente.getCantidadAfectados());
		}
		estado.put("personasQueAlertaron", delIncidente.size() - retiradas.size());
		Map<String, String> respuestasDelTestigo = new LinkedHashMap<>();
		respuestas.forEach((condicion, valor) -> respuestasDelTestigo.put(condicion, valor.name()));
		estado.put(RESPUESTAS, respuestasDelTestigo);
		estado.put("evidencias", evidencias);
		resumenes.findFirstByIncidenteIdOrderByVersionDesc(incidenteId)
				.map(resumen -> resumenDescrito(resumen.getResumen(), incidenteId))
				.ifPresent(resumen -> estado.put("resumenDelIncidente", resumen));

		Long hastaEvento = bitacora.isEmpty() ? null : bitacora.getLast().getId();
		return new EstadoArmado(estado, new JuicioDelProtocolo.Hechos(respuestas, !evidencias.isEmpty()), hastaEvento);
	}

	/** Los hechos que usan las reglas, releídos de un estado que ya se armó y se guardó. */
	static JuicioDelProtocolo.Hechos hechosDe(JsonNode estado) {
		Map<String, RespuestaDelTestigo> respuestas = new LinkedHashMap<>();
		estado.path(RESPUESTAS).properties().forEach(campo -> respuestas.put(campo.getKey(),
				RespuestaDelTestigo.valueOf(campo.getValue().asString())));
		return new JuicioDelProtocolo.Hechos(respuestas, !estado.path("evidencias").isEmpty());
	}

	/**
	 * Lo que quedó contestado de cada condición. De cada alerta vale su última respuesta; entre alertas distintas gana
	 * el «sí» sobre el «no», y el «no» sobre el «no sé»: si un testigo vio algo que otro no, se le cree al que lo vio.
	 * Las respuestas de quien retiró su pedido no cuentan.
	 */
	private Map<String, RespuestaDelTestigo> respuestasDe(List<EventoDelCaso> bitacora, Set<Long> alertasRetiradas) {
		Map<Long, Map<String, RespuestaDelTestigo>> porAlerta = new LinkedHashMap<>();
		for (EventoDelCaso evento : bitacora) {
			if (evento.getTipo() != TipoEventoDelCaso.RESPUESTA_DEL_TESTIGO || evento.getAlerta() == null
					|| alertasRetiradas.contains(evento.getAlerta().getId())) {
				continue;
			}
			JsonNode datos = json.readTree(evento.getDatos());
			porAlerta.computeIfAbsent(evento.getAlerta().getId(), id -> new LinkedHashMap<>())
					.put(datos.path("condicion").asString(), RespuestaDelTestigo.valueOf(datos.path("valor").asString()));
		}
		Map<String, RespuestaDelTestigo> juntas = new LinkedHashMap<>();
		for (Map<String, RespuestaDelTestigo> deUna : porAlerta.values()) {
			deUna.forEach((condicion, valor) -> juntas.merge(condicion, valor, ArmadorDelEstado::laQueMasPesa));
		}
		return juntas;
	}

	private static RespuestaDelTestigo laQueMasPesa(RespuestaDelTestigo una, RespuestaDelTestigo otra) {
		if (una == RespuestaDelTestigo.SI || otra == RespuestaDelTestigo.SI) {
			return RespuestaDelTestigo.SI;
		}
		return una == RespuestaDelTestigo.NO || otra == RespuestaDelTestigo.NO ? RespuestaDelTestigo.NO
				: RespuestaDelTestigo.NO_SE;
	}

	/** Lo que el análisis describió de una evidencia, o {@code null} si no sirve o no se puede leer. */
	private Map<String, Object> evidenciaDescrita(AnalisisEvidencia uno) {
		JsonNode raiz;
		try {
			raiz = json.readTree(uno.getAnalisis());
		} catch (JacksonException e) {
			log.warn("No se pudo leer el análisis de la evidencia {}: no entra en el estado del caso.",
					uno.getEvidencia().getId());
			return null;
		}
		JsonNode sirve = raiz.path("usable");
		if (sirve.isBoolean() && !sirve.asBoolean()) {
			return null;
		}
		Map<String, Object> descrita = new LinkedHashMap<>();
		descrita.put("modalidad", uno.getModalidad());
		ponerTexto(descrita, "resumen", raiz.path("summary"));
		ponerTexto(descrita, "tipoDeEvento", raiz.path("eventType"));
		ponerObjeto(descrita, "personas", raiz.path("people"));
		ponerFrases(descrita, "peligros", raiz.path("hazards"), null);
		ponerFrases(descrita, "observaciones", raiz.path("observations"), "text");
		ponerFrases(descrita, "riesgos", raiz.path("risks"), null);
		ponerTexto(descrita, "gravedad", raiz.path("severity").path("level"));
		// Solo con la modalidad no dice nada del suceso.
		return descrita.size() > 1 ? descrita : null;
	}

	private Map<String, Object> resumenDescrito(String resumen, Long incidenteId) {
		JsonNode raiz;
		try {
			raiz = json.readTree(resumen);
		} catch (JacksonException e) {
			log.warn("No se pudo leer el resumen del incidente {}: no entra en el estado del caso.", incidenteId);
			return null;
		}
		Map<String, Object> descrito = new LinkedHashMap<>();
		ponerTexto(descrito, "resumen", raiz.path("summary"));
		ponerFrases(descrito, "puntosClave", raiz.path("keyPoints"), "text");
		ponerFrases(descrito, "peligros", raiz.path("hazards"), null);
		ponerObjeto(descrito, "personas", raiz.path("people"));
		ponerTexto(descrito, "gravedad", raiz.path("severity").path("level"));
		return descrito.isEmpty() ? null : descrito;
	}

	private static void ponerTexto(Map<String, Object> destino, String clave, JsonNode nodo) {
		if (nodo.isString() && !nodo.asString().isBlank()) {
			destino.put(clave, nodo.asString());
		}
	}

	/** Un objeto chico de números, como el rango de personas: se copia campo por campo, sin textos. */
	private static void ponerObjeto(Map<String, Object> destino, String clave, JsonNode nodo) {
		if (!nodo.isObject()) {
			return;
		}
		Map<String, Object> copia = new HashMap<>();
		nodo.properties().forEach(campo -> {
			if (campo.getValue().isNumber()) {
				copia.put(campo.getKey(), campo.getValue().numberValue());
			}
		});
		if (!copia.isEmpty()) {
			destino.put(clave, copia);
		}
	}

	/** Una lista de textos, o de objetos de los que se toma un campo de texto. */
	private static void ponerFrases(Map<String, Object> destino, String clave, JsonNode lista, String campo) {
		List<String> frases = new ArrayList<>();
		for (JsonNode elemento : lista) {
			JsonNode texto = campo == null ? elemento : elemento.path(campo);
			if (texto.isString() && !texto.asString().isBlank() && frases.size() < MAXIMO_DE_FRASES) {
				frases.add(texto.asString());
			}
		}
		if (!frases.isEmpty()) {
			destino.put(clave, frases);
		}
	}

}
