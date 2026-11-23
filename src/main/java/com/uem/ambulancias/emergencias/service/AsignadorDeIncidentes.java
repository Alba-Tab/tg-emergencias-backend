package com.uem.ambulancias.emergencias.service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
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
import com.uem.ambulancias.emergencias.repository.IncidenteRepository;
import com.uem.ambulancias.emergencias.repository.OfertaDeIncidenteRepository;
import com.uem.ambulancias.flota.domain.Ambulancia;
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
 * <p>Los bloqueos se toman siempre en el mismo orden: primero el incidente, después la asignación y al final la
 * ambulancia. Es el mismo orden de tomar un incidente, así que no se cruzan.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsignadorDeIncidentes {

	private final AjustesDeDespacho ajustes;
	private final OfertaDeIncidenteRepository ofertas;
	private final IncidenteRepository incidentes;
	private final AtencionRepository atenciones;
	private final AmbulanciaRepository ambulancias;
	private final IncidenteService incidenteService;
	private final TurnoService turnos;
	private final ServicioParamedicoService paramedicos;
	private final EstimadorDeTiempos estimador;
	private final TrasladoProperties posiciones;
	private final BitacoraDelCaso bitacora;
	private final ApplicationEventPublisher eventos;

	/**
	 * Cada cambio de un incidente pasa por acá después de confirmarse. Si el incidente dejó de esperar unidad, se
	 * retira la asignación que hubiera pendiente; si la espera y nadie la tiene ofrecida, empieza una ronda nueva.
	 */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void alActualizarseElIncidente(IncidenteActualizado evento) {
		Incidente incidente = incidentes.buscarParaActualizar(evento.incidenteId()).orElse(null);
		if (incidente == null) {
			return;
		}
		List<OfertaDeIncidente> pendientes = ofertas.buscarPendientesDeIncidente(incidente.getId());
		if (!esperaUnidad(incidente)) {
			pendientes.forEach(pendiente -> retirar(pendiente, incidente));
			return;
		}
		if (pendientes.isEmpty() && evento.avisar()) {
			ofrecer(incidente, true);
		}
	}

	/** Una unidad quedó libre: si hay incidentes esperando, se le ofrece el que lleva más tiempo. */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void alLiberarseUnaUnidad(UnidadLiberada evento) {
		if (ajustes.modo() != ModoDeDespacho.ASIGNACION) {
			return;
		}
		for (Incidente esperando : incidentes.buscarSinCubrir()) {
			incidentes.buscarParaActualizar(esperando.getId()).ifPresent(incidente -> ofrecer(incidente, false));
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

	/** Espera unidad el incidente abierto que no tiene a nadie en camino ni trabajando. */
	private boolean esperaUnidad(Incidente incidente) {
		return incidente.getEstado() == EstadoIncidente.ACTIVO && !atenciones.existeActivaPorIncidente(incidente.getId());
	}

	/**
	 * Le asigna el incidente, ya bloqueado, a la unidad libre más cercana con posición reciente que todavía no lo
	 * tuvo en esta ronda y que no tiene otra asignación pendiente. Si no hay ninguna, lo abre a todas.
	 */
	private void ofrecer(Incidente incidente, boolean rondaNueva) {
		AjusteDeDespacho ajuste = ajustes.vigente();
		if (ajuste.getModo() != ModoDeDespacho.ASIGNACION || !esperaUnidad(incidente)
				|| !ofertas.buscarPendientesDeIncidente(incidente.getId()).isEmpty()) {
			return;
		}
		int ronda = Math.max(1, ofertas.ultimaRonda(incidente.getId()) + (rondaNueva ? 1 : 0));
		List<Long> yaOfrecidas = ofertas.buscarAmbulanciasOfrecidas(incidente.getId(), ronda);
		Set<Long> descartadas = new HashSet<>(yaOfrecidas);
		descartadas.addAll(ofertas.buscarAmbulanciasConOfertaPendiente());
		// La unidad que ya estuvo en el caso y lo dejó tampoco: por algo se fue.
		descartadas.addAll(atenciones.buscarAmbulanciasQueEstuvieron(incidente.getId()));

		Instant ahora = Instant.now();
		Point lugar = incidente.getUbicacion();
		List<Long> cercanas = ambulancias.buscarIdsDisponiblesParaIncidente(lugar.getY(), lugar.getX(),
				ahora.minus(posiciones.posicionVigente()));
		for (Long candidata : cercanas) {
			if (descartadas.contains(candidata)) {
				continue;
			}
			// Bloqueada y releída: en el medio pudo irse a otra emergencia o recibir otra asignación.
			Ambulancia unidad = ambulancias.buscarParaActualizar(candidata).orElse(null);
			if (unidad == null || !unidad.puedeAtender()
					|| ofertas.buscarPendienteDeAmbulancia(candidata).isPresent()) {
				continue;
			}
			Integer minutos = unidad.getUltimaPosicion() == null ? null
					: (int) estimador.minutosDeViaje(unidad.getUltimaPosicion(), lugar);
			OfertaDeIncidente oferta = ofertas.save(OfertaDeIncidente.ofrecer(incidente, unidad, ronda,
					yaOfrecidas.size() + 1, minutos, ahora, Duration.ofSeconds(ajuste.getSegundosConfirmacion())));
			bitacora.anotar(incidente, null, TipoEventoDelCaso.UNIDAD_ASIGNADA, OrigenDelEvento.SISTEMA, ahora,
					BitacoraDelCaso.datos(
							"ofertaId", oferta.getId(),
							"ambulanciaId", unidad.getId(),
							"placa", unidad.getPlaca(),
							"ronda", ronda,
							"orden", oferta.getOrden(),
							"minutosDeLlegada", minutos,
							"segundosParaConfirmar", ajuste.getSegundosConfirmacion()));
			eventos.publishEvent(new OfertaEmitida(oferta.getId()));
			return;
		}

		log.warn("Incidente {} sin unidad a la que asignarlo: se abre a todas las de turno.", incidente.getId());
		bitacora.anotar(incidente, null, TipoEventoDelCaso.SIN_UNIDAD_PARA_ASIGNAR, OrigenDelEvento.SISTEMA, ahora,
				BitacoraDelCaso.datos("ronda", ronda, "unidadesQueLoTuvieron", yaOfrecidas.size()));
		eventos.publishEvent(new IncidenteSinUnidadParaAsignar(incidente.getId()));
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
