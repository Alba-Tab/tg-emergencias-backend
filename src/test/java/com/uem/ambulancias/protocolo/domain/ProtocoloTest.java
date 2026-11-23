package com.uem.ambulancias.protocolo.domain;

import static com.uem.ambulancias.soporte.Conflictos.rechazaCon;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.uem.ambulancias.comun.error.CodigoError;
import com.uem.ambulancias.flota.domain.TipoUnidad;

@Tag("PB-31")
class ProtocoloTest {

	private static final Instant AHORA = Instant.parse("2026-11-22T15:00:00Z");

	private static Condicion condicion(String codigo, String pregunta) {
		return Condicion.de(codigo, "Enunciado de " + codigo, pregunta, 0.8, 0.4, 1, TipoUnidad.II, false, null);
	}

	private static TipoDeSuceso tipo(String codigo, Condicion... condiciones) {
		return TipoDeSuceso.de(codigo, "Nombre de " + codigo, List.of(condiciones));
	}

	private static Protocolo borrador() {
		return Protocolo.borrador(1, null, AHORA);
	}

	@Test
	@DisplayName("PB-31 · el borrador nace vacío y guarda sus tipos y condiciones en el orden en que llegan")
	void cargaEnOrden() {
		Protocolo protocolo = borrador();
		assertThat(protocolo.isBorrador()).isTrue();
		assertThat(protocolo.getTipos()).isEmpty();

		protocolo.cargar("Reglamento de prueba", List.of(
				tipo("ACCIDENTE", condicion("ATRAPADO", "¿Hay alguien atrapado?"), condicion("CONTRADICCION", null)),
				tipo("INCONSCIENTE")));

		assertThat(protocolo.getNota()).isEqualTo("Reglamento de prueba");
		assertThat(protocolo.getTipos()).extracting(TipoDeSuceso::getCodigo).containsExactly("ACCIDENTE", "INCONSCIENTE");
		assertThat(protocolo.getTipos()).extracting(TipoDeSuceso::getOrden).containsExactly(0, 1);
		TipoDeSuceso accidente = protocolo.buscarTipo("ACCIDENTE").orElseThrow();
		assertThat(accidente.getProtocolo()).isSameAs(protocolo);
		assertThat(accidente.getCondiciones()).extracting(Condicion::getOrden).containsExactly(0, 1);
		assertThat(accidente.buscarCondicion("ATRAPADO")).isPresent();
		assertThat(protocolo.buscarTipo("OTRO")).isEmpty();
	}

	@Test
	@DisplayName("PB-31 · al testigo solo se le preguntan las condiciones que tienen pregunta")
	void soloLasQueTienenPregunta() {
		TipoDeSuceso accidente = tipo("ACCIDENTE", condicion("ATRAPADO", "¿Hay alguien atrapado?"),
				condicion("CONTRADICCION", null));

		assertThat(accidente.conPregunta()).extracting(Condicion::getCodigo).containsExactly("ATRAPADO");
	}

	@Test
	@DisplayName("PB-31 · dos tipos de suceso o dos condiciones no pueden compartir el código")
	void codigosRepetidos() {
		rechazaCon(CodigoError.PROTOCOLO_INVALIDO,
				() -> borrador().cargar(null, List.of(tipo("ACCIDENTE"), tipo("ACCIDENTE"))));
		rechazaCon(CodigoError.PROTOCOLO_INVALIDO,
				() -> tipo("ACCIDENTE", condicion("ATRAPADO", null), condicion("ATRAPADO", null)));
	}

	@Test
	@DisplayName("PB-31 · los umbrales van entre 0 y 1 y el de preguntar no pasa al de actuar")
	void umbrales() {
		rechazaCon(CodigoError.PROTOCOLO_INVALIDO,
				() -> Condicion.de("ATRAPADO", "Hay un atrapado", null, 0.4, 0.8, 0, null, false, null));
		rechazaCon(CodigoError.PROTOCOLO_INVALIDO,
				() -> Condicion.de("ATRAPADO", "Hay un atrapado", null, 0.8, 0, 0, null, false, null));
		rechazaCon(CodigoError.PROTOCOLO_INVALIDO,
				() -> Condicion.de("ATRAPADO", "Hay un atrapado", null, 1.2, 0.4, 0, null, false, null));
		rechazaCon(CodigoError.PROTOCOLO_INVALIDO,
				() -> Condicion.de("ATRAPADO", "Hay un atrapado", null, 0.8, 0.4, -1, null, false, null));

		Condicion igual = Condicion.de("ATRAPADO", "Hay un atrapado", null, 0.5, 0.5, 0, null, false, null);
		assertThat(igual.getUmbralActuar()).isEqualTo(igual.getUmbralPreguntar());
	}

	@Test
	@DisplayName("PB-31 · un borrador sin tipos de suceso no se puede publicar")
	void noPublicaVacio() {
		rechazaCon(CodigoError.PROTOCOLO_INVALIDO, () -> borrador().publicar(AHORA));
	}

	@Test
	@DisplayName("PB-31 · una versión publicada rige desde ese momento y ya no se edita")
	void publicadoNoSeEdita() {
		Protocolo protocolo = borrador();
		protocolo.cargar(null, List.of(tipo("ACCIDENTE")));

		protocolo.publicar(AHORA);

		assertThat(protocolo.getEstado()).isEqualTo(EstadoProtocolo.VIGENTE);
		assertThat(protocolo.getPublicadoEn()).isEqualTo(AHORA);
		rechazaCon(CodigoError.TRANSICION_INVALIDA, protocolo::vaciar);
		rechazaCon(CodigoError.TRANSICION_INVALIDA, () -> protocolo.cargar(null, List.of(tipo("OTRO"))));
		rechazaCon(CodigoError.TRANSICION_INVALIDA, () -> protocolo.publicar(AHORA));
	}

	@Test
	@DisplayName("PB-31 · solo la versión vigente se retira")
	void retiro() {
		Protocolo protocolo = borrador();
		rechazaCon(CodigoError.TRANSICION_INVALIDA, protocolo::retirar);
		protocolo.cargar(null, List.of(tipo("ACCIDENTE")));
		protocolo.publicar(AHORA);

		protocolo.retirar();

		assertThat(protocolo.getEstado()).isEqualTo(EstadoProtocolo.RETIRADO);
		rechazaCon(CodigoError.TRANSICION_INVALIDA, protocolo::retirar);
	}

	@Test
	@DisplayName("PB-31 · la copia de un tipo trae sus condiciones y no queda atada al protocolo de origen")
	void copia() {
		Protocolo origen = borrador();
		origen.cargar(null, List.of(tipo("ACCIDENTE", condicion("ATRAPADO", "¿Hay alguien atrapado?"))));
		TipoDeSuceso original = origen.getTipos().get(0);

		TipoDeSuceso copia = original.copiar();

		assertThat(copia).isNotSameAs(original);
		assertThat(copia.getProtocolo()).isNull();
		assertThat(copia.getCodigo()).isEqualTo("ACCIDENTE");
		assertThat(copia.getCondiciones()).hasSize(1);
		Condicion condicion = copia.getCondiciones().get(0);
		assertThat(condicion).isNotSameAs(original.getCondiciones().get(0));
		assertThat(condicion.getTipoDeSuceso()).isSameAs(copia);
		assertThat(condicion.getPregunta()).isEqualTo("¿Hay alguien atrapado?");
		assertThat(condicion.getTipoMinimo()).isEqualTo(TipoUnidad.II);
		assertThat(condicion.getUnidadesAdicionales()).isEqualTo(1);
	}

}
