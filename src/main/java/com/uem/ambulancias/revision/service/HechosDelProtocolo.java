package com.uem.ambulancias.revision.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.uem.ambulancias.medicion.domain.Medicion;
import com.uem.ambulancias.medicion.repository.MedicionRepository;
import com.uem.ambulancias.protocolo.domain.Condicion;
import com.uem.ambulancias.protocolo.domain.Protocolo;
import com.uem.ambulancias.protocolo.domain.TipoDeSuceso;
import com.uem.ambulancias.revision.repository.RevisionDelProtocoloRepository;
import com.uem.ambulancias.revision.service.AnalistaDelProtocolo.Hecho;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cuenta cómo funcionó un protocolo: en cuántos casos se evaluó cada condición y con qué resultado, qué contestaron
 * los testigos, cuántas veces tuvo que decidir una persona y qué dijo la última medición. Cada cuenta sale como un
 * hecho en una frase, que es lo que lee el analista y lo que después se le muestra a la persona como respaldo.
 *
 * <p>Los números los pone el código. El analista no cuenta nada: lee estas frases.
 */
@Component
@RequiredArgsConstructor
public class HechosDelProtocolo {

	private final RevisionDelProtocoloRepository consultas;
	private final MedicionRepository mediciones;
	private final JsonMapper json;

	/** Lo que se sabe de una condición en un tipo de suceso. */
	private static final class Cuenta {
		int evaluados;
		int cumplidas;
		int enDuda;
		int descartadas;
		int sinEvaluar;
		int conModelo;
		double sumaDeProbabilidades;
		int si;
		int no;
		int noSabe;
		int derivadas;
		int laPersonaDijoQueSi;
		int laPersonaDijoQueNo;
		int vencidas;
	}

	/**
	 * Los hechos de ese protocolo, numerados H1, H2… Exige una transacción abierta.
	 *
	 * @param minimoDeCasos por debajo de esto, el hecho avisa que son pocos casos para cambiar umbrales
	 */
	public List<Hecho> contar(Protocolo protocolo, int minimoDeCasos) {
		Map<String, Cuenta> cuentas = new LinkedHashMap<>();
		for (Object[] fila : consultas.buscarUltimosVeredictos(protocolo.getId())) {
			for (JsonNode veredicto : json.readTree((String) fila[1])) {
				Cuenta cuenta = cuentas.computeIfAbsent(clave(fila[0], veredicto.path("condicion").asString()),
						nueva -> new Cuenta());
				cuenta.evaluados++;
				switch (veredicto.path("resultado").asString()) {
					case "CUMPLIDA" -> cuenta.cumplidas++;
					case "EN_DUDA" -> cuenta.enDuda++;
					case "NO_CUMPLIDA" -> cuenta.descartadas++;
					default -> cuenta.sinEvaluar++;
				}
				if (veredicto.path("probabilidad").isNumber()) {
					cuenta.conModelo++;
					cuenta.sumaDeProbabilidades += veredicto.path("probabilidad").asDouble();
				}
			}
		}
		for (Object[] fila : consultas.buscarRespuestasDeTestigos(protocolo.getId())) {
			JsonNode datos = json.readTree((String) fila[1]);
			Cuenta cuenta = cuentas.computeIfAbsent(clave(fila[0], datos.path("condicion").asString()),
					nueva -> new Cuenta());
			switch (datos.path("valor").asString()) {
				case "SI" -> cuenta.si++;
				case "NO" -> cuenta.no++;
				default -> cuenta.noSabe++;
			}
		}
		for (Object[] fila : consultas.buscarDerivacionesPorCondicion(protocolo.getId())) {
			Cuenta cuenta = cuentas.computeIfAbsent(clave(fila[0], (String) fila[1]), nueva -> new Cuenta());
			cuenta.derivadas++;
			String opcion = fila[3] == null ? "" : fila[3].toString();
			if ("VENCIDA".equals(String.valueOf(fila[2]))) {
				cuenta.vencidas++;
			} else if ("SE_CUMPLE".equals(opcion)) {
				cuenta.laPersonaDijoQueSi++;
			} else if ("NO_SE_CUMPLE".equals(opcion)) {
				cuenta.laPersonaDijoQueNo++;
			}
		}
		Map<String, Long> incidentes = new LinkedHashMap<>();
		for (Object[] fila : consultas.contarIncidentesPorTipo(protocolo.getId())) {
			incidentes.put((String) fila[0], (Long) fila[1]);
		}
		Map<String, JsonNode> medidos = ultimaMedicion();

		List<Hecho> hechos = new ArrayList<>();
		for (TipoDeSuceso tipo : protocolo.getTipos()) {
			long cuantos = incidentes.getOrDefault(tipo.getCodigo(), 0L);
			if (cuantos > 0) {
				agregar(hechos, tipo.getCodigo(), null, (int) cuantos, "«" + tipo.getNombre() + "»: hubo " + cuantos
						+ (cuantos == 1 ? " incidente" : " incidentes") + " de este tipo con este protocolo.");
			}
			for (Condicion condicion : tipo.getCondiciones()) {
				Cuenta cuenta = cuentas.get(clave(tipo.getCodigo(), condicion.getCodigo()));
				if (cuenta != null) {
					deLaCondicion(hechos, tipo, condicion, cuenta, minimoDeCasos);
				}
				JsonNode medido = medidos.get(clave(tipo.getCodigo(), condicion.getCodigo()));
				if (medido != null && medido.path("casos").asInt() > 0) {
					deLaMedicion(hechos, tipo, condicion, medido);
				}
			}
		}
		return hechos;
	}

	private static void deLaCondicion(List<Hecho> hechos, TipoDeSuceso tipo, Condicion condicion, Cuenta cuenta,
			int minimoDeCasos) {
		String nombre = "«" + condicion.getEnunciado() + "» (" + tipo.getNombre() + ")";
		if (cuenta.evaluados > 0) {
			StringBuilder texto = new StringBuilder(nombre).append(": se evaluó en ").append(casos(cuenta.evaluados))
					.append(". Se cumplió en ").append(cuenta.cumplidas).append(", quedó en duda en ")
					.append(cuenta.enDuda).append(", se descartó en ").append(cuenta.descartadas)
					.append(" y quedó sin evaluar en ").append(cuenta.sinEvaluar).append(".");
			if (cuenta.conModelo > 0) {
				texto.append(" El modelo le dio en promedio ")
						.append(Math.round(100 * cuenta.sumaDeProbabilidades / cuenta.conModelo))
						.append(" % de probabilidad en ").append(casos(cuenta.conModelo)).append(".");
			}
			texto.append(" Hoy se actúa desde ").append(porcentaje(condicion.getUmbralActuar()))
					.append(" y se pregunta desde ").append(porcentaje(condicion.getUmbralPreguntar())).append(".");
			if (cuenta.evaluados < minimoDeCasos) {
				texto.append(" Son pocos casos para cambiar umbrales: hacen falta al menos ").append(minimoDeCasos)
						.append(".");
			}
			agregar(hechos, tipo.getCodigo(), condicion.getCodigo(), cuenta.evaluados, texto.toString());
		}
		int respuestas = cuenta.si + cuenta.no + cuenta.noSabe;
		if (condicion.getPregunta() != null && (respuestas > 0 || cuenta.evaluados > 0)) {
			String texto = respuestas == 0
					? nombre + ": nadie contestó la pregunta «" + condicion.getPregunta() + "» en "
							+ casos(cuenta.evaluados) + "."
					: nombre + ": la pregunta «" + condicion.getPregunta() + "» se contestó " + veces(respuestas)
							+ ": " + cuenta.si + " sí, " + cuenta.no + " no y " + cuenta.noSabe + " «no sé».";
			agregar(hechos, tipo.getCodigo(), condicion.getCodigo(), Math.max(respuestas, cuenta.evaluados), texto);
		}
		if (cuenta.derivadas > 0) {
			agregar(hechos, tipo.getCodigo(), condicion.getCodigo(), cuenta.derivadas, nombre
					+ ": tuvo que decidirla una persona " + veces(cuenta.derivadas) + ". Dijo que se cumple en "
					+ cuenta.laPersonaDijoQueSi + ", que no en " + cuenta.laPersonaDijoQueNo + " y " + cuenta.vencidas
					+ " vencieron sin respuesta.");
		}
	}

	private static void deLaMedicion(List<Hecho> hechos, TipoDeSuceso tipo, Condicion condicion, JsonNode medido) {
		StringBuilder texto = new StringBuilder("«").append(condicion.getEnunciado()).append("» (")
				.append(tipo.getNombre()).append("): en la última medición, con ").append(casos(medido.path("casos").asInt()))
				.append(" de respuesta conocida");
		if (medido.path("brier").isNumber()) {
			texto.append(", el error de Brier fue ")
					.append(String.format(Locale.ROOT, "%.3f", medido.path("brier").asDouble()))
					.append(" (0 es perfecto; 0,25 es contestar siempre 50 %)");
		}
		texto.append(".");
		if (medido.path("actuarSugerido").isNumber()) {
			texto.append(" Umbral sugerido para actuar: ").append(porcentaje(medido.path("actuarSugerido").asDouble()))
					.append(".");
		}
		if (medido.path("preguntarSugerido").isNumber()) {
			texto.append(" Umbral sugerido para preguntar: ")
					.append(porcentaje(medido.path("preguntarSugerido").asDouble())).append(".");
		}
		if (medido.path("advertencia").isString()) {
			texto.append(" Advertencia: ").append(medido.path("advertencia").asString());
		}
		agregar(hechos, tipo.getCodigo(), condicion.getCodigo(), medido.path("casos").asInt(), texto.toString());
	}

	/** Lo medido por condición en la última medición terminada, o nada si no hay ninguna. */
	private Map<String, JsonNode> ultimaMedicion() {
		Map<String, JsonNode> medidos = new LinkedHashMap<>();
		mediciones.findAllByOrderByIdDesc().stream()
				.filter(medicion -> Medicion.HECHA.equals(medicion.getEstado()) && medicion.getResultados() != null)
				.findFirst()
				.ifPresent(medicion -> {
					for (JsonNode resultado : json.readTree(medicion.getResultados())) {
						medidos.put(clave(resultado.path("tipoDeSuceso").asString(),
								resultado.path("condicion").asString()), resultado.path("medido"));
					}
				});
		return medidos;
	}

	private static void agregar(List<Hecho> hechos, String tipo, String condicion, int casos, String texto) {
		hechos.add(new Hecho("H" + (hechos.size() + 1), tipo, condicion, texto, casos));
	}

	private static String clave(Object tipo, String condicion) {
		return tipo + "/" + condicion;
	}

	private static String casos(int cuantos) {
		return cuantos == 1 ? "1 caso" : cuantos + " casos";
	}

	private static String veces(int cuantas) {
		return cuantas == 1 ? "1 vez" : cuantas + " veces";
	}

	private static String porcentaje(double proporcion) {
		return Math.round(proporcion * 100) + " %";
	}

}
