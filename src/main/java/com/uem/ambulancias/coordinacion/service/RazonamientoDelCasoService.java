package com.uem.ambulancias.coordinacion.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.uem.ambulancias.comun.error.NoEncontradoException;
import com.uem.ambulancias.coordinacion.domain.ConclusionDelAgente;
import com.uem.ambulancias.coordinacion.domain.EstadoEvaluacion;
import com.uem.ambulancias.coordinacion.domain.EvaluacionDelCaso;
import com.uem.ambulancias.coordinacion.domain.FuenteDelVeredicto;
import com.uem.ambulancias.coordinacion.domain.MotivoDeDerivacion;
import com.uem.ambulancias.coordinacion.domain.RazonamientoDelCaso;
import com.uem.ambulancias.coordinacion.repository.RazonamientoDelCasoRepository;
import com.uem.ambulancias.emergencias.domain.Incidente;
import com.uem.ambulancias.emergencias.domain.OrigenDelEvento;
import com.uem.ambulancias.emergencias.domain.TipoEventoDelCaso;
import com.uem.ambulancias.emergencias.repository.IncidenteRepository;
import com.uem.ambulancias.emergencias.service.BitacoraDelCaso;
import com.uem.ambulancias.emergencias.service.RecursosDelIncidente;
import com.uem.ambulancias.evidencias.service.FalloDelServicioIa.Desenlace;
import com.uem.ambulancias.protocolo.domain.Condicion;
import com.uem.ambulancias.protocolo.domain.Prioridad;
import com.uem.ambulancias.protocolo.domain.TipoDeSuceso;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * El escalón del razonamiento (PB-40): antes de pasarle a una persona una condición que quedó en duda, se le pide al
 * agente que la mire con sus herramientas, en paralelo con la pregunta al testigo. Lo que el agente resuelve con su
 * porqué queda decidido hasta que llegue un dato nuevo; lo que no resuelve, o si el agente no responde, va a una
 * persona con lo que el agente alcanzó a decir, pero no antes de que venza el plazo del testigo. Sobre una condición
 * roja el agente solo puede confirmar: si concluye que no se cumple, también va a una persona.
 *
 * <p>Cada paso que toca la base va en su transacción corta, con el incidente bloqueado primero. La llamada al agente,
 * que tarda decenas de segundos, queda afuera.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RazonamientoDelCasoService {

	/** Con este nombre queda, en cada condición pedida, hasta cuándo podía contestar el testigo. */
	private static final String ESPERA_AL_TESTIGO = "esperaAlTestigoHasta";

	private final RazonamientoDelCasoRepository razonamientos;
	private final IncidenteRepository incidentes;
	private final ColaDeEvaluaciones cola;
	private final ArmadorDelEstado armador;
	private final Derivaciones derivaciones;
	private final RecursosDelIncidente recursos;
	private final BitacoraDelCaso bitacora;
	private final RazonamientoProperties config;
	private final ObjectProvider<Razonador> razonador;
	private final JsonMapper json;

	/** Si hay un agente encendido al que pedirle razonamientos. */
	public boolean disponible() {
		Razonador agente = razonador.getIfAvailable();
		return agente != null && agente.disponible();
	}

	/** Si el incidente ya tiene un razonamiento esperando o en curso: mientras tanto no se pide otro. */
	public boolean hayEnVuelo(Long incidenteId) {
		return razonamientos.existeEnVuelo(incidenteId);
	}

	/**
	 * Pide un razonamiento sobre esas condiciones. Cada una llega con el paquete que se le pasaría a una persona: si el
	 * agente no la resuelve, es lo que se deriva. El incidente llega bloqueado por quien llama.
	 *
	 * @param esperas hasta cuándo puede contestar el testigo, en las condiciones que todavía lo esperan: si el agente
	 *                no las resuelve antes, no se derivan hasta que venza ese plazo
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void pedir(Incidente incidente, EvaluacionDelCaso evaluacion, Map<String, Map<String, Object>> paquetes,
			Map<String, Instant> esperas) {
		List<Map<String, Object>> condiciones = new ArrayList<>();
		paquetes.forEach((codigo, paquete) -> {
			Instant esperaHasta = esperas.get(codigo);
			condiciones.add(BitacoraDelCaso.datos(
					"codigo", codigo,
					"paquete", paquete,
					ESPERA_AL_TESTIGO, esperaHasta == null ? null : esperaHasta.toString()));
		});
		razonamientos.save(RazonamientoDelCaso.pedir(incidente, evaluacion, json.writeValueAsString(condiciones),
				Instant.now()));
	}

	/**
	 * Lo que dijo el agente de una condición que miró sin resolverla.
	 *
	 * @param porQue       por qué le llega a una persona si no es el de siempre: {@code EL_AGENTE_NO_LA_CONFIRMA}
	 *                     cuando concluyó que una condición roja no se cumple; {@code null} en los demás casos
	 * @param razonamiento lo que la persona ve de ese razonamiento: su porqué y sus herramientas, o el error
	 */
	public record LoQueDijoElAgente(String porQue, Map<String, Object> razonamiento) {
	}

	/**
	 * Las condiciones que el agente ya miró sin resolverlas, sin que desde entonces haya llegado un dato del suceso:
	 * pedírselas otra vez sería pedirle lo mismo. Vale el último razonamiento que miró cada una, si terminó o falló.
	 * Exige una transacción abierta.
	 */
	public Map<String, LoQueDijoElAgente> yaMiradasSinResolver(Long incidenteId, TipoDeSuceso tipo) {
		List<RazonamientoDelCaso> delIncidente = razonamientos.findByIncidenteIdOrderByIdAsc(incidenteId);
		if (delIncidente.isEmpty()) {
			return Map.of();
		}
		Long ultimoDato = armador.ultimoDatoDelSuceso(incidenteId);
		Map<String, LoQueDijoElAgente> miradas = new LinkedHashMap<>();
		Set<String> vistas = new HashSet<>();
		for (RazonamientoDelCaso razonamiento : delIncidente.reversed()) {
			EstadoEvaluacion estado = razonamiento.getEstado();
			if (estado != EstadoEvaluacion.HECHA && estado != EstadoEvaluacion.FALLIDA
					|| razonamiento.getHastaEvento() == null) {
				continue;
			}
			boolean sinDatosNuevos = ultimoDato == null || razonamiento.getHastaEvento() >= ultimoDato;
			Map<String, JsonNode> conclusiones = new LinkedHashMap<>();
			if (razonamiento.getConclusiones() != null) {
				for (JsonNode conclusion : json.readTree(razonamiento.getConclusiones())) {
					conclusiones.putIfAbsent(conclusion.path("condicion").asString(), conclusion);
				}
			}
			for (JsonNode pedida : json.readTree(razonamiento.getCondiciones())) {
				String codigo = pedida.path("codigo").asString();
				// De cada condición vale el razonamiento más nuevo; si después llegó un dato, ya no cuenta ninguno.
				if (!vistas.add(codigo) || !sinDatosNuevos) {
					continue;
				}
				if (estado == EstadoEvaluacion.FALLIDA) {
					miradas.put(codigo, new LoQueDijoElAgente(null, BitacoraDelCaso.datos(
							"error", razonamiento.getUltimoError(),
							"razonamientoId", razonamiento.getId())));
					continue;
				}
				JsonNode conclusion = conclusiones.get(codigo);
				ConclusionDelAgente veredicto = conclusion == null ? ConclusionDelAgente.NO_SE_SABE
						: ConclusionDelAgente.valueOf(conclusion.path("veredicto").asString());
				Prioridad prioridad = tipo.buscarCondicion(codigo).map(Condicion::getPrioridad).orElse(null);
				if (LoQueNoSeResuelve.seAplica(veredicto, prioridad)) {
					continue;
				}
				miradas.put(codigo, new LoQueDijoElAgente(
						veredicto == ConclusionDelAgente.NO_CUMPLE ? LoQueNoSeResuelve.EL_AGENTE_NO_LA_CONFIRMA : null,
						BitacoraDelCaso.datos(
								"veredicto", veredicto == ConclusionDelAgente.NO_CUMPLE ? veredicto.name() : null,
								"justificacion", conclusion == null ? null : texto(conclusion.path("justificacion")),
								"herramientas", razonamiento.getHerramientas() == null ? null
										: json.readTree(razonamiento.getHerramientas()),
								"razonamientoId", razonamiento.getId())));
			}
		}
		return miradas;
	}

	private static String texto(JsonNode nodo) {
		return nodo.isString() ? nodo.asString() : null;
	}

	/** Toma hasta {@code limite} razonamientos y los deja EN_CURSO. */
	@Transactional
	public List<Long> tomar(int limite) {
		Instant ahora = Instant.now();
		List<Long> tomados = new ArrayList<>();
		for (RazonamientoDelCaso razonamiento : razonamientos.tomarDisponibles(ahora, limite)) {
			razonamiento.tomar(ahora, config.bloqueo());
			tomados.add(razonamiento.getId());
		}
		return tomados;
	}

	/**
	 * Arma lo que se le manda al agente. Devuelve vacío si ya no hay nada que razonar: el incidente se cerró, una
	 * unidad llegó al lugar o una persona decidió esas condiciones en el medio.
	 */
	@Transactional
	public Optional<PedidoDeRazonamiento> preparar(Long razonamientoId) {
		Enlazado enlazado = bloquear(razonamientoId).orElse(null);
		if (enlazado == null) {
			return Optional.empty();
		}
		RazonamientoDelCaso razonamiento = enlazado.razonamiento();
		Incidente incidente = enlazado.incidente();
		Instant ahora = Instant.now();
		String motivo = cola.motivoParaNoEvaluar(incidente);
		if (motivo != null) {
			razonamiento.descartar(motivo, ahora);
			return Optional.empty();
		}
		TipoDeSuceso tipo = tipoDe(incidente);
		ArmadorDelEstado.EstadoArmado armado = armador.armar(incidente, tipo);
		List<PedidoDeEvaluacion.Pregunta> preguntas = new ArrayList<>();
		for (JsonNode pedida : json.readTree(razonamiento.getCondiciones())) {
			String codigo = pedida.path("codigo").asString();
			if (!seSigueRazonando(codigo, armado, incidente)) {
				continue;
			}
			tipo.buscarCondicion(codigo).ifPresent(
					condicion -> preguntas.add(new PedidoDeEvaluacion.Pregunta(codigo, condicion.getEnunciado())));
		}
		if (preguntas.isEmpty()) {
			razonamiento.descartar("sin_condiciones_por_razonar", ahora);
			return Optional.empty();
		}
		String estadoJson = json.writeValueAsString(armado.estado());
		razonamiento.registrarEstado(estadoJson, armado.hastaEvento());
		return Optional.of(new PedidoDeRazonamiento(razonamiento.getReferencia(), incidente.getId(), estadoJson,
				preguntas));
	}

	/**
	 * Llegaron las conclusiones del agente. Lo que resolvió queda decidido, con su porqué; si la condición se cumple,
	 * el incidente pasa a necesitar lo que el protocolo le asigna. Lo que dejó sin resolver, y el «no se cumple» de una
	 * condición roja, va a una persona; si el testigo todavía puede contestar, recién cuando venza su plazo.
	 */
	@Transactional
	public void concluir(Long razonamientoId, ConclusionesRecibidas recibidas, long milisegundos) {
		Enlazado enlazado = bloquear(razonamientoId).orElse(null);
		if (enlazado == null) {
			log.warn("El razonamiento {} ya no estaba en curso al llegar su respuesta: se descarta.", razonamientoId);
			return;
		}
		RazonamientoDelCaso razonamiento = enlazado.razonamiento();
		Incidente incidente = enlazado.incidente();
		Instant ahora = Instant.now();
		Map<String, ConclusionesRecibidas.Conclusion> porCondicion = new LinkedHashMap<>();
		for (ConclusionesRecibidas.Conclusion conclusion : recibidas.conclusiones()) {
			porCondicion.putIfAbsent(conclusion.condicion(), conclusion);
		}
		List<Map<String, Object>> detalle = new ArrayList<>();
		for (ConclusionesRecibidas.Conclusion conclusion : porCondicion.values()) {
			detalle.add(BitacoraDelCaso.datos(
					"condicion", conclusion.condicion(),
					"veredicto", conclusion.veredicto().name(),
					"justificacion", conclusion.justificacion(),
					"fuentes", conclusion.fuentes()));
		}
		razonamiento.concluir(json.writeValueAsString(detalle), json.writeValueAsString(recibidas.herramientas()),
				recibidas.proveedor(), recibidas.modelo(), recibidas.versionDelPrompt(), recibidas.tokensDeEntrada(),
				recibidas.tokensDeSalida(), recibidas.pedidosAlModelo(), milisegundos, ahora);
		String motivo = cola.motivoParaNoEvaluar(incidente);
		if (motivo != null) {
			// Las conclusiones quedan guardadas, pero ya no se aplican: el caso lo resuelve quien está en el lugar.
			razonamiento.descartar(motivo, ahora);
			return;
		}
		bitacora.anotar(incidente, null, TipoEventoDelCaso.RAZONAMIENTO_DEL_AGENTE, OrigenDelEvento.AGENTE, ahora,
				BitacoraDelCaso.datos(
						"razonamientoId", razonamiento.getId(),
						"modelo", recibidas.modelo(),
						"herramientas", recibidas.herramientas(),
						"pedidosAlModelo", recibidas.pedidosAlModelo(),
						"conclusiones", detalle));
		TipoDeSuceso tipo = tipoDe(incidente);
		ArmadorDelEstado.EstadoArmado armado = armador.armar(incidente, tipo);
		for (JsonNode pedida : json.readTree(razonamiento.getCondiciones())) {
			String codigo = pedida.path("codigo").asString();
			Condicion condicion = tipo.buscarCondicion(codigo).orElse(null);
			if (condicion == null || !seSigueRazonando(codigo, armado, incidente)) {
				continue;
			}
			ConclusionesRecibidas.Conclusion conclusion = porCondicion.get(codigo);
			ConclusionDelAgente veredicto = conclusion == null ? ConclusionDelAgente.NO_SE_SABE : conclusion.veredicto();
			if (!LoQueNoSeResuelve.seAplica(veredicto, condicion.getPrioridad())) {
				// Mientras el testigo puede contestar no se le pasa a nadie: la evaluación que espera su plazo la
				// deriva con lo que dijo el agente, sin volver a pedírselo si no llegó nada nuevo.
				if (!LoQueNoSeResuelve.esperaAlTestigo(esperaDe(pedida), ahora)) {
					derivar(razonamiento, incidente, codigo, pedida.path("paquete"),
							LoQueNoSeResuelve.porQueNoSeAplica(veredicto, null), BitacoraDelCaso.datos(
									"veredicto", veredicto == ConclusionDelAgente.NO_CUMPLE ? veredicto.name() : null,
									"justificacion", conclusion == null ? null : conclusion.justificacion(),
									"herramientas", recibidas.herramientas()));
				}
				continue;
			}
			boolean cumple = veredicto == ConclusionDelAgente.CUMPLE;
			bitacora.anotar(incidente, null, TipoEventoDelCaso.CONDICION_DECIDIDA, OrigenDelEvento.AGENTE, ahora,
					BitacoraDelCaso.datos(
							"condicion", codigo,
							"cumple", cumple,
							"por", FuenteDelVeredicto.AGENTE.name(),
							"razonamientoId", razonamiento.getId(),
							"hastaEvento", razonamiento.getHastaEvento(),
							"justificacion", conclusion.justificacion(),
							"fuentes", conclusion.fuentes()));
			if (cumple) {
				recursos.requerir(incidente, 1 + condicion.getUnidadesAdicionales(), condicion.getTipoMinimo(),
						condicion.getPrioridad(), BitacoraDelCaso.datos(
								"razonamientoId", razonamiento.getId(),
								"porCondiciones", List.of(codigo)));
			}
		}
		volverAEvaluar(incidente, ahora);
	}

	/**
	 * Al terminar un razonamiento, el caso se vuelve a evaluar. Mientras el agente razonaba no se le pidió otro, y
	 * pudo quedar otra condición sin resolver esperando: esa evaluación la encuentra. Si ya hay una esperando, alcanza
	 * con esa.
	 */
	private void volverAEvaluar(Incidente incidente, Instant ahora) {
		cola.pedirParaDespues(incidente, "RAZONAMIENTO_TERMINADO", ahora);
	}

	/** Hasta cuándo esperaba al testigo esa condición cuando se le pidió al agente, o {@code null}. */
	private static Instant esperaDe(JsonNode pedida) {
		JsonNode espera = pedida.path(ESPERA_AL_TESTIGO);
		return espera.isString() ? Instant.parse(espera.asString()) : null;
	}

	/**
	 * El agente no respondió. Si fue algo pasajero y quedan intentos, se vuelve a pedir. Si no, las condiciones van a
	 * una persona: el razonamiento es una ayuda, no una condición para seguir.
	 */
	@Transactional
	public void sinRespuesta(Long razonamientoId, String error, Desenlace desenlace) {
		Enlazado enlazado = bloquear(razonamientoId).orElse(null);
		if (enlazado == null) {
			return;
		}
		RazonamientoDelCaso razonamiento = enlazado.razonamiento();
		Incidente incidente = enlazado.incidente();
		Instant ahora = Instant.now();
		boolean pasajero = desenlace == Desenlace.REINTENTAR || desenlace == Desenlace.FIRMAR_DE_NUEVO;
		if (pasajero && !razonamiento.agotoIntentos(config.maximoIntentos())) {
			razonamiento.reintentar(error, ahora, config.espera());
			return;
		}
		razonamiento.fallar(error, ahora);
		if (cola.motivoParaNoEvaluar(incidente) != null) {
			return;
		}
		ArmadorDelEstado.EstadoArmado armado = armador.armar(incidente, tipoDe(incidente));
		for (JsonNode pedida : json.readTree(razonamiento.getCondiciones())) {
			String codigo = pedida.path("codigo").asString();
			// La que todavía espera al testigo la deriva la evaluación que espera su plazo, si no contesta.
			if (seSigueRazonando(codigo, armado, incidente)
					&& !LoQueNoSeResuelve.esperaAlTestigo(esperaDe(pedida), ahora)) {
				derivar(razonamiento, incidente, codigo, pedida.path("paquete"), null,
						BitacoraDelCaso.datos("error", error));
			}
		}
		volverAEvaluar(incidente, ahora);
	}

	/** Los razonamientos del incidente, del más viejo al más nuevo. */
	@Transactional(readOnly = true)
	public List<RazonamientoDelCaso> deIncidente(Long incidenteId) {
		if (!incidentes.existsById(incidenteId)) {
			throw new NoEncontradoException("No existe el incidente " + incidenteId + ".");
		}
		return razonamientos.findByIncidenteIdOrderByIdAsc(incidenteId);
	}

	/**
	 * Una condición deja de razonarse si en el medio la decidió una persona, o se dio por cumplida ante la duda, o ya
	 * se le pasó a una persona.
	 */
	private boolean seSigueRazonando(String codigo, ArmadorDelEstado.EstadoArmado armado, Incidente incidente) {
		JuicioDelProtocolo.Decidida decidida = armado.hechos().decididas().get(codigo);
		boolean decididaPorOtro = decidida != null && decidida.fuente() != FuenteDelVeredicto.AGENTE;
		return !decididaPorOtro && !derivaciones.yaDerivado(incidente.getId(), clave(codigo));
	}

	/** @param porQue el motivo que reemplaza al del paquete, o {@code null} para dejar el del paquete */
	private void derivar(RazonamientoDelCaso razonamiento, Incidente incidente, String codigo, JsonNode paquete,
			String porQue, Map<String, Object> delAgente) {
		Map<String, Object> completo = new LinkedHashMap<>();
		if (paquete.isObject()) {
			paquete.properties().forEach(campo -> completo.put(campo.getKey(), campo.getValue()));
		}
		if (porQue != null) {
			completo.put("porQue", porQue);
		}
		Map<String, Object> razonado = new LinkedHashMap<>(delAgente);
		razonado.put("razonamientoId", razonamiento.getId());
		completo.put("razonamiento", razonado);
		derivaciones.derivar(incidente, MotivoDeDerivacion.CONDICION_SIN_RESOLVER, clave(codigo), codigo, completo,
				razonamiento.getEvaluacion());
	}

	private static String clave(String codigo) {
		return MotivoDeDerivacion.CONDICION_SIN_RESOLVER.name() + ":" + codigo;
	}

	private record Enlazado(RazonamientoDelCaso razonamiento, Incidente incidente) {
	}

	/** Bloquea el incidente y después el razonamiento. Vacío si el razonamiento ya no está en curso. */
	private Optional<Enlazado> bloquear(Long razonamientoId) {
		Long incidenteId = razonamientos.buscarIncidenteId(razonamientoId).orElse(null);
		if (incidenteId == null) {
			return Optional.empty();
		}
		Incidente incidente = incidentes.buscarParaActualizar(incidenteId).orElse(null);
		RazonamientoDelCaso razonamiento = razonamientos.buscarParaActualizar(razonamientoId).orElse(null);
		if (incidente == null || razonamiento == null || !razonamiento.isEnCurso()) {
			return Optional.empty();
		}
		return Optional.of(new Enlazado(razonamiento, incidente));
	}

	private static TipoDeSuceso tipoDe(Incidente incidente) {
		return incidente.getProtocolo().buscarTipo(incidente.getTipoSuceso())
				.orElseThrow(() -> new IllegalStateException("El incidente " + incidente.getId()
						+ " señala un tipo de suceso que su protocolo no tiene."));
	}

}
