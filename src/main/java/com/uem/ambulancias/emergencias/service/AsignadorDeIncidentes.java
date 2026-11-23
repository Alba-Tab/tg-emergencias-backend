package com.uem.ambulancias.emergencias.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.uem.ambulancias.comun.error.CodigoError;
import com.uem.ambulancias.comun.error.ConflictoException;
import com.uem.ambulancias.emergencias.domain.AjusteDeDespacho;
import com.uem.ambulancias.emergencias.domain.Atencion;
import com.uem.ambulancias.emergencias.domain.EstadoIncidente;
import com.uem.ambulancias.emergencias.domain.Incidente;
import com.uem.ambulancias.emergencias.domain.ModoDeDespacho;
import com.uem.ambulancias.emergencias.domain.OfertaDeIncidente;
import com.uem.ambulancias.emergencias.domain.OrigenDelEvento;
import com.uem.ambulancias.emergencias.domain.TipoEventoDelCaso;
import com.uem.ambulancias.emergencias.repository.AtencionRepository;
import com.uem.ambulancias.emergencias.repository.EventoDelCasoRepository;
import com.uem.ambulancias.emergencias.repository.IncidenteRepository;
import com.uem.ambulancias.emergencias.repository.OfertaDeIncidenteRepository;
import com.uem.ambulancias.flota.domain.Ambulancia;
import com.uem.ambulancias.flota.domain.TipoUnidad;
import com.uem.ambulancias.flota.repository.AmbulanciaRepository;
import com.uem.ambulancias.flota.service.ServicioParamedicoService;
import com.uem.ambulancias.flota.service.TurnoService;
import com.uem.ambulancias.usuarios.domain.Usuario;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Point;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * El despacho en modo asignación (PB-34): al incidente que necesita una unidad se le asigna la libre que llega antes
 * y se espera que su tripulación confirme. Si dice que no puede o no contesta a tiempo, pasa a la siguiente; cuando
 * no queda ninguna, el incidente se abre a todas las de turno, como en el modo de siempre.
 *
 * <p>Un incidente puede necesitar más de una unidad, o una de cierto tipo como mínimo (PB-36): eso lo fija el plan de
 * respuesta del protocolo y acá se cubre. Se asignan tantas unidades como falten, cada una con su confirmación, y si
 * ninguna de las que van alcanza el tipo pedido se suma una que sí. Nunca se retira una unidad que ya confirmó.
 *
 * <p>Los bloqueos se toman siempre en el mismo orden: primero el incidente, después la asignación y al final la
 * ambulancia. Es el mismo orden de tomar un incidente, así que no se cruzan.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsignadorDeIncidentes {

	/** Los renglones de la bitácora que cuentan el despacho de un incidente: con el último se sabe en qué quedó. */
	private static final Set<String> RENGLONES_DEL_DESPACHO = Set.of(
			TipoEventoDelCaso.UNIDAD_ASIGNADA.name(), TipoEventoDelCaso.ASIGNACION_CONFIRMADA.name(),
			TipoEventoDelCaso.ASIGNACION_RECHAZADA.name(), TipoEventoDelCaso.ASIGNACION_VENCIDA.name(),
			TipoEventoDelCaso.ASIGNACION_RETIRADA.name(), TipoEventoDelCaso.SIN_UNIDAD_PARA_ASIGNAR.name(),
			TipoEventoDelCaso.PLAN_DE_RESPUESTA_ACTUALIZADO.name());

	private final AjustesDeDespacho ajustes;
	private final OfertaDeIncidenteRepository ofertas;
	private final IncidenteRepository incidentes;
	private final AtencionRepository atenciones;
	private final AmbulanciaRepository ambulancias;
	private final EventoDelCasoRepository renglones;
	private final IncidenteService incidenteService;
	private final TurnoService turnos;
	private final ServicioParamedicoService paramedicos;
	private final EstimadorDeTiempos estimador;
	private final TrasladoProperties posiciones;
	private final BitacoraDelCaso bitacora;
	private final ApplicationEventPublisher eventos;

	/**
	 * Cada cambio de un incidente pasa por acá después de confirmarse. Se retiran las asignaciones pendientes que ya
	 * no hacen falta, y si al incidente le falta una unidad se le asigna: con una ronda nueva si acaba de quedarse
	 * esperando, y dentro de la ronda en curso si lo que cambió es cuánto necesita.
	 */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void alActualizarseElIncidente(IncidenteActualizado evento) {
		Incidente incidente = incidentes.buscarParaActualizar(evento.incidenteId()).orElse(null);
		if (incidente == null) {
			return;
		}
		List<OfertaDeIncidente> pendientes = ofertas.buscarPendientesDeIncidente(incidente.getId());
		if (!incidente.getEstado().isAbierto()) {
			pendientes.forEach(pendiente -> retirar(pendiente, incidente));
			return;
		}
		pendientes = retirarLasQueSobran(incidente, pendientes);
		if (evento.avisar() && pendientes.isEmpty()) {
			ofrecer(incidente, true);
		} else if (pideMasQueUnaUnidadCualquiera(incidente)) {
			ofrecer(incidente, false);
		}
	}

	/**
	 * Una unidad quedó libre: se le ofrecen los incidentes a los que les falta una, del que lleva más tiempo al más
	 * nuevo. Al que ya se había quedado sin candidatas se le abre una ronda nueva, porque ahora sí hay a quién pedirle.
	 */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void alLiberarseUnaUnidad(UnidadLiberada evento) {
		if (ajustes.modo() != ModoDeDespacho.ASIGNACION) {
			return;
		}
		for (Long esperando : incidentes.buscarIdsQuePuedenNecesitarUnidad()) {
			incidentes.buscarParaActualizar(esperando)
					.ifPresent(incidente -> ofrecer(incidente, seQuedoSinCandidatas(incidente)));
		}
	}

	/** La asignación que la unidad en turno de ese paramédico tiene esperando respuesta, si tiene alguna. */
	@Transactional(readOnly = true)
	public Optional<OfertaDeIncidente> pendienteDe(Long paramedicoId) {
		return ofertas.buscarPendienteDeAmbulancia(turnos.ambulanciaEnTurno(paramedicoId)).map(oferta -> {
			// Deja cargado el incidente, para poder armar la respuesta fuera de la transacción.
			oferta.getIncidente().getUbicacion();
			return oferta;
		});
	}

	/** La tripulación confirma: nace la atención y la unidad queda en camino. */
	@Transactional
	public Atencion confirmar(Long paramedicoId) {
		Usuario paramedico = paramedicos.buscarParamedicoActivo(paramedicoId);
		OfertaDeIncidente oferta = bloquearLaPendienteDe(paramedicoId);
		Incidente incidente = oferta.getIncidente();
		Ambulancia ambulancia = oferta.getAmbulancia();
		Instant ahora = Instant.now();
		Atencion atencion = incidenteService.acudirPorAsignacion(incidente.getId(), ambulancia.getId(), paramedico);
		oferta.confirmar(paramedico, atencion, ahora);
		bitacora.anotar(incidente, null, TipoEventoDelCaso.ASIGNACION_CONFIRMADA, OrigenDelEvento.TRIPULACION, ahora,
				BitacoraDelCaso.datos(
						"ofertaId", oferta.getId(),
						"ambulanciaId", ambulancia.getId(),
						"placa", ambulancia.getPlaca(),
						"atencionId", atencion.getId(),
						"segundosEnResponder", Duration.between(oferta.getOfrecidaEn(), ahora).toSeconds()));
		return atencion;
	}

	/** La tripulación dice que no puede ir. El incidente pasa en el momento a la unidad siguiente. */
	@Transactional
	public void rechazar(Long paramedicoId, String motivo) {
		Usuario paramedico = paramedicos.buscarParamedicoActivo(paramedicoId);
		OfertaDeIncidente oferta = bloquearLaPendienteDe(paramedicoId);
		Incidente incidente = oferta.getIncidente();
		Instant ahora = Instant.now();
		oferta.rechazar(paramedico, motivo, ahora);
		bitacora.anotar(incidente, null, TipoEventoDelCaso.ASIGNACION_RECHAZADA, OrigenDelEvento.TRIPULACION, ahora,
				BitacoraDelCaso.datos(
						"ofertaId", oferta.getId(),
						"ambulanciaId", oferta.getAmbulancia().getId(),
						"placa", oferta.getAmbulancia().getPlaca(),
						"motivo", motivo));
		ofertas.saveAndFlush(oferta);
		ofrecer(incidente, false);
	}

	/**
	 * Da por vencida una asignación que se quedó sin respuesta y le ofrece el incidente a la unidad siguiente. No
	 * hace nada si en el medio la tripulación contestó.
	 */
	@Transactional
	public void vencer(Long ofertaId, Instant ahora) {
		Long incidenteId = ofertas.buscarIncidenteId(ofertaId).orElse(null);
		if (incidenteId == null) {
			return;
		}
		Incidente incidente = incidentes.buscarParaActualizar(incidenteId).orElse(null);
		OfertaDeIncidente oferta = ofertas.buscarParaActualizar(ofertaId).orElse(null);
		if (incidente == null || oferta == null || !oferta.vencio(ahora)) {
			return;
		}
		oferta.vencer(ahora);
		bitacora.anotar(incidente, null, TipoEventoDelCaso.ASIGNACION_VENCIDA, OrigenDelEvento.SISTEMA, ahora,
				BitacoraDelCaso.datos(
						"ofertaId", oferta.getId(),
						"ambulanciaId", oferta.getAmbulancia().getId(),
						"placa", oferta.getAmbulancia().getPlaca()));
		ofertas.saveAndFlush(oferta);
		eventos.publishEvent(new OfertaRetirada(incidenteId, oferta.getAmbulancia().getId()));
		ofrecer(incidente, false);
	}

	/** Las asignaciones que ya deberían haber tenido respuesta. */
	@Transactional(readOnly = true)
	public List<Long> vencidas(Instant ahora) {
		return ofertas.buscarIdsVencidas(ahora);
	}

	/**
	 * Lee la asignación pendiente de la unidad en turno, bloquea su incidente y recién entonces la bloquea a ella y
	 * confirma que siga pendiente.
	 */
	private OfertaDeIncidente bloquearLaPendienteDe(Long paramedicoId) {
		Long ambulanciaId = turnos.ambulanciaEnTurno(paramedicoId);
		Long ofertaId = ofertas.buscarIdPendienteDeAmbulancia(ambulanciaId)
				.orElseThrow(AsignadorDeIncidentes::sinOferta);
		ofertas.buscarIncidenteId(ofertaId).ifPresent(incidentes::buscarParaActualizar);
		return ofertas.buscarParaActualizar(ofertaId)
				.filter(OfertaDeIncidente::isPendiente)
				.orElseThrow(AsignadorDeIncidentes::sinOferta);
	}

	private static ConflictoException sinOferta() {
		return new ConflictoException(CodigoError.SIN_OFERTA, "Tu unidad no tiene ninguna asignación esperando respuesta.");
	}

	/**
	 * Lo que al incidente le falta conseguir.
	 *
	 * @param unidades   cuántas unidades más hay que asignar
	 * @param faltaTipo  si ninguna de las que van, ni de las que tienen una asignación pendiente, alcanza el tipo pedido
	 */
	private record Necesidad(int unidades, boolean faltaTipo) {

		boolean cubierta() {
			return unidades <= 0 && !faltaTipo;
		}

	}

	/**
	 * Cuenta lo que el incidente ya tiene: las unidades que fueron y no cancelaron, y las que tienen la asignación
	 * esperando respuesta. Un incidente abierto que no tiene a nadie en camino ni trabajando necesita al menos una,
	 * aunque por el caso ya hayan pasado otras.
	 */
	private Necesidad necesidadDe(Incidente incidente, List<OfertaDeIncidente> pendientes) {
		List<TipoUnidad> enElCaso = atenciones.buscarTiposDeUnidadVigentes(incidente.getId());
		int faltan = incidente.getUnidadesRequeridas() - enElCaso.size() - pendientes.size();
		if (incidente.getEstado() == EstadoIncidente.ACTIVO
				&& !atenciones.existeActivaPorIncidente(incidente.getId())) {
			faltan = Math.max(faltan, 1 - pendientes.size());
		}
		TipoUnidad tipoMinimo = incidente.getTipoMinimoRequerido();
		boolean faltaTipo = tipoMinimo != null
				&& enElCaso.stream().noneMatch(tipo -> tipo.cubreA(tipoMinimo))
				&& pendientes.stream().noneMatch(pendiente -> cubre(pendiente, tipoMinimo));
		return new Necesidad(Math.max(faltan, 0), faltaTipo);
	}

	private static boolean cubre(OfertaDeIncidente oferta, TipoUnidad tipoMinimo) {
		return oferta.getAmbulancia().getTipoUnidad().cubreA(tipoMinimo);
	}

	/** Si el plan de respuesta le pidió al incidente algo más que la respuesta básica de una unidad cualquiera. */
	private static boolean pideMasQueUnaUnidadCualquiera(Incidente incidente) {
		return incidente.getUnidadesRequeridas() > 1 || incidente.getTipoMinimoRequerido() != null;
	}

	/** Si lo último que pasó con el despacho del incidente fue que no quedó a quién asignárselo. */
	private boolean seQuedoSinCandidatas(Incidente incidente) {
		return renglones.buscarUltimoTipoEntre(incidente.getId(), RENGLONES_DEL_DESPACHO)
				.filter(TipoEventoDelCaso.SIN_UNIDAD_PARA_ASIGNAR.name()::equals)
				.isPresent();
	}

	/**
	 * Retira las asignaciones pendientes que ya no hacen falta: otra unidad tomó el caso por su cuenta o la central
	 * mandó una. Se recorren de la más vieja a la más nueva, que es de la unidad más cercana a la más lejana. Una
	 * asignación se queda si todavía hay lugar por cantidad, o si trae el tipo de unidad que se pidió y ninguna de las
	 * anteriores lo alcanza. Devuelve las que siguen pendientes.
	 */
	private List<OfertaDeIncidente> retirarLasQueSobran(Incidente incidente, List<OfertaDeIncidente> pendientes) {
		if (pendientes.isEmpty()) {
			return pendientes;
		}
		List<TipoUnidad> enElCaso = atenciones.buscarTiposDeUnidadVigentes(incidente.getId());
		TipoUnidad tipoMinimo = incidente.getTipoMinimoRequerido();
		int lugares = incidente.getUnidadesRequeridas() - enElCaso.size();
		boolean tipoCubierto = tipoMinimo == null || enElCaso.stream().anyMatch(tipo -> tipo.cubreA(tipoMinimo));
		List<OfertaDeIncidente> quedan = new ArrayList<>();
		for (OfertaDeIncidente pendiente : pendientes) {
			boolean alcanzaElTipo = tipoMinimo != null && cubre(pendiente, tipoMinimo);
			if (lugares > 0 || (!tipoCubierto && alcanzaElTipo)) {
				lugares--;
				tipoCubierto = tipoCubierto || alcanzaElTipo;
				quedan.add(pendiente);
			} else {
				retirar(pendiente, incidente);
			}
		}
		return quedan;
	}

	/**
	 * Le asigna el incidente, ya bloqueado, a tantas unidades como le falten: las libres más cercanas con posición
	 * reciente que todavía no lo tuvieron en esta ronda y que no tienen otra asignación pendiente. Si el incidente
	 * pide un tipo de unidad y ninguna de las que van lo alcanza, primero se busca una que sí; si no hay, va la más
	 * cercana de cualquier tipo, porque mandar algo es mejor que no mandar nada. Cuando no queda a quién asignarlo, se
	 * abre a todas.
	 */
	private void ofrecer(Incidente incidente, boolean rondaNueva) {
		AjusteDeDespacho ajuste = ajustes.vigente();
		if (ajuste.getModo() != ModoDeDespacho.ASIGNACION || !incidente.getEstado().isAbierto()) {
			return;
		}
		List<OfertaDeIncidente> pendientes = ofertas.buscarPendientesDeIncidente(incidente.getId());
		Necesidad necesidad = necesidadDe(incidente, pendientes);
		if (necesidad.cubierta()) {
			return;
		}
		int ronda = Math.max(1, ofertas.ultimaRonda(incidente.getId()) + (rondaNueva ? 1 : 0));
		List<Long> yaOfrecidas = ofertas.buscarAmbulanciasOfrecidas(incidente.getId(), ronda);
		Set<Long> descartadas = new HashSet<>(yaOfrecidas);
		descartadas.addAll(ofertas.buscarAmbulanciasConOfertaPendiente());
		// La unidad que ya estuvo en el caso tampoco: o sigue en él, o por algo se fue.
		descartadas.addAll(atenciones.buscarAmbulanciasQueEstuvieron(incidente.getId()));

		Instant ahora = Instant.now();
		Point lugar = incidente.getUbicacion();
		List<Long> cercanas = new ArrayList<>(ambulancias.buscarIdsDisponiblesParaIncidente(lugar.getY(),
				lugar.getX(), ahora.minus(posiciones.posicionVigente())));
		cercanas.removeAll(descartadas);

		TipoUnidad tipoMinimo = incidente.getTipoMinimoRequerido();
		int faltan = necesidad.unidades();
		boolean faltaTipo = necesidad.faltaTipo();
		int orden = yaOfrecidas.size();
		while (faltan > 0 || faltaTipo) {
			Ambulancia unidad = faltaTipo ? tomarCandidata(cercanas, tipoMinimo) : null;
			if (unidad == null && faltan > 0) {
				unidad = tomarCandidata(cercanas, null);
			}
			if (unidad == null) {
				break;
			}
			orden++;
			boolean alcanzaElTipo = tipoMinimo == null || unidad.getTipoUnidad().cubreA(tipoMinimo);
			Integer minutos = unidad.getUltimaPosicion() == null ? null
					: (int) estimador.minutosDeViaje(unidad.getUltimaPosicion(), lugar);
			OfertaDeIncidente oferta = ofertas.save(OfertaDeIncidente.ofrecer(incidente, unidad, ronda, orden, minutos,
					ahora, Duration.ofSeconds(ajuste.getSegundosConfirmacion())));
			bitacora.anotar(incidente, null, TipoEventoDelCaso.UNIDAD_ASIGNADA, OrigenDelEvento.SISTEMA, ahora,
					BitacoraDelCaso.datos(
							"ofertaId", oferta.getId(),
							"ambulanciaId", unidad.getId(),
							"placa", unidad.getPlaca(),
							"tipoUnidad", unidad.getTipoUnidad().name(),
							"ronda", ronda,
							"orden", oferta.getOrden(),
							"minutosDeLlegada", minutos,
							"segundosParaConfirmar", ajuste.getSegundosConfirmacion(),
							"tipoMinimoPedido", tipoMinimo == null ? null : tipoMinimo.name(),
							"alcanzaElTipoPedido", tipoMinimo == null ? null : alcanzaElTipo));
			eventos.publishEvent(new OfertaEmitida(oferta.getId()));
			faltan--;
			if (alcanzaElTipo) {
				faltaTipo = false;
			}
		}
		if (faltan <= 0 && !faltaTipo) {
			return;
		}
		if (seQuedoSinCandidatas(incidente)) {
			// Ya quedó anotado y ya se abrió a todas: repetirlo en cada cambio del incidente no suma nada.
			return;
		}
		log.warn("Incidente {} sin unidad a la que asignarlo (faltan {}, tipo pedido {}): se abre a todas las de turno.",
				incidente.getId(), Math.max(faltan, 0), faltaTipo ? tipoMinimo : "cubierto");
		bitacora.anotar(incidente, null, TipoEventoDelCaso.SIN_UNIDAD_PARA_ASIGNAR, OrigenDelEvento.SISTEMA, ahora,
				BitacoraDelCaso.datos(
						"ronda", ronda,
						"unidadesQueLoTuvieron", yaOfrecidas.size(),
						"unidadesQueFaltan", Math.max(faltan, 0),
						"tipoQueFalta", faltaTipo ? tipoMinimo.name() : null));
		eventos.publishEvent(new IncidenteSinUnidadParaAsignar(incidente.getId()));
	}

	/**
	 * Saca de la lista la primera candidata que sigue libre y la devuelve bloqueada. Si se pide cubrir un tipo, solo
	 * mira las que lo alcanzan y deja las demás en la lista. Cada candidata se relee bloqueada porque en el medio
	 * pudo irse a otra emergencia o recibir otra asignación.
	 */
	private Ambulancia tomarCandidata(List<Long> cercanas, TipoUnidad tipoQueCubrir) {
		for (Iterator<Long> candidatas = cercanas.iterator(); candidatas.hasNext();) {
			Long candidata = candidatas.next();
			if (tipoQueCubrir != null && !ambulancias.buscarTipoUnidad(candidata)
					.filter(tipo -> tipo.cubreA(tipoQueCubrir))
					.isPresent()) {
				continue;
			}
			candidatas.remove();
			Ambulancia unidad = ambulancias.buscarParaActualizar(candidata).orElse(null);
			if (unidad != null && unidad.puedeAtender() && ofertas.buscarPendienteDeAmbulancia(candidata).isEmpty()) {
				return unidad;
			}
		}
		return null;
	}

	private void retirar(OfertaDeIncidente pendiente, Incidente incidente) {
		Instant ahora = Instant.now();
		pendiente.retirar(ahora);
		bitacora.anotar(incidente, null, TipoEventoDelCaso.ASIGNACION_RETIRADA, OrigenDelEvento.SISTEMA, ahora,
				BitacoraDelCaso.datos(
						"ofertaId", pendiente.getId(),
						"ambulanciaId", pendiente.getAmbulancia().getId(),
						"placa", pendiente.getAmbulancia().getPlaca(),
						"estadoDelIncidente", incidente.getEstado().name()));
		eventos.publishEvent(new OfertaRetirada(incidente.getId(), pendiente.getAmbulancia().getId()));
	}

}
