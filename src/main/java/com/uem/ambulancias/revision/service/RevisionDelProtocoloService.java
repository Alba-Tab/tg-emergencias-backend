package com.uem.ambulancias.revision.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.uem.ambulancias.comun.error.CodigoError;
import com.uem.ambulancias.comun.error.ConflictoException;
import com.uem.ambulancias.comun.error.NoEncontradoException;
import com.uem.ambulancias.protocolo.domain.Condicion;
import com.uem.ambulancias.protocolo.domain.Protocolo;
import com.uem.ambulancias.protocolo.domain.TipoDeSuceso;
import com.uem.ambulancias.protocolo.service.ProtocoloService;
import com.uem.ambulancias.revision.domain.RevisionDelProtocolo;
import com.uem.ambulancias.revision.repository.RevisionDelProtocoloRepository;
import com.uem.ambulancias.revision.service.AnalistaDelProtocolo.ExtractoDeNorma;
import com.uem.ambulancias.revision.service.AnalistaDelProtocolo.Hecho;
import com.uem.ambulancias.revision.service.AnalistaDelProtocolo.Propuesta;
import com.uem.ambulancias.revision.service.AnalistaDelProtocolo.PropuestasRecibidas;
import com.uem.ambulancias.usuarios.domain.RolUsuario;
import com.uem.ambulancias.usuarios.domain.Usuario;
import com.uem.ambulancias.usuarios.repository.UsuarioRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

/**
 * Las revisiones del protocolo: se piden, el worker las prepara contando los hechos, el analista propone y acá se
 * decide qué propuestas llegan a la persona. Ninguna cambia el protocolo.
 */
@Service
@RequiredArgsConstructor
public class RevisionDelProtocoloService {

	private static final String AJUSTAR_UMBRAL = "adjust_threshold";

	/** Los tipos de propuesta del servicio de IA, como se le muestran a la central. */
	private static final Map<String, String> TIPOS = Map.of(
			AJUSTAR_UMBRAL, "AJUSTAR_UMBRAL",
			"change_question", "CAMBIAR_PREGUNTA",
			"change_response", "CAMBIAR_RESPUESTA",
			"add_condition", "AGREGAR_CONDICION",
			"remove_condition", "QUITAR_CONDICION",
			"other", "OTRA");

	private final RevisionDelProtocoloRepository revisiones;
	private final ProtocoloService protocolos;
	private final UsuarioRepository usuarios;
	private final HechosDelProtocolo hechos;
	private final ObjectProvider<AnalistaDelProtocolo> analista;
	private final RevisionProperties config;
	private final JsonMapper json;

	/**
	 * Lo que el worker necesita para pedirle la revisión al analista.
	 *
	 * @param temas de qué trata cada tipo de suceso, para buscar los extractos de la norma que vienen al caso
	 */
	public record Preparada(String protocoloJson, List<Hecho> hechos, List<String> temas) {
	}

	/** Pide una revisión del protocolo vigente. Queda en la cola hasta que el worker la tome. */
	@Transactional
	public RevisionDelProtocolo pedir(Long administradorId) {
		Usuario administrador = usuarios.findByIdAndRol(administradorId, RolUsuario.ADMIN)
				.orElseThrow(() -> new NoEncontradoException("No existe el administrador " + administradorId + "."));
		if (Optional.ofNullable(analista.getIfAvailable()).filter(AnalistaDelProtocolo::listoParaRevisar).isEmpty()) {
			throw new ConflictoException(CodigoError.ANALISTA_NO_DISPONIBLE,
					"El analista está apagado: enciende sga.ia.analista-habilitado.");
		}
		Protocolo vigente = protocolos.vigente().orElseThrow(() -> new ConflictoException(
				CodigoError.PROTOCOLO_INVALIDO, "No hay un protocolo publicado para revisar."));
		return revisiones.save(RevisionDelProtocolo.pedir(vigente, administrador, Instant.now()));
	}

	/** Las últimas veinte, de la más nueva a la más vieja. */
	@Transactional(readOnly = true)
	public List<RevisionDelProtocolo> listar() {
		return revisiones.buscarRecientes(PageRequest.of(0, 20));
	}

	@Transactional(readOnly = true)
	public RevisionDelProtocolo buscar(Long id) {
		return revisiones.findById(id)
				.orElseThrow(() -> new NoEncontradoException("No existe la revisión " + id + "."));
	}

	@Transactional(readOnly = true)
	public List<Long> porHacer() {
		return revisiones.buscarIdsPorHacer();
	}

	/**
	 * Cuenta los hechos del protocolo y deja la revisión en curso. Si no hay nada que contar, la cierra sin llamar al
	 * analista y devuelve vacío.
	 */
	@Transactional
	public Optional<Preparada> preparar(Long revisionId) {
		RevisionDelProtocolo revision = revisiones.findById(revisionId).orElse(null);
		if (revision == null || revision.terminada()) {
			return Optional.empty();
		}
		Protocolo protocolo = revision.getProtocolo();
		List<Hecho> contados = hechos.contar(protocolo, config.minimoDeCasos());
		revision.empezar(json.writeValueAsString(contados));
		if (contados.isEmpty()) {
			revision.concluir("[]", 0, null, null, null,
					"Todavía no hay casos con este protocolo: no hay nada que revisar.", Instant.now());
			return Optional.empty();
		}
		return Optional.of(new Preparada(json.writeValueAsString(paraElAnalista(protocolo)), contados,
				temas(protocolo)));
	}

	/**
	 * Guarda las propuestas que se pueden defender. Además de lo que ya filtró el servicio de IA, acá se descarta la
	 * que no cita ningún hecho de los que se mandaron y la que pide cambiar un umbral con pocos casos detrás.
	 */
	@Transactional
	public void concluir(Long revisionId, List<Hecho> contados, List<ExtractoDeNorma> norma,
			PropuestasRecibidas recibidas) {
		RevisionDelProtocolo revision = revisiones.findById(revisionId).orElse(null);
		if (revision == null || revision.terminada()) {
			return;
		}
		int descartadas = recibidas.descartadas();
		List<Map<String, Object>> propuestas = new ArrayList<>();
		for (Propuesta propuesta : recibidas.propuestas()) {
			List<Hecho> citados = contados.stream().filter(hecho -> propuesta.hechos().contains(hecho.id())).toList();
			boolean pocosCasos = AJUSTAR_UMBRAL.equals(propuesta.tipo())
					&& citados.stream().noneMatch(hecho -> hecho.casos() >= config.minimoDeCasos());
			if (citados.isEmpty() || pocosCasos || !TIPOS.containsKey(propuesta.tipo())) {
				descartadas++;
				continue;
			}
			Map<String, Object> guardada = new LinkedHashMap<>();
			guardada.put("tipo", TIPOS.get(propuesta.tipo()));
			guardada.put("tipoDeSuceso", propuesta.tipoDeSuceso());
			guardada.put("condicion", propuesta.condicion());
			guardada.put("propuesta", propuesta.propuesta());
			guardada.put("porQue", propuesta.porQue());
			guardada.put("hechos", citados.stream().map(Hecho::texto).toList());
			guardada.put("norma", norma.stream().filter(extracto -> propuesta.norma().contains(extracto.id()))
					.map(ExtractoDeNorma::cita).toList());
			propuestas.add(guardada);
		}
		revision.concluir(json.writeValueAsString(propuestas), descartadas, recibidas.proveedor(), recibidas.modelo(),
				recibidas.versionDelPrompt(),
				propuestas.isEmpty() ? "El analista no encontró nada que proponer con estos hechos." : null,
				Instant.now());
	}

	@Transactional
	public void fallar(Long revisionId, String nota) {
		revisiones.findById(revisionId).filter(revision -> !revision.terminada())
				.ifPresent(revision -> revision.fallar(nota, Instant.now()));
	}

	/** El protocolo con el contrato del servicio de IA. */
	private static Map<String, Object> paraElAnalista(Protocolo protocolo) {
		List<Map<String, Object>> tipos = new ArrayList<>();
		for (TipoDeSuceso tipo : protocolo.getTipos()) {
			List<Map<String, Object>> condiciones = new ArrayList<>();
			for (Condicion condicion : tipo.getCondiciones()) {
				Map<String, Object> una = new LinkedHashMap<>();
				una.put("code", condicion.getCodigo());
				una.put("statement", condicion.getEnunciado());
				una.put("question", condicion.getPregunta());
				una.put("actThreshold", condicion.getUmbralActuar());
				una.put("askThreshold", condicion.getUmbralPreguntar());
				una.put("extraUnits", condicion.getUnidadesAdicionales());
				una.put("minimumUnitType", condicion.getTipoMinimo() == null ? null : condicion.getTipoMinimo().name());
				una.put("priority", condicion.getPrioridad() == null ? null : condicion.getPrioridad().name());
				una.put("sendsToAPerson", condicion.isDerivaAPersona());
				una.put("metWhenAffectedAtLeast", condicion.getAfectadosDesde());
				condiciones.add(una);
			}
			Map<String, Object> uno = new LinkedHashMap<>();
			uno.put("code", tipo.getCodigo());
			uno.put("name", tipo.getNombre());
			uno.put("conditions", condiciones);
			tipos.add(uno);
		}
		Map<String, Object> raiz = new LinkedHashMap<>();
		raiz.put("version", protocolo.getVersion());
		raiz.put("types", tipos);
		return raiz;
	}

	/** Una frase por tipo de suceso con lo que evalúa, para buscar en la norma lo que viene al caso. */
	private static List<String> temas(Protocolo protocolo) {
		return protocolo.getTipos().stream()
				.filter(tipo -> !tipo.getCondiciones().isEmpty())
				.map(tipo -> tipo.getNombre() + ": " + String.join("; ",
						tipo.getCondiciones().stream().map(Condicion::getEnunciado).toList()))
				.toList();
	}

}
