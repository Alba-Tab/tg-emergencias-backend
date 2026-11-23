package com.uem.ambulancias.agente.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.uem.ambulancias.coordinacion.domain.Derivacion;
import com.uem.ambulancias.coordinacion.domain.MotivoDeDerivacion;
import com.uem.ambulancias.coordinacion.repository.DerivacionRepository;
import com.uem.ambulancias.coordinacion.service.Derivaciones;
import com.uem.ambulancias.emergencias.domain.Incidente;
import com.uem.ambulancias.emergencias.repository.AtencionRepository;
import com.uem.ambulancias.emergencias.repository.IncidenteRepository;
import com.uem.ambulancias.emergencias.service.BitacoraDelCaso;
import com.uem.ambulancias.emergencias.service.FormularioDelCaso;
import com.uem.ambulancias.protocolo.domain.Condicion;
import com.uem.ambulancias.protocolo.domain.TipoDeSuceso;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lo poco que el agente puede hacer por su cuenta en la central: pedirle una respuesta al testigo y proponerle algo
 * a una persona. Ninguna de las dos mueve una unidad: sumar recursos lo hace el sistema con el protocolo, y todo lo
 * demás lo decide una persona.
 */
@Service
@RequiredArgsConstructor
public class AccionesDelAgente {

	/** Lo más largo que puede ser una propuesta o su justificación. */
	private static final int MAXIMO_DE_TEXTO = 600;

	private final IncidenteRepository incidentes;
	private final AtencionRepository atenciones;
	private final DerivacionRepository derivacionesAbiertas;
	private final FormularioDelCaso formulario;
	private final Derivaciones derivaciones;

	/**
	 * Le pide a los testigos que contesten esas condiciones. Solo valen las que el protocolo del incidente pregunta, y
	 * cada una se pide una sola vez: las repetidas no generan otro aviso.
	 */
	@Transactional
	public Map<String, Object> mostrarCasillas(long incidenteId, List<String> condiciones) {
		Incidente incidente = abiertoYSinUnidadEnElLugar(incidenteId);
		if (incidente.getTipoSuceso() == null || incidente.getProtocolo() == null) {
			throw new ErrorDeHerramienta("El incidente " + incidenteId
					+ " no tiene un tipo de suceso declarado: no hay formulario que mostrarle al testigo.");
		}
		TipoDeSuceso tipo = incidente.getProtocolo().buscarTipo(incidente.getTipoSuceso())
				.orElseThrow(() -> new ErrorDeHerramienta("El tipo de suceso del incidente no está en su protocolo."));
		List<String> conPregunta = new ArrayList<>();
		List<String> sinPregunta = new ArrayList<>();
		for (String codigo : condiciones) {
			boolean sePregunta = tipo.buscarCondicion(codigo).map(Condicion::getPregunta).isPresent();
			(sePregunta ? conPregunta : sinPregunta).add(codigo);
		}
		List<String> pedidas = formulario.preguntar(incidente, conPregunta, null);
		Map<String, Object> respuesta = new LinkedHashMap<>();
		respuesta.put("pedidasAhora", pedidas);
		respuesta.put("yaEstabanPedidas", conPregunta.stream().filter(codigo -> !pedidas.contains(codigo)).toList());
		respuesta.put("sinPreguntaEnElProtocolo", sinPregunta);
		respuesta.put("nota", "La respuesta del testigo no llega en esta conversación: deja esas condiciones como "
				+ "unknown. Cuando conteste, el sistema vuelve a evaluar el caso.");
		return respuesta;
	}

	/**
	 * Le pasa una propuesta a una persona de la central. Es para lo que el sistema no hace solo: quitar o cambiar una
	 * unidad, cancelar, unir incidentes. Mientras el incidente tenga una propuesta esperando respuesta, no se abre
	 * otra.
	 */
	@Transactional
	public Map<String, Object> proponerAlOperador(long incidenteId, String propuesta, String justificacion) {
		String loPropuesto = recortado(propuesta, "propuesta");
		String porQue = recortado(justificacion, "justificacion");
		Incidente incidente = abiertoYSinUnidadEnElLugar(incidenteId);
		if (derivacionesAbiertas.existeAbierta(incidenteId, MotivoDeDerivacion.PROPUESTA_DEL_AGENTE.name())) {
			throw new ErrorDeHerramienta("El incidente " + incidenteId
					+ " ya tiene una propuesta esperando la respuesta de una persona.");
		}
		long anteriores = derivacionesAbiertas.findByIncidenteIdOrderByIdAsc(incidenteId).stream()
				.filter(derivacion -> derivacion.getMotivo() == MotivoDeDerivacion.PROPUESTA_DEL_AGENTE)
				.count();
		Optional<Derivacion> abierta = derivaciones.derivar(incidente, MotivoDeDerivacion.PROPUESTA_DEL_AGENTE,
				MotivoDeDerivacion.PROPUESTA_DEL_AGENTE.name() + ":" + (anteriores + 1), null,
				BitacoraDelCaso.datos("propuesta", loPropuesto, "justificacion", porQue), null);
		Derivacion derivacion = abierta.orElseThrow(
				() -> new ErrorDeHerramienta("No se pudo registrar la propuesta: ya había una igual."));
		Map<String, Object> respuesta = new LinkedHashMap<>();
		respuesta.put("derivacionId", derivacion.getId());
		respuesta.put("venceEn", derivacion.getVenceEn().toString());
		respuesta.put("nota", "Una persona de la central la va a ver. Si nadie contesta a tiempo, no se hace.");
		return respuesta;
	}

	/** El incidente, bloqueado. Desde que se cerró o una unidad llegó al lugar, el agente ya no actúa sobre él. */
	private Incidente abiertoYSinUnidadEnElLugar(long incidenteId) {
		Incidente incidente = incidentes.buscarParaActualizar(incidenteId)
				.orElseThrow(() -> new ErrorDeHerramienta("No existe el incidente " + incidenteId + "."));
		if (!incidente.getEstado().isAbierto()) {
			throw new ErrorDeHerramienta("El incidente " + incidenteId + " ya está cerrado.");
		}
		if (atenciones.existeLlegadaVigentePorIncidente(incidenteId)) {
			throw new ErrorDeHerramienta("Una unidad ya llegó al lugar del incidente " + incidenteId
					+ ": desde ahí decide la tripulación.");
		}
		return incidente;
	}

	private static String recortado(String texto, String campo) {
		if (texto == null || texto.isBlank()) {
			throw new ErrorDeHerramienta("Falta «" + campo + "».");
		}
		String limpio = texto.trim();
		return limpio.length() <= MAXIMO_DE_TEXTO ? limpio : limpio.substring(0, MAXIMO_DE_TEXTO);
	}

}
