package com.uem.ambulancias.agente.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.uem.ambulancias.agente.domain.LlamadaDeHerramienta;
import com.uem.ambulancias.agente.repository.LlamadaDeHerramientaRepository;
import com.uem.ambulancias.comun.error.ConflictoException;
import com.uem.ambulancias.comun.error.NoEncontradoException;
import com.uem.ambulancias.memoria.service.IndiceDeLaNorma;
import com.uem.ambulancias.memoria.service.MemoriaDeCasos;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * El catálogo de herramientas del agente y la puerta por la que se las llama. Cada llamada queda registrada, salga
 * bien o mal: así se sabe qué miró el agente antes de concluir algo.
 */
@Slf4j
@Component
public class HerramientasDelAgente {

	private static final String INCIDENTE = "incidenteId";

	private final List<Herramienta> herramientas;
	private final LlamadaDeHerramientaRepository llamadas;
	private final JsonMapper json;

	public HerramientasDelAgente(ConsultasDelAgente consultas, AccionesDelAgente acciones,
			LlamadaDeHerramientaRepository llamadas, JsonMapper json, IndiceDeLaNorma norma, MemoriaDeCasos memoria,
			AgenteProperties config) {
		this.llamadas = llamadas;
		this.json = json;
		this.herramientas = List.of(
				new Herramienta("ver_caso", "Ver el caso",
						"Todo lo que se sabe de un incidente: lo que marcaron los testigos, lo que el análisis "
								+ "describió de sus fotos y audios, las condiciones de su tipo de suceso, lo último "
								+ "que se evaluó, lo que necesita y las unidades que ya van. Es la fuente principal: "
								+ "úsala primero.",
						esquema(Map.of(INCIDENTE, entero("El identificador del incidente.")), INCIDENTE),
						argumentos -> consultas.verCaso(entero(argumentos, INCIDENTE)), true),
				new Herramienta("ver_unidades", "Ver las unidades",
						"La flota activa de la central: tipo de cada unidad, su estado y si tiene tripulación de "
								+ "turno. Con un incidente, además cuántos minutos tarda cada una en llegar.",
						esquema(Map.of(INCIDENTE, entero("Opcional. Para saber cuánto tarda cada unidad en llegar."))),
						argumentos -> consultas.verUnidades(enteroOpcional(argumentos, INCIDENTE)), true),
				new Herramienta("ver_casos_abiertos", "Ver los casos abiertos",
						"Los incidentes abiertos de la central, con lo que necesita y lo que ya tiene cada uno. Sirve "
								+ "para saber con qué otros casos se reparten las unidades.",
						esquema(Map.of()),
						argumentos -> consultas.verCasosAbiertos(), true),
				new Herramienta("ver_traslados_proximos", "Ver los traslados próximos",
						"Los traslados programados que salen en las próximas horas: van a ocupar una unidad.",
						esquema(Map.of("horas", entero("Cuántas horas hacia adelante mirar. Por defecto, 6."))),
						argumentos -> consultas.verTrasladosProximos(
								Optional.ofNullable(enteroOpcional(argumentos, "horas")).map(Long::intValue).orElse(6)),
						true),
				new Herramienta("buscar_casos_parecidos", "Buscar casos parecidos",
						"Incidentes ya cerrados que se parecen a este: cómo terminaron, cuántas unidades fueron y qué "
								+ "condiciones se cumplieron. Si la memoria de casos no encuentra parecidos, trae los "
								+ "últimos del mismo tipo de suceso. Son una referencia, no una regla.",
						esquema(Map.of(INCIDENTE, entero("El incidente para el que se buscan casos parecidos.")),
								INCIDENTE),
						// La memoria se consulta antes y aparte: llama al servicio de IA, y eso no va dentro de la
						// transacción en la que se arma la respuesta.
						argumentos -> consultas.buscarCasosParecidos(entero(argumentos, INCIDENTE),
								memoria.parecidosA(entero(argumentos, INCIDENTE), config.casosParecidos())),
						true),
				new Herramienta("buscar_en_norma", "Buscar en la norma",
						"Qué dicen el protocolo vigente y las normas nacionales sobre un tema: las condiciones del "
								+ "protocolo que lo nombran, con la respuesta que les asigna, y los extractos de las "
								+ "normas más cercanos a la consulta, con su documento, sección y página para citarlos.",
						esquema(Map.of("consulta", texto("Lo que se busca, por ejemplo «persona atrapada» o «qué "
								+ "ambulancia corresponde a un paciente crítico».")),
								"consulta"),
						argumentos -> consultas.buscarEnNorma(texto(argumentos, "consulta"),
								norma.buscar(texto(argumentos, "consulta"))),
						true),
				new Herramienta("mostrar_casillas", "Pedirle una respuesta al testigo",
						"Le pide a quienes alertaron que contesten una o más condiciones del formulario. Solo sirve "
								+ "para las condiciones que tienen pregunta. La respuesta no llega en esta "
								+ "conversación.",
						esquema(Map.of(INCIDENTE, entero("El identificador del incidente."),
								"condiciones", Map.of("type", "array", "items", Map.of("type", "string"), "minItems", 1,
										"description", "Los códigos de las condiciones a preguntar.")),
								INCIDENTE, "condiciones"),
						argumentos -> acciones.mostrarCasillas(entero(argumentos, INCIDENTE),
								textos(argumentos, "condiciones")), false),
				new Herramienta("proponer_al_operador", "Proponerle algo al operador",
						"Le pasa una propuesta a una persona de la central. Es para lo que el sistema no hace solo: "
								+ "quitar o cambiar una unidad, cancelar el caso, unir dos incidentes. La persona "
								+ "decide; si nadie contesta, no se hace.",
						esquema(Map.of(INCIDENTE, entero("El identificador del incidente."),
								"propuesta", texto("Qué se propone hacer, en una frase."),
								"justificacion", texto("Por qué, con los datos en que se apoya.")),
								INCIDENTE, "propuesta", "justificacion"),
						argumentos -> acciones.proponerAlOperador(entero(argumentos, INCIDENTE),
								texto(argumentos, "propuesta"), texto(argumentos, "justificacion")), false));
	}

	public List<Herramienta> listar() {
		return herramientas;
	}

	public boolean existe(String nombre) {
		return herramientas.stream().anyMatch(herramienta -> herramienta.nombre().equals(nombre));
	}

	/**
	 * El resultado de una llamada.
	 *
	 * @param contenido lo que devolvió la herramienta o, si falló, el mensaje para el agente
	 */
	public record Resultado(Object contenido, boolean error) {
	}

	/**
	 * Llama a la herramienta. Un error que el agente puede entender (un incidente que no existe, un dato que falta)
	 * vuelve como resultado con error, no como excepción: así el modelo lo lee y se corrige.
	 */
	public Resultado llamar(String nombre, JsonNode argumentos) {
		Herramienta herramienta = herramientas.stream()
				.filter(candidata -> candidata.nombre().equals(nombre))
				.findFirst()
				.orElseThrow(() -> new IllegalArgumentException("No existe la herramienta " + nombre + "."));
		long inicio = System.nanoTime();
		Resultado resultado;
		try {
			resultado = new Resultado(herramienta.accion().apply(argumentos), false);
		} catch (ErrorDeHerramienta | NoEncontradoException | ConflictoException e) {
			resultado = new Resultado(e.getMessage(), true);
		} catch (RuntimeException e) {
			log.error("La herramienta {} falló de este lado.", nombre, e);
			resultado = new Resultado("La herramienta no pudo responder por un error interno.", true);
		}
		registrar(nombre, argumentos, resultado, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicio));
		return resultado;
	}

	private void registrar(String nombre, JsonNode argumentos, Resultado resultado, long milisegundos) {
		try {
			JsonNode incidente = argumentos.path(INCIDENTE);
			llamadas.save(LlamadaDeHerramienta.registrar(nombre,
					incidente.isIntegralNumber() ? incidente.asLong() : null, json.writeValueAsString(argumentos),
					!resultado.error(), resultado.error() ? String.valueOf(resultado.contenido()) : null,
					milisegundos, Instant.now()));
		} catch (RuntimeException e) {
			// El registro es para mirar después: que falle no le quita al agente su respuesta.
			log.error("No se pudo registrar la llamada a {}.", nombre, e);
		}
	}

	private static Map<String, Object> esquema(Map<String, Object> propiedades, String... obligatorias) {
		Map<String, Object> esquema = new LinkedHashMap<>();
		esquema.put("type", "object");
		esquema.put("properties", propiedades);
		esquema.put("required", List.of(obligatorias));
		esquema.put("additionalProperties", false);
		return esquema;
	}

	private static Map<String, Object> entero(String descripcion) {
		return Map.of("type", "integer", "description", descripcion);
	}

	private static Map<String, Object> texto(String descripcion) {
		return Map.of("type", "string", "description", descripcion);
	}

	private static long entero(JsonNode argumentos, String campo) {
		Long valor = enteroOpcional(argumentos, campo);
		if (valor == null) {
			throw new ErrorDeHerramienta("Falta «" + campo + "», que tiene que ser un número entero.");
		}
		return valor;
	}

	private static Long enteroOpcional(JsonNode argumentos, String campo) {
		JsonNode valor = argumentos.path(campo);
		if (valor.isMissingNode() || valor.isNull()) {
			return null;
		}
		if (!valor.isIntegralNumber()) {
			throw new ErrorDeHerramienta("«" + campo + "» tiene que ser un número entero.");
		}
		return valor.asLong();
	}

	private static String texto(JsonNode argumentos, String campo) {
		JsonNode valor = argumentos.path(campo);
		if (!valor.isString() || valor.asString().isBlank()) {
			throw new ErrorDeHerramienta("Falta «" + campo + "», que tiene que ser un texto.");
		}
		return valor.asString();
	}

	private static List<String> textos(JsonNode argumentos, String campo) {
		JsonNode valor = argumentos.path(campo);
		List<String> textos = new ArrayList<>();
		if (valor.isArray()) {
			for (JsonNode elemento : valor) {
				if (elemento.isString() && !elemento.asString().isBlank()) {
					textos.add(elemento.asString().trim());
				}
			}
		}
		if (textos.isEmpty()) {
			throw new ErrorDeHerramienta("Falta «" + campo + "», que tiene que ser una lista de códigos.");
		}
		return textos;
	}

}
