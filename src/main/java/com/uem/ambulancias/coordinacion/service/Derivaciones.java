package com.uem.ambulancias.coordinacion.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.uem.ambulancias.comun.error.NoEncontradoException;
import com.uem.ambulancias.coordinacion.domain.Derivacion;
import com.uem.ambulancias.coordinacion.domain.EvaluacionDelCaso;
import com.uem.ambulancias.coordinacion.domain.FuenteDelVeredicto;
import com.uem.ambulancias.coordinacion.domain.MotivoDeDerivacion;
import com.uem.ambulancias.coordinacion.domain.OpcionDeDerivacion;
import com.uem.ambulancias.coordinacion.repository.DerivacionRepository;
import com.uem.ambulancias.emergencias.domain.EstadoAtencion;
import com.uem.ambulancias.emergencias.domain.EventoDelCaso;
import com.uem.ambulancias.emergencias.domain.Incidente;
import com.uem.ambulancias.emergencias.domain.OrigenDelEvento;
import com.uem.ambulancias.emergencias.domain.TipoEventoDelCaso;
import com.uem.ambulancias.emergencias.repository.AtencionRepository;
import com.uem.ambulancias.emergencias.repository.EventoDelCasoRepository;
import com.uem.ambulancias.emergencias.repository.IncidenteRepository;
import com.uem.ambulancias.emergencias.service.BitacoraDelCaso;
import com.uem.ambulancias.emergencias.service.IncidenteActualizado;
import com.uem.ambulancias.emergencias.service.IncidenteMasUrgente;
import com.uem.ambulancias.emergencias.service.IncidenteSinUnidadParaAsignar;
import com.uem.ambulancias.emergencias.service.RecursosDelIncidente;
import com.uem.ambulancias.protocolo.domain.Condicion;
import com.uem.ambulancias.protocolo.domain.Prioridad;
import com.uem.ambulancias.usuarios.domain.RolUsuario;
import com.uem.ambulancias.usuarios.domain.Usuario;
import com.uem.ambulancias.usuarios.repository.UsuarioRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * La derivación a una persona: cuando el sistema no puede o no debe decidir solo, le pasa el asunto a quien está en
 * la central, con lo que necesita para decidir, las opciones y un tiempo límite. Si nadie contesta a tiempo se aplica
 * la opción segura del motivo, y la derivación queda a la vista hasta que alguien la marque como vista.
 *
 * <p>Los bloqueos van en el orden de siempre: primero el incidente, después la derivación.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Derivaciones {

	private final DerivacionRepository derivaciones;
	private final IncidenteRepository incidentes;
	private final AtencionRepository atenciones;
	private final EventoDelCasoRepository renglones;
	private final UsuarioRepository usuarios;
	private final RecursosDelIncidente recursos;
	private final BitacoraDelCaso bitacora;
	private final DerivacionProperties config;
	private final ApplicationEventPublisher eventos;
	private final JsonMapper json;

	/**
	 * Le pasa a una persona un asunto del incidente, que llega bloqueado por quien llama. El mismo asunto no se deriva
	 * dos veces: si ya se derivó, esté como esté, devuelve vacío.
	 *
	 * @param clave   el asunto dentro del incidente
	 * @param paquete lo que la persona necesita ver para decidir
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public Optional<Derivacion> derivar(Incidente incidente, MotivoDeDerivacion motivo, String clave,
			String condicion, Map<String, Object> paquete, EvaluacionDelCaso evaluacion) {
		if (derivaciones.existsByIncidenteIdAndClave(incidente.getId(), clave)) {
			return Optional.empty();
		}
		Instant ahora = Instant.now();
		Derivacion derivacion = derivaciones.save(Derivacion.abrir(incidente, motivo, clave, condicion,
				json.writeValueAsString(paquete), evaluacion, ahora, config.respuesta()));
		bitacora.anotar(incidente, null, TipoEventoDelCaso.DERIVADO_A_PERSONA, OrigenDelEvento.SISTEMA, ahora,
				BitacoraDelCaso.datos(
						"derivacionId", derivacion.getId(),
						"motivo", motivo.name(),
						"condicion", condicion,
						"opciones", motivo.getOpciones().stream().map(Enum::name).toList(),
						"opcionSegura", motivo.getOpcionSegura().name(),
						"segundosParaResponder", config.segundosRespuesta(),
						"evaluacionId", evaluacion == null ? null : evaluacion.getId()));
		eventos.publishEvent(new DerivacionCambiada(derivacion.getId()));
		return Optional.of(derivacion);
	}

	/** Si ese asunto del incidente ya se le pasó a una persona, haya terminado como haya terminado. */
	public boolean yaDerivado(Long incidenteId, String clave) {
		return derivaciones.existsByIncidenteIdAndClave(incidenteId, clave);
	}

	/** La persona de la central contesta. Si su respuesta tiene un efecto, se aplica en el momento. */
	@Transactional
	public Derivacion decidir(Long derivacionId, Long administradorId, OpcionDeDerivacion opcion, String nota) {
		Usuario persona = administrador(administradorId);
		Derivacion derivacion = bloquear(derivacionId);
		Incidente incidente = derivacion.getIncidente();
		Instant ahora = Instant.now();
		derivacion.resolver(opcion, persona, nota, ahora);
		bitacora.anotar(incidente, null, TipoEventoDelCaso.DERIVACION_RESUELTA, OrigenDelEvento.CENTRAL, ahora,
				BitacoraDelCaso.datos(
						"derivacionId", derivacion.getId(),
						"motivo", derivacion.getMotivo().name(),
						"condicion", derivacion.getCondicion(),
						"opcion", opcion.name(),
						"usuarioId", persona.getId(),
						"nota", nota,
						"segundosEnResponder", Duration.between(derivacion.getCreadaEn(), ahora).toSeconds()));
		aplicar(derivacion, incidente, opcion, FuenteDelVeredicto.CENTRAL, ahora);
		eventos.publishEvent(new DerivacionCambiada(derivacion.getId()));
		return cargada(derivacion);
	}

	/**
	 * Da por vencida una derivación que se quedó sin respuesta y aplica la opción segura de su motivo. No hace nada si
	 * en el medio alguien contestó.
	 */
	@Transactional
	public void vencer(Long derivacionId, Instant ahora) {
		Long incidenteId = derivaciones.buscarIncidenteId(derivacionId).orElse(null);
		if (incidenteId == null) {
			return;
		}
		Incidente incidente = incidentes.buscarParaActualizar(incidenteId).orElse(null);
		Derivacion derivacion = derivaciones.buscarParaActualizar(derivacionId).orElse(null);
		if (incidente == null || derivacion == null || !derivacion.vencio(ahora)) {
			return;
		}
		OpcionDeDerivacion segura = derivacion.vencer(ahora);
		log.warn("La derivación {} del incidente {} venció sin respuesta: se aplica {}.", derivacionId, incidenteId,
				segura);
		bitacora.anotar(incidente, null, TipoEventoDelCaso.DERIVACION_VENCIDA, OrigenDelEvento.SISTEMA, ahora,
				BitacoraDelCaso.datos(
						"derivacionId", derivacion.getId(),
						"motivo", derivacion.getMotivo().name(),
						"condicion", derivacion.getCondicion(),
						"opcionAplicada", segura.name()));
		aplicar(derivacion, incidente, segura, FuenteDelVeredicto.ANTE_LA_DUDA, ahora);
		eventos.publishEvent(new DerivacionCambiada(derivacion.getId()));
	}

	/** Las derivaciones que ya deberían haber tenido respuesta. */
	@Transactional(readOnly = true)
	public List<Long> vencidas(Instant ahora) {
		return derivaciones.buscarIdsVencidas(ahora);
	}

	/** Alguien de la central se da por enterado de una derivación que venció sin respuesta. */
	@Transactional
	public Derivacion marcarVista(Long derivacionId, Long administradorId) {
		Usuario persona = administrador(administradorId);
		Derivacion derivacion = bloquear(derivacionId);
		derivacion.marcarVista(persona, Instant.now());
		eventos.publishEvent(new DerivacionCambiada(derivacion.getId()));
		return cargada(derivacion);
	}

	/**
	 * Cada cambio de un incidente pasa por acá después de confirmarse. Si el incidente se cerró o una unidad ya llegó
	 * al lugar, las derivaciones que esperaban respuesta dejan de hacer falta: desde ahí decide quien está parado
	 * allí.
	 */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void alActualizarseElIncidente(IncidenteActualizado evento) {
		// La mayoría de los incidentes no tiene nada derivado: se mira antes de bloquear.
		if (derivaciones.buscarAbiertasDeIncidente(evento.incidenteId()).isEmpty()) {
			return;
		}
		Incidente incidente = incidentes.buscarParaActualizar(evento.incidenteId()).orElse(null);
		if (incidente == null) {
			return;
		}
		String razon = !incidente.getEstado().isAbierto() ? "incidente_cerrado"
				: atenciones.existeLlegadaVigentePorIncidente(incidente.getId()) ? "unidad_en_el_lugar" : null;
		if (razon == null) {
			return;
		}
		Instant ahora = Instant.now();
		for (Derivacion derivacion : derivaciones.buscarAbiertasDeIncidente(incidente.getId())) {
			derivacion.retirar(razon, ahora);
			bitacora.anotar(incidente, null, TipoEventoDelCaso.DERIVACION_RETIRADA, OrigenDelEvento.SISTEMA, ahora,
					BitacoraDelCaso.datos(
							"derivacionId", derivacion.getId(),
							"motivo", derivacion.getMotivo().name(),
							"condicion", derivacion.getCondicion(),
							"razon", razon));
			eventos.publishEvent(new DerivacionCambiada(derivacion.getId()));
		}
	}

	/**
	 * En modo asignación no quedó ninguna unidad propia para el incidente, o ninguna del tipo que hace falta. El
	 * incidente ya se abrió a todas las de turno; además, que lo vea una persona, que es quien puede pedir apoyo a
	 * otra institución. Mientras tenga una derivación así esperando respuesta, no se abre otra.
	 */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void alQuedarSinUnidad(IncidenteSinUnidadParaAsignar evento) {
		Incidente incidente = incidentes.buscarParaActualizar(evento.incidenteId()).orElse(null);
		if (incidente == null || !incidente.getEstado().isAbierto()
				|| derivaciones.existeAbierta(incidente.getId(), MotivoDeDerivacion.SIN_UNIDAD.name())) {
			return;
		}
		EventoDelCaso renglon = renglones.findFirstByIncidenteIdAndTipoOrderByIdDesc(incidente.getId(),
				TipoEventoDelCaso.SIN_UNIDAD_PARA_ASIGNAR.name()).orElse(null);
		if (renglon == null) {
			return;
		}
		JsonNode datos = json.readTree(renglon.getDatos());
		derivar(incidente, MotivoDeDerivacion.SIN_UNIDAD, MotivoDeDerivacion.SIN_UNIDAD.name() + ":" + renglon.getId(),
				null, BitacoraDelCaso.datos(
						"unidadesQueFaltan", datos.path("unidadesQueFaltan").isNumber()
								? datos.path("unidadesQueFaltan").asInt() : null,
						"tipoQueFalta", datos.path("tipoQueFalta").isString()
								? datos.path("tipoQueFalta").asString() : null,
						"unidadesRequeridas", incidente.getUnidadesRequeridas(),
						"tipoMinimoRequerido", incidente.getTipoMinimoRequerido() == null ? null
								: incidente.getTipoMinimoRequerido().name(),
						"prioridad", incidente.getPrioridad() == null ? null : incidente.getPrioridad().name(),
						"unidadesEnCasosMenores", unidadesEnCasosMenores(incidente)),
				null);
	}

	/**
	 * El incidente pasó a ser más urgente mientras esperaba una unidad. La derivación por falta de unidad se abrió
	 * antes de saberlo: se le agrega la prioridad y qué unidades están en casos menos urgentes, que es lo que la
	 * persona necesita para decidir si mueve una.
	 */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void alVolverseMasUrgente(IncidenteMasUrgente evento) {
		Incidente incidente = incidentes.buscarParaActualizar(evento.incidenteId()).orElse(null);
		if (incidente == null || !incidente.getEstado().isAbierto() || incidente.getPrioridad() == null) {
			return;
		}
		for (Derivacion derivacion : derivaciones.buscarAbiertasDeIncidente(incidente.getId())) {
			if (derivacion.getMotivo() != MotivoDeDerivacion.SIN_UNIDAD) {
				continue;
			}
			ObjectNode detalle = (ObjectNode) json.readTree(derivacion.getDetalle());
			detalle.put("prioridad", incidente.getPrioridad().name());
			List<Map<String, Object>> unidades = unidadesEnCasosMenores(incidente);
			if (unidades == null) {
				detalle.remove("unidadesEnCasosMenores");
			} else {
				detalle.set("unidadesEnCasosMenores", json.valueToTree(unidades));
			}
			derivacion.completarDetalle(json.writeValueAsString(detalle));
			eventos.publishEvent(new DerivacionCambiada(derivacion.getId()));
		}
	}

	/**
	 * Las unidades que están atendiendo un caso menos urgente que este. Se le muestran a la persona cuando a un caso
	 * amarillo o rojo le falta una unidad: mover una lo decide ella, el sistema no le quita una unidad a nadie.
	 */
	private List<Map<String, Object>> unidadesEnCasosMenores(Incidente incidente) {
		Prioridad prioridad = incidente.getPrioridad();
		if (prioridad == null || prioridad == Prioridad.VERDE) {
			return null;
		}
		List<Map<String, Object>> unidades = atenciones
				.buscarActivasDeOtrosIncidentes(incidente.getId(), EstadoAtencion.ACTIVOS).stream()
				.filter(atencion -> prioridad.masUrgenteQue(atencion.getIncidente().getPrioridad()))
				.map(atencion -> BitacoraDelCaso.datos(
						"incidenteId", atencion.getIncidente().getId(),
						"placa", atencion.getAmbulancia().getPlaca(),
						"tipoUnidad", atencion.getAmbulancia().getTipoUnidad().name(),
						"estado", atencion.getEstado().name(),
						"prioridad", atencion.getIncidente().getPrioridad() == null ? null
								: atencion.getIncidente().getPrioridad().name()))
				.toList();
		return unidades.isEmpty() ? null : unidades;
	}

	/**
	 * Las que le hacen falta a una persona: primero las de los incidentes de prioridad más urgente y, a igual
	 * prioridad, la que vence antes.
	 */
	@Transactional(readOnly = true)
	public List<Derivacion> queNecesitanAtencion() {
		return derivaciones.buscarQueNecesitanAtencion().stream().map(Derivaciones::cargada)
				.sorted(Comparator.comparingInt(
						(Derivacion derivacion) -> -Prioridad.nivelDe(derivacion.getIncidente().getPrioridad())))
				.toList();
	}

	/** Las últimas, con cualquier estado. */
	@Transactional(readOnly = true)
	public List<Derivacion> recientes(int limite) {
		return derivaciones.buscarRecientes(PageRequest.of(0, limite)).stream().map(Derivaciones::cargada).toList();
	}

	@Transactional(readOnly = true)
	public List<Derivacion> deIncidente(Long incidenteId) {
		if (!incidentes.existsById(incidenteId)) {
			throw new NoEncontradoException("No existe el incidente " + incidenteId + ".");
		}
		return derivaciones.findByIncidenteIdOrderByIdAsc(incidenteId).stream().map(Derivaciones::cargada).toList();
	}

	@Transactional(readOnly = true)
	public Derivacion buscar(Long derivacionId) {
		return derivaciones.findById(derivacionId).map(Derivaciones::cargada)
				.orElseThrow(() -> new NoEncontradoException("No existe la derivación " + derivacionId + "."));
	}

	@Transactional(readOnly = true)
	public long cuantasNecesitanAtencion() {
		return derivaciones.contarQueNecesitanAtencion();
	}

	/**
	 * El efecto de una respuesta. Solo las derivaciones por una condición sin resolver tienen uno: la condición queda
	 * decidida, por la central o ante la duda, y si se cumple el incidente pasa a necesitar lo que el protocolo le
	 * asigna. Las demás respuestas dejan constancia y nada más: lo que hay que hacer lo hace la persona.
	 */
	private void aplicar(Derivacion derivacion, Incidente incidente, OpcionDeDerivacion opcion,
			FuenteDelVeredicto fuente, Instant ahora) {
		if (derivacion.getMotivo() != MotivoDeDerivacion.CONDICION_SIN_RESOLVER) {
			return;
		}
		boolean cumple = opcion == OpcionDeDerivacion.SE_CUMPLE;
		bitacora.anotar(incidente, null, TipoEventoDelCaso.CONDICION_DECIDIDA,
				fuente == FuenteDelVeredicto.CENTRAL ? OrigenDelEvento.CENTRAL : OrigenDelEvento.SISTEMA, ahora,
				BitacoraDelCaso.datos(
						"condicion", derivacion.getCondicion(),
						"cumple", cumple,
						"por", fuente.name(),
						"derivacionId", derivacion.getId()));
		if (!cumple || !incidente.getEstado().isAbierto() || incidente.getProtocolo() == null) {
			return;
		}
		incidente.getProtocolo().buscarTipo(incidente.getTipoSuceso())
				.flatMap(tipo -> tipo.buscarCondicion(derivacion.getCondicion()))
				.ifPresent(condicion -> requerir(incidente, condicion, derivacion));
	}

	private void requerir(Incidente incidente, Condicion condicion, Derivacion derivacion) {
		recursos.requerir(incidente, 1 + condicion.getUnidadesAdicionales(), condicion.getTipoMinimo(),
				condicion.getPrioridad(), BitacoraDelCaso.datos(
						"derivacionId", derivacion.getId(),
						"porCondiciones", List.of(condicion.getCodigo())));
	}

	private Usuario administrador(Long administradorId) {
		return usuarios.findByIdAndRol(administradorId, RolUsuario.ADMIN)
				.orElseThrow(() -> new NoEncontradoException("No existe el administrador " + administradorId + "."));
	}

	/** Bloquea el incidente y después la derivación. */
	private Derivacion bloquear(Long derivacionId) {
		Long incidenteId = derivaciones.buscarIncidenteId(derivacionId)
				.orElseThrow(() -> new NoEncontradoException("No existe la derivación " + derivacionId + "."));
		incidentes.buscarParaActualizar(incidenteId);
		return derivaciones.buscarParaActualizar(derivacionId)
				.orElseThrow(() -> new NoEncontradoException("No existe la derivación " + derivacionId + "."));
	}

	/** Deja cargado lo que la respuesta muestra, para poder armarla fuera de la transacción. */
	private static Derivacion cargada(Derivacion derivacion) {
		derivacion.getIncidente().getUbicacion();
		if (derivacion.getResueltaPor() != null) {
			derivacion.getResueltaPor().getNombreCompleto();
		}
		if (derivacion.getVistaPor() != null) {
			derivacion.getVistaPor().getNombreCompleto();
		}
		if (derivacion.getEvaluacion() != null) {
			derivacion.getEvaluacion().getId();
		}
		return derivacion;
	}

}
