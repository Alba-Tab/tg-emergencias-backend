package com.uem.ambulancias.emergencias.service;

import java.time.Instant;

import com.uem.ambulancias.comun.error.NoEncontradoException;
import com.uem.ambulancias.emergencias.domain.AjusteDeDespacho;
import com.uem.ambulancias.emergencias.domain.ModoDeDespacho;
import com.uem.ambulancias.emergencias.repository.AjusteDeDespachoRepository;
import com.uem.ambulancias.usuarios.domain.RolUsuario;
import com.uem.ambulancias.usuarios.domain.Usuario;
import com.uem.ambulancias.usuarios.repository.UsuarioRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * El interruptor del despacho. Se lee en cada incidente, así que un cambio rige desde el incidente siguiente sin
 * reiniciar nada.
 */
@Service
@RequiredArgsConstructor
public class AjustesDeDespacho {

	private final AjusteDeDespachoRepository ajustes;
	private final UsuarioRepository usuarios;
	private final DespachoProperties porDefecto;

	/** Lo que rige ahora: lo que eligió la central o, si nunca eligió, lo configurado. */
	@Transactional(readOnly = true)
	public AjusteDeDespacho vigente() {
		return ajustes.findById(AjusteDeDespacho.ID)
				.map(AjustesDeDespacho::conQuienLoCambio)
				.orElseGet(() -> AjusteDeDespacho.inicial(porDefecto.modo(), porDefecto.segundosConfirmacion()));
	}

	/** Deja cargado quién lo cambió, para poder armar la respuesta fuera de la transacción. */
	private static AjusteDeDespacho conQuienLoCambio(AjusteDeDespacho ajuste) {
		if (ajuste.getActualizadoPor() != null) {
			ajuste.getActualizadoPor().getNombreCompleto();
		}
		return ajuste;
	}

	@Transactional(readOnly = true)
	public ModoDeDespacho modo() {
		return vigente().getModo();
	}

	/** La central cambia cómo se despacha. Queda anotado quién lo cambió y cuándo. */
	@Transactional
	public AjusteDeDespacho cambiar(Long administradorId, ModoDeDespacho modo, int segundosConfirmacion) {
		Usuario administrador = usuarios.findByIdAndRol(administradorId, RolUsuario.ADMIN)
				.orElseThrow(() -> new NoEncontradoException("No existe el administrador " + administradorId + "."));
		AjusteDeDespacho ajuste = ajustes.findById(AjusteDeDespacho.ID)
				.orElseGet(() -> AjusteDeDespacho.inicial(porDefecto.modo(), porDefecto.segundosConfirmacion()));
		ajuste.cambiar(modo, segundosConfirmacion, administrador, Instant.now());
		return ajustes.save(ajuste);
	}

}
