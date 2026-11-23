package com.uem.ambulancias.medicion.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.uem.ambulancias.comun.error.CodigoError;
import com.uem.ambulancias.comun.error.ConflictoException;
import com.uem.ambulancias.comun.error.NoEncontradoException;
import com.uem.ambulancias.coordinacion.service.PedidoDeEvaluacion;
import com.uem.ambulancias.coordinacion.service.ProbabilidadesRecibidas;
import com.uem.ambulancias.medicion.domain.CasoDePrueba;
import com.uem.ambulancias.medicion.domain.Medicion;
import com.uem.ambulancias.medicion.domain.MedicionDeCaso;
import com.uem.ambulancias.medicion.repository.CasoDePruebaRepository;
import com.uem.ambulancias.medicion.repository.MedicionDeCasoRepository;
import com.uem.ambulancias.medicion.repository.MedicionRepository;
import com.uem.ambulancias.medicion.service.MetricasDeCalibracion.Muestra;
import com.uem.ambulancias.protocolo.domain.Condicion;
import com.uem.ambulancias.protocolo.domain.TipoDeSuceso;
import com.uem.ambulancias.usuarios.domain.RolUsuario;
import com.uem.ambulancias.usuarios.domain.Usuario;
import com.uem.ambulancias.usuarios.repository.UsuarioRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Las mediciones del modelo de decisión (PB-41). Una medición le pasa al modelo los casos del banco, guarda lo que
 * respondió en cada uno y al final hace las cuentas por condición: cuánto acierta, qué tan bien calibrado está y con
 * qué umbrales convendría usarlo.
 *
 * <p>Cada paso que toca la base va en su transacción corta: la consulta al modelo queda afuera.
 */
@Service
@RequiredArgsConstructor
public class MedicionService {

	private final MedicionRepository mediciones;
	private final MedicionDeCasoRepository medidos;
	private final CasoDePruebaRepository casos;
	private final UsuarioRepository usuarios;
	private final JsonMapper json;

	/** Lo próximo que hay que pasarle al modelo en una medición. */
	public record Paso(Long casoId, PedidoDeEvaluacion pedido) {
	}

	/** Pide una medición sobre los casos de ese origen, o sobre todos. Queda en la cola hasta que el medidor la tome. */
	@Transactional
	public Medicion pedir(Long administradorId, String origen, double objetivoDePrecision,
			double objetivoDeCobertura) {
		Usuario administrador = usuarios.findByIdAndRol(administradorId, RolUsuario.ADMIN)
				.orElseThrow(() -> new NoEncontradoException("No existe el administrador " + administradorId + "."));
		int cuantos = origen == null ? (int) casos.count() : casos.countByOrigen(origen);
		if (cuantos == 0) {
			throw new ConflictoException(CodigoError.SIN_CASOS_DE_PRUEBA,
					"El banco no tiene casos para medir. Genera algunos o guarda casos reales primero.");
		}
		return mediciones.save(Medicion.pedir(origen, objetivoDePrecision, objetivoDeCobertura, cuantos,
				administrador, Instant.now()));
	}

	/** Las mediciones que esperan o quedaron a medias. */
	@Transactional(readOnly = true)
	public List<Long> porHacer() {
		return mediciones.buscarIdsPorHacer();
	}

	/**
	 * El próximo caso de la medición que todavía no pasó por el modelo, con el pedido armado. Vacío cuando ya no queda
	 * ninguno. Un caso que no tiene nada que consultarle al modelo queda anotado con su error y se sigue con el
	 * siguiente.
	 */
	@Transactional
	public Optional<Paso> siguiente(Long medicionId) {
		Medicion medicion = mediciones.buscarParaActualizar(medicionId).orElse(null);
		if (medicion == null || medicion.isTerminada()) {
			return Optional.empty();
		}
		medicion.empezar();
		while (true) {
			PageRequest uno = PageRequest.of(0, 1);
			List<Long> sinMedir = medicion.getOrigenDeLosCasos() == null ? casos.buscarIdsSinMedir(medicionId, uno)
					: casos.buscarIdsSinMedirDeOrigen(medicionId, medicion.getOrigenDeLosCasos(), uno);
			if (sinMedir.isEmpty()) {
				return Optional.empty();
			}
			CasoDePrueba caso = casos.findById(sinMedir.getFirst()).orElseThrow();
			List<PedidoDeEvaluacion.Pregunta> preguntas = caso.getProtocolo().buscarTipo(caso.getTipoSuceso())
					.map(MedicionService::paraElModelo)
					.orElse(List.of());
			if (preguntas.isEmpty()) {
				medidos.saveAndFlush(MedicionDeCaso.conError(medicion, caso, "sin_condiciones_para_el_modelo"));
				medicion.sumarError();
				continue;
			}
			return Optional.of(new Paso(caso.getId(), new PedidoDeEvaluacion(java.util.UUID.randomUUID(),
					caso.getIncidente() == null ? caso.getId() : caso.getIncidente().getId(), caso.getEstado(),
					preguntas)));
		}
	}

	/** El modelo respondió sobre ese caso. */
	@Transactional
	public void anotar(Long medicionId, Long casoId, ProbabilidadesRecibidas recibidas, long milisegundos) {
		Medicion medicion = mediciones.buscarParaActualizar(medicionId).orElseThrow();
		medidos.save(MedicionDeCaso.medido(medicion, casos.getReferenceById(casoId),
				json.writeValueAsString(recibidas.probabilidades()), milisegundos));
		medicion.sumarMedido(recibidas.proveedor(), recibidas.modelo(), recibidas.tokensDeEntrada(),
				recibidas.costo());
	}

	/** El modelo no respondió sobre ese caso. La medición sigue con los demás. */
	@Transactional
	public void anotarError(Long medicionId, Long casoId, String error) {
		Medicion medicion = mediciones.buscarParaActualizar(medicionId).orElseThrow();
		medidos.save(MedicionDeCaso.conError(medicion, casos.getReferenceById(casoId), error));
		medicion.sumarError();
	}

	/** Ya pasaron todos los casos: se hacen las cuentas por condición y la medición queda terminada. */
	@Transactional
	public void cerrar(Long medicionId) {
		Medicion medicion = mediciones.buscarParaActualizar(medicionId).orElse(null);
		if (medicion == null || medicion.isTerminada()) {
			return;
		}
		medicion.terminar(json.writeValueAsString(resultados(medicion)), Instant.now());
	}

	/** La medición no puede seguir: el modelo no está disponible o está mal configurado. */
	@Transactional
	public void fallar(Long medicionId, String error) {
		mediciones.buscarParaActualizar(medicionId).filter(medicion -> !medicion.isTerminada())
				.ifPresent(medicion -> medicion.fallar(error, Instant.now()));
	}

	@Transactional(readOnly = true)
	public List<Medicion> listar() {
		return mediciones.findAllByOrderByIdDesc();
	}

	@Transactional(readOnly = true)
	public Medicion buscar(Long medicionId) {
		return mediciones.findById(medicionId)
				.orElseThrow(() -> new NoEncontradoException("No existe la medición " + medicionId + "."));
	}

	/** Las condiciones que de verdad se le consultan al modelo: las que se resuelven por cantidad nunca le llegan. */
	private static List<PedidoDeEvaluacion.Pregunta> paraElModelo(TipoDeSuceso tipo) {
		return tipo.getCondiciones().stream()
				.filter(condicion -> condicion.getAfectadosDesde() == null)
				.map(condicion -> new PedidoDeEvaluacion.Pregunta(condicion.getCodigo(), condicion.getEnunciado()))
				.toList();
	}

	/** Junta, por tipo de suceso y condición, lo que respondió el modelo con lo que era cierto, y hace las cuentas. */
	private List<Map<String, Object>> resultados(Medicion medicion) {
		record Clave(String tipoSuceso, String condicion) {
		}
		Map<Clave, List<Muestra>> muestras = new LinkedHashMap<>();
		Map<Clave, Condicion> condiciones = new LinkedHashMap<>();
		for (MedicionDeCaso medido : medidos.buscarDeMedicion(medicion.getId())) {
			if (medido.getProbabilidades() == null) {
				continue;
			}
			CasoDePrueba caso = medido.getCaso();
			JsonNode verdades = json.readTree(caso.getVerdades());
			TipoDeSuceso tipo = caso.getProtocolo().buscarTipo(caso.getTipoSuceso()).orElse(null);
			json.readTree(medido.getProbabilidades()).properties().forEach(respuesta -> {
				JsonNode verdad = verdades.path(respuesta.getKey());
				if (!verdad.isBoolean() || !respuesta.getValue().isNumber() || tipo == null) {
					return;
				}
				Clave clave = new Clave(caso.getTipoSuceso(), respuesta.getKey());
				muestras.computeIfAbsent(clave, nueva -> new ArrayList<>())
						.add(new Muestra(respuesta.getValue().asDouble(), verdad.asBoolean()));
				tipo.buscarCondicion(respuesta.getKey()).ifPresent(condicion -> condiciones.putIfAbsent(clave, condicion));
			});
		}
		List<Map<String, Object>> resultados = new ArrayList<>();
		muestras.forEach((clave, delaCondicion) -> {
			Condicion condicion = condiciones.get(clave);
			if (condicion == null) {
				return;
			}
			Map<String, Object> resultado = new LinkedHashMap<>();
			resultado.put("tipoDeSuceso", clave.tipoSuceso());
			resultado.put("condicion", clave.condicion());
			resultado.put("enunciado", condicion.getEnunciado());
			resultado.put("medido", MetricasDeCalibracion.calcular(delaCondicion, condicion.getUmbralActuar(),
					condicion.getUmbralPreguntar(), medicion.getObjetivoDePrecision(),
					medicion.getObjetivoDeCobertura()));
			resultados.add(resultado);
		});
		return resultados;
	}

}
