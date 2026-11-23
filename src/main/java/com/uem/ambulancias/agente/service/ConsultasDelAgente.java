package com.uem.ambulancias.agente.service;

import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.uem.ambulancias.coordinacion.domain.Derivacion;
import com.uem.ambulancias.coordinacion.repository.DerivacionRepository;
import com.uem.ambulancias.coordinacion.repository.EvaluacionDelCasoRepository;
import com.uem.ambulancias.coordinacion.service.ArmadorDelEstado;
import com.uem.ambulancias.emergencias.domain.Atencion;
import com.uem.ambulancias.emergencias.domain.EstadoTraslado;
import com.uem.ambulancias.emergencias.domain.Incidente;
import com.uem.ambulancias.emergencias.domain.Traslado;
import com.uem.ambulancias.emergencias.repository.AtencionRepository;
import com.uem.ambulancias.emergencias.repository.IncidenteRepository;
import com.uem.ambulancias.emergencias.repository.OfertaDeIncidenteRepository;
import com.uem.ambulancias.emergencias.repository.TrasladoRepository;
import com.uem.ambulancias.emergencias.service.EstimadorDeTiempos;
import com.uem.ambulancias.flota.domain.Ambulancia;
import com.uem.ambulancias.flota.domain.TipoUnidad;
import com.uem.ambulancias.flota.repository.AmbulanciaRepository;
import com.uem.ambulancias.flota.repository.TripulantesEnTurno;
import com.uem.ambulancias.flota.repository.TurnoRepository;
import com.uem.ambulancias.protocolo.domain.Condicion;
import com.uem.ambulancias.protocolo.domain.Protocolo;
import com.uem.ambulancias.protocolo.domain.TipoDeSuceso;
import com.uem.ambulancias.protocolo.service.ProtocoloService;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lo que el agente puede mirar de la central. Son lecturas: ninguna cambia nada. Devuelven mapas listos para
 * escribirse como JSON, con los nombres en español porque es lo que lee el modelo.
 *
 * <p>No devuelven nombres ni teléfonos de nadie, ni nada escrito a mano por un testigo: lo mismo que se cuida al
 * armar el estado del caso.
 */
@Service
@RequiredArgsConstructor
public class ConsultasDelAgente {

	/** Cuántos incidentes abiertos se devuelven como máximo. */
	private static final int MAXIMO_DE_CASOS_ABIERTOS = 30;

	/** Cuántos fragmentos de la norma se devuelven como máximo. */
	private static final int MAXIMO_DE_FRAGMENTOS = 8;

	private final IncidenteRepository incidentes;
	private final AtencionRepository atenciones;
	private final OfertaDeIncidenteRepository ofertas;
	private final DerivacionRepository derivaciones;
	private final EvaluacionDelCasoRepository evaluaciones;
	private final AmbulanciaRepository ambulancias;
	private final TurnoRepository turnos;
	private final TrasladoRepository traslados;
	private final ArmadorDelEstado armador;
	private final EstimadorDeTiempos estimador;
	private final ProtocoloService protocolos;
	private final AgenteProperties config;
	private final JsonMapper json;

	/**
	 * Todo lo que se sabe de un incidente: lo que marcaron los testigos, lo que el análisis describió de las
	 * evidencias, las condiciones de su tipo de suceso, lo último que se evaluó, lo que necesita y lo que ya tiene.
	 */
	@Transactional(readOnly = true)
	public Map<String, Object> verCaso(long incidenteId) {
		Incidente incidente = incidente(incidenteId);
		Instant ahora = Instant.now();
		Map<String, Object> caso = new LinkedHashMap<>();
		caso.put("incidenteId", incidente.getId());
		caso.put("estado", incidente.getEstado().name());
		caso.put("minutosAbierto", Duration.between(incidente.getFechaHoraCreacion(), ahora).toMinutes());
		TipoDeSuceso tipo = tipoDe(incidente);
		if (tipo == null) {
			caso.put("tipoDeSuceso", null);
			if (incidente.getCantidadAfectados() != null) {
				caso.put("afectadosDeclarados", incidente.getCantidadAfectados());
			}
		} else {
			caso.put("caso", armador.armar(incidente, tipo).estado());
			caso.put("condiciones", tipo.getCondiciones().stream().map(ConsultasDelAgente::condicion).toList());
		}
		evaluaciones.buscarUltimaHecha(incidenteId)
				.ifPresent(ultima -> caso.put("ultimosVeredictos", json.readTree(ultima.getVeredictos())));
		caso.put("necesita", necesita(incidente));
		caso.put("unidadesEnElCaso", atenciones.buscarActivasPorIncidente(incidenteId).stream()
				.map(ConsultasDelAgente::unidadEnElCaso)
				.toList());
		caso.put("asignacionesEsperandoConfirmacion", ofertas.buscarPendientesDeIncidente(incidenteId).size());
		caso.put("derivacionesAbiertas", derivaciones.buscarAbiertasDeIncidente(incidenteId).stream()
				.map(ConsultasDelAgente::derivacion)
				.toList());
		return caso;
	}

	/**
	 * La flota activa: tipo, estado y si tiene tripulación de turno. Con un incidente, además cuántos minutos tarda
	 * cada una en llegar, para las que tienen una posición conocida.
	 */
	@Transactional(readOnly = true)
	public Map<String, Object> verUnidades(Long incidenteId) {
		Incidente incidente = incidenteId == null ? null : incidente(incidenteId);
		Map<Long, Long> tripulantes = turnos.contarAbiertosPorUnidad().stream()
				.collect(Collectors.toMap(TripulantesEnTurno::ambulanciaId, TripulantesEnTurno::cantidad));
		List<Map<String, Object>> unidades = new ArrayList<>();
		for (Ambulancia ambulancia : ambulancias.findAllByOrderByPlacaAsc()) {
			if (!ambulancia.isActiva()) {
				continue;
			}
			Map<String, Object> unidad = new LinkedHashMap<>();
			unidad.put("placa", ambulancia.getPlaca());
			unidad.put("tipo", ambulancia.getTipoUnidad().name());
			unidad.put("estado", ambulancia.getEstado().name());
			unidad.put("tripulantesDeTurno", tripulantes.getOrDefault(ambulancia.getId(), 0L));
			if (incidente != null && ambulancia.getUltimaPosicion() != null) {
				unidad.put("minutosAlIncidente",
						estimador.minutosDeViaje(ambulancia.getUltimaPosicion(), incidente.getUbicacion()));
			}
			unidades.add(unidad);
		}
		Map<String, Object> flota = new LinkedHashMap<>();
		flota.put("unidades", unidades);
		flota.put("disponibles", unidades.stream().filter(unidad -> "DISPONIBLE".equals(unidad.get("estado"))).count());
		return flota;
	}

	/** Los incidentes abiertos de la central, del más viejo al más nuevo, con lo que necesita y tiene cada uno. */
	@Transactional(readOnly = true)
	public Map<String, Object> verCasosAbiertos() {
		Instant ahora = Instant.now();
		List<Map<String, Object>> abiertos = new ArrayList<>();
		for (Incidente incidente : incidentes.buscarAbiertos(PageRequest.of(0, MAXIMO_DE_CASOS_ABIERTOS))) {
			Map<String, Object> caso = new LinkedHashMap<>();
			caso.put("incidenteId", incidente.getId());
			caso.put("estado", incidente.getEstado().name());
			caso.put("tipoDeSuceso", incidente.getTipoSuceso());
			caso.put("afectadosDeclarados", incidente.getCantidadAfectados());
			caso.put("minutosAbierto", Duration.between(incidente.getFechaHoraCreacion(), ahora).toMinutes());
			caso.put("necesita", necesita(incidente));
			caso.put("unidadesEnElCaso", atenciones.buscarTiposDeUnidadVigentes(incidente.getId()).size());
			abiertos.add(caso);
		}
		return Map.of("incidentesAbiertos", abiertos);
	}

	/**
	 * Los traslados programados que salen en las próximas horas y todavía no terminaron: van a ocupar una unidad, y
	 * conviene saberlo antes de comprometer la última que queda libre.
	 */
	@Transactional(readOnly = true)
	public Map<String, Object> verTrasladosProximos(int horas) {
		int ventana = Math.max(1, Math.min(horas, config.horasDeTraslados()));
		Instant ahora = Instant.now();
		List<Map<String, Object>> proximos = new ArrayList<>();
		for (Traslado traslado : traslados.buscarEntre(ahora.minus(Duration.ofHours(1)),
				ahora.plus(Duration.ofHours(ventana)))) {
			if (!EstadoTraslado.VIGENTES.contains(traslado.getEstado())) {
				continue;
			}
			Map<String, Object> proximo = new LinkedHashMap<>();
			proximo.put("trasladoId", traslado.getId());
			proximo.put("estado", traslado.getEstado().name());
			proximo.put("tipoDeUnidad", traslado.tipoUnidadEfectivo().name());
			proximo.put("minutosParaSalir", Duration.between(ahora, traslado.getHoraSalidaEstimada()).toMinutes());
			proximos.add(proximo);
		}
		Map<String, Object> respuesta = new LinkedHashMap<>();
		respuesta.put("horasConsultadas", ventana);
		respuesta.put("traslados", proximos);
		return respuesta;
	}

	/**
	 * Incidentes ya cerrados del mismo tipo de suceso, del más reciente al más viejo: cómo terminaron, cuántas
	 * unidades fueron y qué condiciones se cumplieron. Sirven de referencia, no de regla.
	 */
	@Transactional(readOnly = true)
	public Map<String, Object> buscarCasosParecidos(long incidenteId) {
		Incidente incidente = incidente(incidenteId);
		if (incidente.getTipoSuceso() == null) {
			throw new ErrorDeHerramienta("El incidente " + incidenteId
					+ " no tiene un tipo de suceso declarado: no hay con qué buscar casos parecidos.");
		}
		List<Map<String, Object>> parecidos = new ArrayList<>();
		for (Incidente anterior : incidentes.buscarCerradosDelTipo(incidente.getTipoSuceso(), incidenteId,
				PageRequest.of(0, config.casosParecidos()))) {
			List<TipoUnidad> fueron = atenciones.buscarTiposDeUnidadVigentes(anterior.getId());
			Map<String, Object> caso = new LinkedHashMap<>();
			caso.put("incidenteId", anterior.getId());
			caso.put("comoTermino", anterior.getEstado().name());
			caso.put("motivoDeCierre", anterior.getMotivoCierre() == null ? null : anterior.getMotivoCierre().name());
			caso.put("afectadosDeclarados", anterior.getCantidadAfectados());
			caso.put("unidadesQueFueron", fueron.stream().map(Enum::name).toList());
			caso.put("necesitaba", necesita(anterior));
			caso.put("condicionesCumplidas", evaluaciones.buscarUltimaHecha(anterior.getId())
					.map(ultima -> cumplidas(json.readTree(ultima.getVeredictos())))
					.orElse(List.of()));
			parecidos.add(caso);
		}
		Map<String, Object> respuesta = new LinkedHashMap<>();
		respuesta.put("tipoDeSuceso", incidente.getTipoSuceso());
		respuesta.put("casos", parecidos);
		return respuesta;
	}

	/**
	 * Lo que el protocolo vigente dice sobre un tema: las condiciones cuyo texto, pregunta o cita nombran alguna de
	 * las palabras buscadas, con la respuesta que les asigna y de qué parte de la norma salen.
	 */
	@Transactional(readOnly = true)
	public Map<String, Object> buscarEnNorma(String consulta) {
		Set<String> palabras = palabras(consulta);
		if (palabras.isEmpty()) {
			throw new ErrorDeHerramienta("La consulta está vacía: escribe qué buscar, por ejemplo «persona atrapada».");
		}
		Protocolo vigente = protocolos.vigente().orElse(null);
		Map<String, Object> respuesta = new LinkedHashMap<>();
		if (vigente == null) {
			respuesta.put("fragmentos", List.of());
			respuesta.put("nota", "La central no tiene un protocolo publicado.");
			return respuesta;
		}
		record Hallazgo(int coincidencias, Map<String, Object> fragmento) {
		}
		List<Hallazgo> hallazgos = new ArrayList<>();
		for (TipoDeSuceso tipo : vigente.getTipos()) {
			for (Condicion condicion : tipo.getCondiciones()) {
				Set<String> texto = palabras(String.join(" ", tipo.getNombre(), condicion.getCodigo().replace('_', ' '),
						condicion.getEnunciado(), condicion.getPregunta() == null ? "" : condicion.getPregunta(),
						condicion.getCita() == null ? "" : condicion.getCita()));
				int coincidencias = (int) palabras.stream().filter(texto::contains).count();
				if (coincidencias > 0) {
					Map<String, Object> fragmento = new LinkedHashMap<>(condicion(condicion));
					fragmento.put("tipoDeSuceso", tipo.getCodigo());
					hallazgos.add(new Hallazgo(coincidencias, fragmento));
				}
			}
		}
		hallazgos.sort((uno, otro) -> Integer.compare(otro.coincidencias(), uno.coincidencias()));
		respuesta.put("versionDelProtocolo", vigente.getVersion());
		respuesta.put("fragmentos", hallazgos.stream().limit(MAXIMO_DE_FRAGMENTOS).map(Hallazgo::fragmento).toList());
		return respuesta;
	}

	private Incidente incidente(long incidenteId) {
		return incidentes.findById(incidenteId)
				.orElseThrow(() -> new ErrorDeHerramienta("No existe el incidente " + incidenteId + "."));
	}

	private static TipoDeSuceso tipoDe(Incidente incidente) {
		if (incidente.getTipoSuceso() == null || incidente.getProtocolo() == null) {
			return null;
		}
		return incidente.getProtocolo().buscarTipo(incidente.getTipoSuceso()).orElse(null);
	}

	private static Map<String, Object> necesita(Incidente incidente) {
		Map<String, Object> necesita = new LinkedHashMap<>();
		necesita.put("unidades", incidente.getUnidadesRequeridas());
		necesita.put("tipoMinimo", incidente.getTipoMinimoRequerido() == null ? null
				: incidente.getTipoMinimoRequerido().name());
		necesita.put("prioridad", incidente.getPrioridad() == null ? null : incidente.getPrioridad().name());
		return necesita;
	}

	private static Map<String, Object> condicion(Condicion condicion) {
		Map<String, Object> descrita = new LinkedHashMap<>();
		descrita.put("codigo", condicion.getCodigo());
		descrita.put("enunciado", condicion.getEnunciado());
		descrita.put("preguntaAlTestigo", condicion.getPregunta());
		descrita.put("siSeCumple", respuestaDe(condicion));
		descrita.put("cita", condicion.getCita());
		return descrita;
	}

	/** Lo que el protocolo manda hacer cuando la condición se cumple. */
	private static Map<String, Object> respuestaDe(Condicion condicion) {
		Map<String, Object> respuesta = new LinkedHashMap<>();
		respuesta.put("unidadesAdicionales", condicion.getUnidadesAdicionales());
		respuesta.put("tipoMinimo", condicion.getTipoMinimo() == null ? null : condicion.getTipoMinimo().name());
		respuesta.put("prioridad", condicion.getPrioridad() == null ? null : condicion.getPrioridad().name());
		respuesta.put("laVeUnaPersona", condicion.isDerivaAPersona());
		return respuesta;
	}

	private static Map<String, Object> unidadEnElCaso(Atencion atencion) {
		Map<String, Object> unidad = new LinkedHashMap<>();
		unidad.put("placa", atencion.getAmbulancia().getPlaca());
		unidad.put("tipo", atencion.getAmbulancia().getTipoUnidad().name());
		unidad.put("estado", atencion.getEstado().name());
		return unidad;
	}

	private static Map<String, Object> derivacion(Derivacion derivacion) {
		Map<String, Object> abierta = new HashMap<>();
		abierta.put("motivo", derivacion.getMotivo().name());
		abierta.put("condicion", derivacion.getCondicion());
		return abierta;
	}

	private static List<String> cumplidas(JsonNode veredictos) {
		List<String> cumplidas = new ArrayList<>();
		for (JsonNode veredicto : veredictos) {
			if ("CUMPLIDA".equals(veredicto.path("resultado").asString())) {
				cumplidas.add(veredicto.path("condicion").asString());
			}
		}
		return cumplidas;
	}

	/** Las palabras de un texto, en minúsculas y sin tildes, dejando afuera las de menos de cuatro letras. */
	private static Set<String> palabras(String texto) {
		if (texto == null) {
			return Set.of();
		}
		String plano = Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
				.toLowerCase(Locale.ROOT);
		return Arrays.stream(plano.split("[^a-z0-9]+"))
				.filter(palabra -> palabra.length() >= 4)
				.collect(Collectors.toSet());
	}

}
