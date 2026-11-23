package com.uem.ambulancias.medicion.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.uem.ambulancias.medicion.service.MetricasDeCalibracion.ConUmbrales;
import com.uem.ambulancias.medicion.service.MetricasDeCalibracion.Muestra;
import com.uem.ambulancias.medicion.service.MetricasDeCalibracion.Resultado;

/** Las cuentas con las que se mide al modelo de decisión y se le proponen umbrales. */
@Tag("PB-41")
class MetricasDeCalibracionTest {

	private static List<Muestra> muestras(Object... probabilidadesYVerdades) {
		List<Muestra> muestras = new ArrayList<>();
		for (int i = 0; i < probabilidadesYVerdades.length; i += 2) {
			muestras.add(new Muestra((Double) probabilidadesYVerdades[i], (Boolean) probabilidadesYVerdades[i + 1]));
		}
		return muestras;
	}

	/** Cuarenta casos: los ciertos con probabilidad alta, los falsos con baja, y dos que el modelo confunde. */
	private static List<Muestra> modeloQueAciertaCasiSiempre() {
		List<Muestra> muestras = new ArrayList<>();
		for (int i = 0; i < 14; i++) {
			muestras.add(new Muestra(0.9, true));
		}
		for (int i = 0; i < 24; i++) {
			muestras.add(new Muestra(0.1, false));
		}
		muestras.add(new Muestra(0.3, true));
		muestras.add(new Muestra(0.6, false));
		return muestras;
	}

	@Test
	@DisplayName("PB-41 · con unos umbrales se cuenta cuándo se actúa, cuándo se duda, cuándo se descarta y cuántos ciertos se dejan pasar")
	void conUmbrales() {
		ConUmbrales medido = MetricasDeCalibracion.conUmbrales(modeloQueAciertaCasiSiempre(), 15, 0.8, 0.4);

		assertThat(medido.actuan()).isEqualTo(14);
		assertThat(medido.aciertosAlActuar()).isEqualTo(14);
		assertThat(medido.precisionAlActuar()).isEqualTo(1.0);
		assertThat(medido.enDuda()).isEqualTo(1);
		assertThat(medido.descartadas()).isEqualTo(25);
		assertThat(medido.positivosDescartados()).isEqualTo(1);
		assertThat(medido.cobertura()).isEqualTo(0.933);
	}

	@Test
	@DisplayName("PB-41 · el umbral para actuar es el más bajo con el que se acierta lo pedido, y el de preguntar el más alto que no deja pasar ciertos de más")
	void umbralesSugeridos() {
		Resultado resultado = MetricasDeCalibracion.calcular(modeloQueAciertaCasiSiempre(), 0.8, 0.4, 0.9, 0.95);

		assertThat(resultado.casos()).isEqualTo(40);
		assertThat(resultado.positivos()).isEqualTo(15);
		// Desde 0,3 se actuaría en 16 casos con 15 aciertos (94 %): alcanza el 90 % pedido. Desde 0,1, con 15 de 40, no.
		assertThat(resultado.actuarSugerido()).isEqualTo(0.3);
		// Con 15 ciertos y 95 % de cobertura no se puede dejar pasar ninguno: el umbral baja hasta el cierto de 0,3.
		assertThat(resultado.preguntarSugerido()).isEqualTo(0.3);
		assertThat(resultado.conLosSugeridos().actuan()).isEqualTo(16);
		assertThat(resultado.conLosSugeridos().enDuda()).isZero();
		assertThat(resultado.conLosSugeridos().positivosDescartados()).isZero();
		assertThat(resultado.conLosSugeridos().precisionAlActuar()).isEqualTo(0.938);
		assertThat(resultado.advertencia()).isNull();
		assertThat(resultado.brier()).isBetween(0.0, 0.05);
	}

	@Test
	@DisplayName("PB-41 · la curva de calibración compara, por tramo, la probabilidad que dio el modelo con la frecuencia real")
	void curvaDeCalibracion() {
		Resultado resultado = MetricasDeCalibracion.calcular(modeloQueAciertaCasiSiempre(), 0.8, 0.4, 0.9, 0.95);

		assertThat(resultado.curva()).hasSize(5);
		assertThat(resultado.curva().get(0).casos()).isEqualTo(24);
		assertThat(resultado.curva().get(0).frecuenciaReal()).isZero();
		assertThat(resultado.curva().get(1).casos()).isEqualTo(1);
		assertThat(resultado.curva().get(1).frecuenciaReal()).isEqualTo(1.0);
		assertThat(resultado.curva().get(2).casos()).isZero();
		assertThat(resultado.curva().get(2).frecuenciaReal()).isNull();
		assertThat(resultado.curva().get(4).casos()).isEqualTo(14);
		assertThat(resultado.curva().get(4).probabilidadMedia()).isEqualTo(0.9);
		assertThat(resultado.curva().get(4).frecuenciaReal()).isEqualTo(1.0);
	}

	@Test
	@DisplayName("PB-41 · un modelo que no distingue no recibe umbral para actuar, y el de preguntar nunca queda por encima del de actuar")
	void sinUmbralParaActuar() {
		List<Muestra> alAzar = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			alAzar.add(new Muestra(0.5, i % 2 == 0));
		}

		Resultado sinDistinguir = MetricasDeCalibracion.calcular(alAzar, 0.8, 0.4, 0.9, 0.95);
		Resultado cruzado = MetricasDeCalibracion.calcular(muestras(0.9, true, 0.9, true, 0.9, true, 0.9, true,
				0.9, true, 0.2, false), 0.8, 0.4, 0.9, 0.95);

		assertThat(sinDistinguir.actuarSugerido()).isNull();
		assertThat(sinDistinguir.conLosSugeridos()).isNull();
		assertThat(sinDistinguir.brier()).isEqualTo(0.25);
		assertThat(cruzado.actuarSugerido()).isEqualTo(0.9);
		assertThat(cruzado.preguntarSugerido()).isLessThanOrEqualTo(cruzado.actuarSugerido());
	}

	@Test
	@DisplayName("PB-41 · con pocos casos, o con pocos ciertos, lo medido se avisa como referencia y nada más")
	void pocosCasos() {
		Resultado pocos = MetricasDeCalibracion.calcular(muestras(0.9, true, 0.1, false), 0.8, 0.4, 0.9, 0.95);
		List<Muestra> casiTodosFalsos = new ArrayList<>(muestras(0.9, true, 0.8, true));
		for (int i = 0; i < 38; i++) {
			casiTodosFalsos.add(new Muestra(0.1, false));
		}
		Resultado pocosCiertos = MetricasDeCalibracion.calcular(casiTodosFalsos, 0.8, 0.4, 0.9, 0.95);
		Resultado vacio = MetricasDeCalibracion.calcular(List.of(), 0.8, 0.4, 0.9, 0.95);

		assertThat(pocos.advertencia()).contains("2 casos");
		assertThat(pocosCiertos.advertencia()).contains("Solo en 2 casos");
		assertThat(vacio.casos()).isZero();
		assertThat(vacio.advertencia()).isEqualTo("No hay casos medidos.");
		assertThat(vacio.actuales()).isNull();
	}

}
