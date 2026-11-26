package com.uem.ambulancias.coordinacion.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import com.uem.ambulancias.coordinacion.domain.ConclusionDelAgente;
import com.uem.ambulancias.coordinacion.domain.ResultadoDeCondicion;
import com.uem.ambulancias.protocolo.domain.Condicion;
import com.uem.ambulancias.protocolo.domain.Prioridad;

/**
 * Qué se hace con una condición que el protocolo no alcanzó a cerrar (PB-38 y PB-40). Acá no hay base de datos ni
 * llamadas: solo la regla.
 *
 * <ul>
 * <li>Mientras el testigo tiene tiempo de contestar, no se le pasa a una persona. El agente sí puede razonarla en ese
 * tiempo, en paralelo con la pregunta.</li>
 * <li>Pasado ese tiempo, o si no hay a quién preguntarle, la condición queda sin resolver: va al agente si tiene algo
 * con qué razonar y todavía no la miró con estos datos, y si no, a una persona.</li>
 * <li>Sobre una condición roja el agente solo puede confirmar: si concluye que no se cumple, la decide una persona.</li>
 * </ul>
 */
public final class LoQueNoSeResuelve {

	public static final String EL_TESTIGO_NO_CONTESTO = "EL_TESTIGO_NO_CONTESTO";
	public static final String EL_TESTIGO_NO_SABE = "EL_TESTIGO_NO_SABE";
	public static final String SIN_PREGUNTA_PARA_EL_TESTIGO = "SIN_PREGUNTA_PARA_EL_TESTIGO";
	public static final String EL_AGENTE_NO_LA_CONFIRMA = "EL_AGENTE_NO_LA_CONFIRMA";

	private LoQueNoSeResuelve() {
	}

	/**
	 * Una condición sin resolver.
	 *
	 * @param porQue      por qué le llegaría a una persona
	 * @param esperaHasta hasta cuándo puede contestar el testigo, o {@code null} si ya no hay a quién esperar
	 */
	public record Pendiente(String codigo, String porQue, Instant esperaHasta) {

		public boolean esperaAlTestigo() {
			return esperaHasta != null;
		}

	}

	/**
	 * Lo que se hace ahora con las condiciones sin resolver.
	 *
	 * @param aDerivar        las que van a una persona
	 * @param aRazonar        las que se le piden al agente
	 * @param proximaRevision cuándo vence el primer plazo del testigo, para volver a evaluar; {@code null} si no queda
	 *                        ninguno
	 */
	public record Reparto(List<Pendiente> aDerivar, List<Pendiente> aRazonar, Instant proximaRevision) {
	}

	/**
	 * En qué quedó una condición, o {@code null} si está resuelta o no hay nada que hacer con ella.
	 *
	 * <ul>
	 * <li>Con pregunta y sin respuesta, espera al testigo hasta que vence su plazo; después, el testigo no contestó.</li>
	 * <li>Con una respuesta que no la cerró, el testigo no sabe. Vale con o sin evidencia: una persona puede llamarlo,
	 * y el agente puede mirar lo demás del caso.</li>
	 * <li>Sin pregunta, queda sin resolver si hay una evidencia que mirar o si el modelo la dejó en duda: llegó al umbral
	 * de preguntar y no hay testigo a quien preguntarle, así que pregunta una persona. Si nadie la pudo evaluar y no hay
	 * ninguna evidencia, no hay nada que pasarle a nadie: es lo que pasa con una condición por cantidad cuando nadie
	 * declaró cuántos afectados hay, o cuando el modelo no responde.</li>
	 * </ul>
	 *
	 * @param preguntadaEn cuándo se le pidió la respuesta al testigo; sin ese dato, el plazo corre desde ahora
	 */
	public static Pendiente pendiente(Condicion condicion, Veredicto veredicto, boolean hayEvidenciaUtil,
			Instant preguntadaEn, Instant ahora, Duration esperaDelTestigo) {
		ResultadoDeCondicion resultado = veredicto.resultado();
		if (resultado == ResultadoDeCondicion.CUMPLIDA || resultado == ResultadoDeCondicion.NO_CUMPLIDA) {
			return null;
		}
		String codigo = condicion.getCodigo();
		if (condicion.getPregunta() != null && veredicto.respuestaDelTestigo() == null) {
			Instant limite = (preguntadaEn == null ? ahora : preguntadaEn).plus(esperaDelTestigo);
			return new Pendiente(codigo, EL_TESTIGO_NO_CONTESTO, ahora.isBefore(limite) ? limite : null);
		}
		if (veredicto.respuestaDelTestigo() != null) {
			return new Pendiente(codigo, EL_TESTIGO_NO_SABE, null);
		}
		if (hayEvidenciaUtil || resultado == ResultadoDeCondicion.EN_DUDA) {
			return new Pendiente(codigo, SIN_PREGUNTA_PARA_EL_TESTIGO, null);
		}
		return null;
	}

	/**
	 * Reparte las condiciones sin resolver. Lo que todavía espera al testigo nunca va a una persona. Con el agente a
	 * mano, se le pide todo lo que no miró con estos datos, espere o no al testigo. Lo que ya miró sin resolverlo no se
	 * le vuelve a pedir: espera al testigo o va a una persona.
	 *
	 * @param yaMiradas    las que el agente ya miró sin resolverlas, sin que desde entonces llegara un dato nuevo
	 * @param puedeRazonar si hay un agente encendido y algo con qué razonar
	 */
	public static Reparto repartir(List<Pendiente> pendientes, Set<String> yaMiradas, boolean puedeRazonar) {
		List<Pendiente> aDerivar = new ArrayList<>();
		List<Pendiente> aRazonar = new ArrayList<>();
		Instant proximaRevision = null;
		for (Pendiente pendiente : pendientes) {
			if (pendiente.esperaAlTestigo() && (proximaRevision == null
					|| pendiente.esperaHasta().isBefore(proximaRevision))) {
				proximaRevision = pendiente.esperaHasta();
			}
			if (puedeRazonar && !yaMiradas.contains(pendiente.codigo())) {
				aRazonar.add(pendiente);
			} else if (!pendiente.esperaAlTestigo()) {
				aDerivar.add(pendiente);
			}
		}
		return new Reparto(List.copyOf(aDerivar), List.copyOf(aRazonar), proximaRevision);
	}

	/**
	 * Si el agente tiene algo con qué razonar además de lo que ya miró el modelo: una evidencia analizada que sirve,
	 * más de una persona que alertó, o casos anteriores del mismo tipo de suceso. Sin nada de eso, pedirle un
	 * razonamiento solo demora a la persona.
	 *
	 * @param hayCasosDelTipo se consulta solo si lo demás no alcanza
	 */
	public static boolean hayAlgoQueRazonar(boolean hayEvidenciaUtil, int personasQueAlertaron,
			BooleanSupplier hayCasosDelTipo) {
		return hayEvidenciaUtil || personasQueAlertaron > 1 || hayCasosDelTipo.getAsBoolean();
	}

	/**
	 * Si lo que concluyó el agente se aplica. Que se cumple, siempre. Que no se cumple, salvo en una condición roja:
	 * descartar la más grave lo decide una persona. Que no se sabe, nunca.
	 */
	public static boolean seAplica(ConclusionDelAgente conclusion, Prioridad prioridad) {
		return conclusion == ConclusionDelAgente.CUMPLE
				|| conclusion == ConclusionDelAgente.NO_CUMPLE && prioridad != Prioridad.ROJA;
	}

	/** Por qué le llega a una persona una condición en la que no se aplicó lo que concluyó el agente. */
	public static String porQueNoSeAplica(ConclusionDelAgente conclusion, String porQueDeAntes) {
		return conclusion == ConclusionDelAgente.NO_CUMPLE ? EL_AGENTE_NO_LA_CONFIRMA : porQueDeAntes;
	}

	/** Si el testigo todavía tiene tiempo de contestar. */
	public static boolean esperaAlTestigo(Instant esperaHasta, Instant ahora) {
		return esperaHasta != null && ahora.isBefore(esperaHasta);
	}

}
