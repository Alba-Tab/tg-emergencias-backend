package com.uem.ambulancias.memoria.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.uem.ambulancias.coordinacion.service.ArmadorDelEstado;
import com.uem.ambulancias.emergencias.domain.Incidente;
import com.uem.ambulancias.emergencias.repository.IncidenteRepository;
import com.uem.ambulancias.memoria.domain.MemoriaDelCaso;
import com.uem.ambulancias.memoria.repository.MemoriaDelCasoRepository;
import com.uem.ambulancias.protocolo.domain.Condicion;
import com.uem.ambulancias.protocolo.domain.TipoDeSuceso;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * El resumen de un incidente que se convierte en vector para compararlo con otros. Dice la situación: qué pasó, qué
 * contestaron los testigos, qué condiciones se dieron por ciertas y qué describió el análisis de las evidencias. No
 * lleva nombres, teléfonos ni lo que escribió el testigo, y tampoco cómo terminó el caso: eso es lo que se quiere
 * averiguar con la búsqueda, no lo que se compara.
 */
@Component
@RequiredArgsConstructor
public class ResumenDelCaso {

	private static final Map<String, String> RESPUESTA = Map.of("SI", "sí", "NO", "no", "NO_SE", "no sabe");

	private final IncidenteRepository incidentes;
	private final MemoriaDelCasoRepository memorias;
	private final ArmadorDelEstado armador;

	/** El resumen del incidente, o vacío si no declaró un tipo de suceso que su protocolo conozca. */
	@Transactional(readOnly = true)
	public Optional<String> textoDe(Long incidenteId) {
		return incidentes.findById(incidenteId).flatMap(this::texto);
	}

	/** Deja al incidente en la memoria con su resumen, todavía sin vector. Sin resumen queda anotado igual, vacío. */
	@Transactional
	public void registrar(Long incidenteId) {
		Incidente incidente = incidentes.findById(incidenteId).orElse(null);
		if (incidente == null || incidente.getTipoSuceso() == null) {
			return;
		}
		memorias.save(MemoriaDelCaso.de(incidente, texto(incidente).orElse(null), Instant.now()));
	}

	private Optional<String> texto(Incidente incidente) {
		if (incidente.getTipoSuceso() == null || incidente.getProtocolo() == null) {
			return Optional.empty();
		}
		return incidente.getProtocolo().buscarTipo(incidente.getTipoSuceso())
				.map(tipo -> redactar(tipo, armador.armar(incidente, tipo).estado()));
	}

	/** Pasa el estado del caso a frases. Las condiciones van con su enunciado, no con su código. */
	static String redactar(TipoDeSuceso tipo, Map<String, Object> estado) {
		List<String> frases = new ArrayList<>();
		frases.add("Tipo de suceso: " + tipo.getNombre() + ".");
		if (estado.get("afectadosDeclarados") instanceof Number afectados) {
			frases.add("Personas afectadas declaradas: " + afectados + ".");
		}
		if (estado.get("respuestasDelTestigo") instanceof Map<?, ?> respuestas) {
			respuestas.forEach((codigo, valor) -> frases.add("El testigo contestó «" + RESPUESTA.getOrDefault(
					String.valueOf(valor), "no sabe") + "» a: " + enunciado(tipo, String.valueOf(codigo)) + "."));
		}
		if (estado.get("condicionesDecididas") instanceof Map<?, ?> decididas) {
			decididas.forEach((codigo, decidida) -> frases.add((decidida instanceof Map<?, ?> detalle
					&& Boolean.TRUE.equals(detalle.get("cumple")) ? "Se dio por cierto: " : "Se descartó: ")
					+ enunciado(tipo, String.valueOf(codigo)) + "."));
		}
		String evidencias = aplanar(estado.get("evidencias"));
		if (!evidencias.isBlank()) {
			frases.add("Lo que describió el análisis de las evidencias: " + evidencias + ".");
		}
		String resumen = aplanar(estado.get("resumenDelIncidente"));
		if (!resumen.isBlank()) {
			frases.add("Resumen del incidente: " + resumen + ".");
		}
		String texto = String.join(" ", frases);
		return texto.length() <= TextosDeLaNorma.LARGO_MAXIMO ? texto : texto.substring(0, TextosDeLaNorma.LARGO_MAXIMO);
	}

	private static String enunciado(TipoDeSuceso tipo, String codigo) {
		return tipo.buscarCondicion(codigo).map(Condicion::getEnunciado).orElse(codigo.replace('_', ' ').toLowerCase());
	}

	/** Todo lo que haya adentro, en una sola línea: los textos tal cual y los números con su nombre. */
	private static String aplanar(Object valor) {
		if (valor instanceof String texto) {
			return texto.strip();
		}
		if (valor instanceof Collection<?> varios) {
			return varios.stream().map(ResumenDelCaso::aplanar).filter(parte -> !parte.isBlank())
					.reduce((uno, otro) -> uno + "; " + otro).orElse("");
		}
		if (valor instanceof Map<?, ?> campos) {
			List<String> partes = new ArrayList<>();
			campos.forEach((clave, contenido) -> {
				if (contenido instanceof Number || contenido instanceof Boolean) {
					partes.add(clave + " " + contenido);
				} else {
					String texto = aplanar(contenido);
					if (!texto.isBlank()) {
						partes.add(texto);
					}
				}
			});
			return String.join("; ", partes);
		}
		return "";
	}

}
