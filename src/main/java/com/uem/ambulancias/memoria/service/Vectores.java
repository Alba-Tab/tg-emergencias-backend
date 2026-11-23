package com.uem.ambulancias.memoria.service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Lo mínimo para guardar y comparar embeddings. Un vector se guarda como bytes, cuatro por número, y se compara por
 * el coseno del ángulo entre los dos: 1 es el mismo sentido, 0 es que no tienen nada que ver.
 *
 * <p>La comparación es exacta, contra todos los guardados. A la escala de una central (cientos de casos y de
 * fragmentos de norma) tarda milisegundos; un índice aproximado recién haría falta con decenas de miles.
 */
public final class Vectores {

	private Vectores() {
	}

	public static byte[] aBytes(float[] vector) {
		ByteBuffer bytes = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
		for (float valor : vector) {
			bytes.putFloat(valor);
		}
		return bytes.array();
	}

	public static float[] deBytes(byte[] bytes) {
		ByteBuffer lector = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
		float[] vector = new float[bytes.length / Float.BYTES];
		for (int i = 0; i < vector.length; i++) {
			vector[i] = lector.getFloat();
		}
		return vector;
	}

	/**
	 * El coseno entre dos vectores. Si miden distinto o alguno es todo ceros no se pueden comparar: devuelve 0, que
	 * es «no se parecen».
	 */
	public static double coseno(float[] uno, float[] otro) {
		if (uno.length != otro.length || uno.length == 0) {
			return 0;
		}
		double producto = 0;
		double normaDeUno = 0;
		double normaDeOtro = 0;
		for (int i = 0; i < uno.length; i++) {
			producto += (double) uno[i] * otro[i];
			normaDeUno += (double) uno[i] * uno[i];
			normaDeOtro += (double) otro[i] * otro[i];
		}
		if (normaDeUno == 0 || normaDeOtro == 0) {
			return 0;
		}
		return producto / (Math.sqrt(normaDeUno) * Math.sqrt(normaDeOtro));
	}

}
