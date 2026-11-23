package com.uem.ambulancias.protocolo.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.uem.ambulancias.comun.error.CodigoError;
import com.uem.ambulancias.comun.error.ConflictoException;
import com.uem.ambulancias.comun.error.NoEncontradoException;
import com.uem.ambulancias.comun.web.Textos;
import com.uem.ambulancias.protocolo.domain.Condicion;
import com.uem.ambulancias.protocolo.domain.EstadoProtocolo;
import com.uem.ambulancias.protocolo.domain.Protocolo;
import com.uem.ambulancias.protocolo.domain.TipoDeSuceso;
import com.uem.ambulancias.protocolo.dto.GuardarProtocoloRequest;
import com.uem.ambulancias.protocolo.dto.GuardarProtocoloRequest.CondicionRequest;
import com.uem.ambulancias.protocolo.dto.GuardarProtocoloRequest.TipoRequest;
import com.uem.ambulancias.protocolo.repository.BloqueoDeProtocolo;
import com.uem.ambulancias.protocolo.repository.ProtocoloRepository;
import com.uem.ambulancias.usuarios.domain.RolUsuario;
import com.uem.ambulancias.usuarios.domain.Usuario;
import com.uem.ambulancias.usuarios.repository.UsuarioRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * El protocolo de despacho lo mantiene la central y lo usan todos los demás. Siempre hay a lo sumo un borrador, que
 * es el único editable, y a lo sumo una versión vigente, que es la que ven la app y los incidentes nuevos.
 *
 * <p>Lo que devuelve sale con sus tipos y condiciones ya cargados, para que se pueda armar la respuesta fuera de la
 * transacción.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProtocoloService {

	private final ProtocoloRepository protocolos;
	private final UsuarioRepository usuarios;
	private final BloqueoDeProtocolo bloqueo;

	public List<Protocolo> listar() {
		List<Protocolo> todos = protocolos.findAllByOrderByVersionDesc();
		todos.forEach(ProtocoloService::cargado);
		return todos;
	}

	public Protocolo buscar(Long id) {
		return protocolos.findById(id).map(ProtocoloService::cargado)
				.orElseThrow(() -> new NoEncontradoException("No existe el protocolo " + id + "."));
	}

	/** La versión que rige hoy, si la central ya publicó alguna. */
	public Optional<Protocolo> vigente() {
		return protocolos.findByEstado(EstadoProtocolo.VIGENTE).map(ProtocoloService::cargado);
	}

	public Protocolo vigenteObligatorio() {
		return vigente().orElseThrow(() -> new NoEncontradoException("Todavía no hay un protocolo publicado."));
	}

	public Protocolo borrador() {
		return cargado(borradorObligatorio());
	}

	/** Lo que la app le ofrece al ciudadano para marcar qué pasó. Sin protocolo publicado no hay nada que ofrecer. */
	public List<TipoDeSuceso> tiposVigentes() {
		return vigente().map(Protocolo::getTipos).orElse(List.of());
	}

	/** Las preguntas del formulario de ese tipo de suceso, en el orden del protocolo. */
	public List<Condicion> preguntasDe(String codigoTipo) {
		return vigente().flatMap(protocolo -> protocolo.buscarTipo(codigoTipo))
				.map(TipoDeSuceso::conPregunta)
				.orElseThrow(() -> new NoEncontradoException("No existe el tipo de suceso " + codigoTipo + "."));
	}

	/**
	 * Abre el borrador de la versión siguiente. Arranca con el contenido de la vigente, que es de donde casi siempre
	 * se parte para corregir algo; si todavía no hay ninguna, arranca vacío.
	 */
	@Transactional
	public Protocolo abrirBorrador(Long administradorId) {
		bloqueo.adquirir();
		if (protocolos.findByEstado(EstadoProtocolo.BORRADOR).isPresent()) {
			throw new ConflictoException(CodigoError.PROTOCOLO_BORRADOR_EXISTENTE,
					"Ya hay un borrador abierto: hay que publicarlo o descartarlo antes de abrir otro.");
		}
		Usuario administrador = usuarios.findByIdAndRol(administradorId, RolUsuario.ADMIN)
				.orElseThrow(() -> new NoEncontradoException("No existe el administrador " + administradorId + "."));
		Protocolo borrador = Protocolo.borrador(protocolos.ultimaVersion() + 1, administrador, Instant.now());
		protocolos.findByEstado(EstadoProtocolo.VIGENTE).ifPresent(vigente -> borrador.cargar(vigente.getNota(),
				vigente.getTipos().stream().map(TipoDeSuceso::copiar).toList()));
		return cargado(protocolos.save(borrador));
	}

	/**
	 * Reemplaza todo el contenido del borrador. Lo anterior se borra antes de insertar lo nuevo: si no, dos tipos con
	 * el mismo código chocarían en la base aunque uno ya esté de salida.
	 */
	@Transactional
	public Protocolo guardarBorrador(GuardarProtocoloRequest contenido) {
		bloqueo.adquirir();
		Protocolo borrador = borradorObligatorio();
		borrador.vaciar();
		protocolos.saveAndFlush(borrador);
		borrador.cargar(Textos.opcional(contenido.nota()), contenido.tipos().stream().map(ProtocoloService::tipo)
				.toList());
		return cargado(protocolos.save(borrador));
	}

	/** El borrador pasa a regir y la versión anterior queda retirada, las dos cosas juntas. */
	@Transactional
	public Protocolo publicarBorrador() {
		bloqueo.adquirir();
		Protocolo borrador = borradorObligatorio();
		// Se busca la vigente antes de publicar: después habría dos y la búsqueda ya no sabría cuál devolver.
		Optional<Protocolo> anterior = protocolos.findByEstado(EstadoProtocolo.VIGENTE);
		borrador.publicar(Instant.now());
		anterior.ifPresent(Protocolo::retirar);
		return cargado(borrador);
	}

	@Transactional
	public void descartarBorrador() {
		bloqueo.adquirir();
		protocolos.delete(borradorObligatorio());
	}

	private Protocolo borradorObligatorio() {
		return protocolos.findByEstado(EstadoProtocolo.BORRADOR)
				.orElseThrow(() -> new NoEncontradoException("No hay un borrador de protocolo abierto."));
	}

	private static TipoDeSuceso tipo(TipoRequest tipo) {
		List<CondicionRequest> condiciones = tipo.condiciones() == null ? List.of() : tipo.condiciones();
		return TipoDeSuceso.de(tipo.codigo(), tipo.nombre().trim(),
				condiciones.stream().map(ProtocoloService::condicion).toList());
	}

	private static Condicion condicion(CondicionRequest condicion) {
		return Condicion.de(condicion.codigo(), condicion.enunciado().trim(), Textos.opcional(condicion.pregunta()),
				condicion.umbralActuar(), condicion.umbralPreguntar(),
				condicion.unidadesAdicionales() == null ? 0 : condicion.unidadesAdicionales(),
				condicion.tipoMinimo(), Boolean.TRUE.equals(condicion.derivaAPersona()),
				Textos.opcional(condicion.cita()), condicion.afectadosDesde()).conPrioridad(condicion.prioridad());
	}

	/** Recorre los tipos y sus condiciones mientras la transacción sigue abierta. */
	private static Protocolo cargado(Protocolo protocolo) {
		protocolo.getTipos().forEach(tipo -> tipo.getCondiciones().size());
		return protocolo;
	}

}
