package com.uem.ambulancias.agente.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.uem.ambulancias.agente.service.AgenteProperties;
import com.uem.ambulancias.agente.service.Herramienta;
import com.uem.ambulancias.agente.service.HerramientasDelAgente;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * El servidor MCP de la central: por acá el agente del servicio de IA pide sus herramientas (PB-39).
 *
 * <p>Habla el transporte HTTP de MCP en su forma más simple, sin estado y sin flujo de eventos:
 *
 * <ul>
 * <li>un pedido JSON-RPC recibe un 200 con su respuesta en JSON;</li>
 * <li>una notificación, que no trae {@code id}, recibe un 202 sin cuerpo;</li>
 * <li>GET y DELETE reciben 405, porque no hay flujo ni sesión que abrir o cerrar.</li>
 * </ul>
 *
 * <p>Atiende el saludo {@code initialize} de las versiones 2025-03-26 y 2025-06-18. A un cliente que pide una versión
 * más nueva le responde cuáles conoce, y el cliente vuelve con una de esas.
 *
 * <p>Solo entra quien trae el token del agente. Si la central no tiene uno configurado, la ruta no existe. Es una
 * puerta entre servidores: un pedido que viene de una página web, con cabecera {@code Origin}, se rechaza.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class McpController {

	static final List<String> VERSIONES = List.of("2025-03-26", "2025-06-18");

	static final int PEDIDO_INVALIDO = -32600;
	static final int METODO_DESCONOCIDO = -32601;
	static final int PARAMETROS_INVALIDOS = -32602;
	static final int NO_AUTORIZADO = -32001;
	static final int VERSION_NO_SOPORTADA = -32022;

	private static final String CABECERA_DE_VERSION = "MCP-Protocol-Version";

	private final AgenteProperties config;
	private final HerramientasDelAgente herramientas;
	private final JsonMapper json;

	@PostMapping(value = "/mcp", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Object> recibir(
			@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String autorizacion,
			@RequestHeader(name = HttpHeaders.ORIGIN, required = false) String origen,
			@RequestHeader(name = CABECERA_DE_VERSION, required = false) String version,
			@RequestBody(required = false) JsonNode mensaje) {
		if (!config.habilitado()) {
			return ResponseEntity.notFound().build();
		}
		if (origen != null) {
			return rechazo(HttpStatus.FORBIDDEN, PEDIDO_INVALIDO, "Esta puerta no atiende pedidos de un navegador.");
		}
		if (!autorizado(autorizacion)) {
			return rechazo(HttpStatus.UNAUTHORIZED, NO_AUTORIZADO, "No autorizado.");
		}
		if (mensaje == null || !mensaje.isObject() || !"2.0".equals(mensaje.path("jsonrpc").asString(""))
				|| (mensaje.has("method") && !mensaje.path("method").isString())) {
			return rechazo(HttpStatus.BAD_REQUEST, PEDIDO_INVALIDO, "Se espera un único mensaje JSON-RPC 2.0.");
		}
		JsonNode id = mensaje.get("id");
		if (id == null || id.isNull() || !mensaje.has("method")) {
			// Una notificación o una respuesta del cliente: se acepta y no se contesta nada.
			return ResponseEntity.accepted().build();
		}
		String metodo = mensaje.path("method").asString();
		JsonNode parametros = mensaje.path("params");
		if ("initialize".equals(metodo)) {
			return ResponseEntity.ok(resultado(id, saludo(parametros.path("protocolVersion").asString(""))));
		}
		if (version != null && !VERSIONES.contains(version)) {
			// Un cliente más nuevo prueba primero con su versión: con esta respuesta sabe cuáles se hablan acá.
			return ResponseEntity.badRequest().body(error(id, VERSION_NO_SOPORTADA, "Versión del protocolo no soportada.",
					Map.of("supported", VERSIONES, "requested", version)));
		}
		return ResponseEntity.ok(switch (metodo) {
			case "ping" -> resultado(id, Map.of());
			case "tools/list" -> resultado(id, Map.of("tools", herramientas.listar().stream().map(McpController::descrita).toList()));
			case "tools/call" -> llamar(id, parametros);
			default -> error(id, METODO_DESCONOCIDO, "Método desconocido: " + metodo, null);
		});
	}

	/** No hay flujo de eventos que abrir. */
	@GetMapping("/mcp")
	public ResponseEntity<Void> sinFlujo() {
		return config.habilitado() ? ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build()
				: ResponseEntity.notFound().build();
	}

	/** No hay sesión que cerrar. */
	@DeleteMapping("/mcp")
	public ResponseEntity<Void> sinSesion() {
		return sinFlujo();
	}

	/** Si el cliente pide una versión que se habla acá, se usa esa; si no, se le ofrece la más nueva de las propias. */
	private static Map<String, Object> saludo(String pedida) {
		Map<String, Object> saludo = new LinkedHashMap<>();
		saludo.put("protocolVersion", VERSIONES.contains(pedida) ? pedida : VERSIONES.getLast());
		saludo.put("capabilities", Map.of("tools", Map.of()));
		saludo.put("serverInfo", Map.of("name", "sga-central", "title", "Central de ambulancias", "version", "1.0.0"));
		saludo.put("instructions", "Herramientas de la central de ambulancias. Las que leen no cambian nada; las "
				+ "otras dos le piden algo a una persona. Ninguna mueve una unidad.");
		return saludo;
	}

	private static Map<String, Object> descrita(Herramienta herramienta) {
		Map<String, Object> descrita = new LinkedHashMap<>();
		descrita.put("name", herramienta.nombre());
		descrita.put("title", herramienta.titulo());
		descrita.put("description", herramienta.descripcion());
		descrita.put("inputSchema", herramienta.esquema());
		descrita.put("annotations", Map.of("readOnlyHint", herramienta.soloLee()));
		return descrita;
	}

	/**
	 * Una herramienta que no existe es un error del protocolo. Una que existe y no pudo hacer lo pedido devuelve un
	 * resultado con {@code isError}: así el modelo lee el motivo y se corrige.
	 */
	private Map<String, Object> llamar(JsonNode id, JsonNode parametros) {
		String nombre = parametros.path("name").asString("");
		if (!herramientas.existe(nombre)) {
			return error(id, PARAMETROS_INVALIDOS, "Herramienta desconocida: " + nombre, null);
		}
		JsonNode argumentos = parametros.path("arguments");
		HerramientasDelAgente.Resultado resultado = herramientas.llamar(nombre,
				argumentos.isObject() ? argumentos : json.createObjectNode());
		String texto = resultado.error() ? String.valueOf(resultado.contenido())
				: json.writeValueAsString(resultado.contenido());
		Map<String, Object> cuerpo = new LinkedHashMap<>();
		cuerpo.put("content", List.of(Map.of("type", "text", "text", texto)));
		cuerpo.put("isError", resultado.error());
		return resultado(id, cuerpo);
	}

	private boolean autorizado(String autorizacion) {
		if (autorizacion == null) {
			return false;
		}
		// Comparación en tiempo constante: no deja adivinar el token midiendo cuánto tarda la respuesta.
		return MessageDigest.isEqual(autorizacion.getBytes(StandardCharsets.UTF_8),
				("Bearer " + config.token()).getBytes(StandardCharsets.UTF_8));
	}

	private static Map<String, Object> resultado(JsonNode id, Object resultado) {
		Map<String, Object> respuesta = new LinkedHashMap<>();
		respuesta.put("jsonrpc", "2.0");
		respuesta.put("id", id);
		respuesta.put("result", resultado);
		return respuesta;
	}

	private static Map<String, Object> error(JsonNode id, int codigo, String mensaje, Object datos) {
		Map<String, Object> detalle = new LinkedHashMap<>();
		detalle.put("code", codigo);
		detalle.put("message", mensaje);
		if (datos != null) {
			detalle.put("data", datos);
		}
		Map<String, Object> respuesta = new LinkedHashMap<>();
		respuesta.put("jsonrpc", "2.0");
		if (id != null) {
			respuesta.put("id", id);
		}
		respuesta.put("error", detalle);
		return respuesta;
	}

	private static ResponseEntity<Object> rechazo(HttpStatus estado, int codigo, String mensaje) {
		return ResponseEntity.status(estado).contentType(MediaType.APPLICATION_JSON)
				.body(error(null, codigo, mensaje, null));
	}

}
