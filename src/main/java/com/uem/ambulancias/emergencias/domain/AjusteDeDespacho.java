package com.uem.ambulancias.emergencias.domain;

import java.time.Instant;

import com.uem.ambulancias.usuarios.domain.Usuario;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Cómo despacha la central hoy. Es un solo renglón que la central cambia desde el panel; mientras nadie lo cambie,
 * rigen los valores de {@code sga.despacho.*}.
 */
@Entity
@Table(name = "ajuste_de_despacho")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AjusteDeDespacho {

	/** El único renglón de la tabla. */
	public static final Long ID = 1L;

	@Id
	private Long id;

	/** Guardado como texto, sin restricción en la base, para poder sumar modos sin tocarla. */
	@Column(nullable = false, length = 20)
	private String modo;

	/** Cuánto se espera a que la tripulación confirme una asignación antes de pasar a la unidad siguiente. */
	@Column(nullable = false)
	private int segundosConfirmacion;

	private Instant actualizadoEn;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "actualizado_por_id")
	private Usuario actualizadoPor;

	/** El ajuste tal como viene de la configuración, antes de que la central lo cambie. */
	public static AjusteDeDespacho inicial(ModoDeDespacho modo, int segundosConfirmacion) {
		AjusteDeDespacho ajuste = new AjusteDeDespacho();
		ajuste.id = ID;
		ajuste.modo = modo.name();
		ajuste.segundosConfirmacion = segundosConfirmacion;
		return ajuste;
	}

	public void cambiar(ModoDeDespacho modo, int segundosConfirmacion, Usuario administrador, Instant ahora) {
		this.modo = modo.name();
		this.segundosConfirmacion = segundosConfirmacion;
		this.actualizadoPor = administrador;
		this.actualizadoEn = ahora;
	}

	public ModoDeDespacho getModo() {
		return ModoDeDespacho.valueOf(modo);
	}

}
