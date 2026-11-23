package com.uem.ambulancias.medicion.controller;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.uem.ambulancias.comun.web.Textos;
import com.uem.ambulancias.comun.web.UsuarioActual;
import com.uem.ambulancias.medicion.domain.CasoDePrueba;
import com.uem.ambulancias.medicion.domain.Medicion;
import com.uem.ambulancias.medicion.service.BancoDeCasos;
import com.uem.ambulancias.medicion.service.MedicionService;
import com.uem.ambulancias.medicion.service.RepeticionDeCasos;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * El banco de casos, las mediciones del modelo de decisión y la repetición de un incidente (PB-41 y PB-42). Todo es
 * de la central.
 */
@RestController
@RequiredArgsConstructor
public class MedicionController {

	private static final String ORIGEN = "GENERADO|REAL";

	private final BancoDeCasos banco;
	private final MedicionService mediciones;
	private final RepeticionDeCasos repeticion;

	public record GenerarCasosRequest(
			@NotNull(message = "La cantidad es obligatoria.")
			@Min(value = 1, message = "Se genera al menos un caso.")
			@Max(value = 500, message = "Se generan hasta 500 casos por vez.")
			Integer cantidad,
			Long semilla) {
	}

	public record GuardarCasoRealRequest(
			@NotEmpty(message = "Hay que decir si se cumplió cada condición.")
			Map<String, @NotNull(message = "Cada condición lleva verdadero o falso.") Boolean> verdades,
			@Size(max = 500, message = "La nota es demasiado larga.")
			String nota) {
	}

	public record PedirMedicionRequest(
			@Pattern(regexp = ORIGEN, message = "El origen es GENERADO o REAL.")
			String origen,
			@DecimalMin(value = "0.5", message = "El objetivo de precisión va de 0,5 a 1.")
			@DecimalMax(value = "1", message = "El objetivo de precisión va de 0,5 a 1.")
			Double objetivoDePrecision,
			@DecimalMin(value = "0.5", message = "El objetivo de cobertura va de 0,5 a 1.")
			@DecimalMax(value = "1", message = "El objetivo de cobertura va de 0,5 a 1.")
			Double objetivoDeCobertura) {
	}

	public record CasoResponse(Long id, String origen, String tipoSuceso, Long incidenteId, Long semilla, String nota,
			Instant creadoEn, @JsonRawValue String estado, @JsonRawValue String verdades) {

		static CasoResponse de(CasoDePrueba caso) {
			return new CasoResponse(caso.getId(), caso.getOrigen(), caso.getTipoSuceso(),
					caso.getIncidente() == null ? null : caso.getIncidente().getId(), caso.getSemilla(),
					caso.getNota(), caso.getCreadoEn(), caso.getEstado(), caso.getVerdades());
		}

	}

	public record MedicionResponse(Long id, String estado, String origenDeLosCasos, double objetivoDePrecision,
			double objetivoDeCobertura, int casos, int casosMedidos, int casosConError, String proveedor, String modelo,
			Long tokensDeEntrada, Double costo, String error, Instant creadaEn, Instant terminadaEn,
			@JsonRawValue String resultados) {

		static MedicionResponse de(Medicion medicion) {
			return new MedicionResponse(medicion.getId(), medicion.getEstado(), medicion.getOrigenDeLosCasos(),
					medicion.getObjetivoDePrecision(), medicion.getObjetivoDeCobertura(), medicion.getCasos(),
					medicion.getCasosMedidos(), medicion.getCasosConError(), medicion.getProveedor(),
					medicion.getModelo(), medicion.getTokensDeEntrada(), medicion.getCosto(), medicion.getError(),
					medicion.getCreadaEn(), medicion.getTerminadaEn(), medicion.getResultados());
		}

	}

	/** Los casos más nuevos del banco, hasta doscientos. Con {@code origen}, solo los generados o solo los reales. */
	@GetMapping("/banco-de-casos")
	public List<CasoResponse> casos(@RequestParam(required = false) String origen) {
		return banco.listar(origen).stream().map(CasoResponse::de).toList();
	}

	/** Cuántos casos hay de cada origen. */
	@GetMapping("/banco-de-casos/resumen")
	public Map<String, Integer> resumen() {
		return banco.cuantos();
	}

	/**
	 * Genera casos inventados con el protocolo vigente. Sin semilla usa la hora: con la misma semilla salen los mismos
	 * casos. 409 si no hay un protocolo publicado con condiciones.
	 */
	@PostMapping("/banco-de-casos/generacion")
	@ResponseStatus(HttpStatus.CREATED)
	public Map<String, Object> generar(@Valid @RequestBody GenerarCasosRequest request) {
		long semilla = request.semilla() == null ? System.currentTimeMillis() : request.semilla();
		return Map.of("generados", banco.generar(request.cantidad(), semilla), "semilla", semilla);
	}

	/** Borra los casos generados que ninguna medición usó. */
	@DeleteMapping("/banco-de-casos/generados")
	public Map<String, Integer> borrarGenerados() {
		return Map.of("borrados", banco.borrarGeneradosSinMedir());
	}

	/** Guarda un incidente como caso real, con la verdad de cada condición que confirma la central. */
	@PostMapping("/incidentes/{id}/caso-de-prueba")
	@ResponseStatus(HttpStatus.CREATED)
	public CasoResponse guardarReal(@PathVariable("id") Long incidenteId,
			@Valid @RequestBody GuardarCasoRealRequest request) {
		return CasoResponse.de(banco.guardarReal(incidenteId, request.verdades(), Textos.opcional(request.nota())));
	}

	/**
	 * Pide medir al modelo de decisión con los casos del banco. Corre en segundo plano: el resultado se consulta
	 * después. 409 {@code SIN_CASOS_DE_PRUEBA} si el banco está vacío.
	 */
	@PostMapping("/mediciones")
	@ResponseStatus(HttpStatus.CREATED)
	public MedicionResponse medir(@UsuarioActual Long administradorId,
			@Valid @RequestBody PedirMedicionRequest request) {
		return MedicionResponse.de(mediciones.pedir(administradorId, request.origen(),
				request.objetivoDePrecision() == null ? 0.9 : request.objetivoDePrecision(),
				request.objetivoDeCobertura() == null ? 0.95 : request.objetivoDeCobertura()));
	}

	/** Las mediciones, de la más nueva a la más vieja. */
	@GetMapping("/mediciones")
	public List<MedicionResponse> mediciones() {
		return mediciones.listar().stream().map(MedicionResponse::de).toList();
	}

	/** Una medición con sus resultados por condición. 404 si no existe. */
	@GetMapping("/mediciones/{id}")
	public MedicionResponse medicion(@PathVariable Long id) {
		return MedicionResponse.de(mediciones.buscar(id));
	}

	/**
	 * Vuelve a evaluar cada evaluación guardada del incidente con las reglas y el modelo de ahora, y dice qué veredicto
	 * cambiaría. No toca el incidente.
	 */
	@PostMapping("/incidentes/{id}/repeticion")
	public List<RepeticionDeCasos.Repetida> repetir(@PathVariable("id") Long incidenteId) {
		return repeticion.repetir(incidenteId);
	}

}
