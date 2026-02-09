package auth.service;

import auth.dto.response.UserResponse;
import auth.model.Role;
import auth.model.Usuario;
import auth.repository.UsuarioRepository;
import common.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.stream.Collectors;

@Service
public class UsuarioService {

    private final UsuarioRepository usuarioRepository;

    public UsuarioService(UsuarioRepository usuarioRepository) {
        this.usuarioRepository = usuarioRepository;
    }

    @Transactional(readOnly = true)
    public UserResponse obterDadosUsuarioLogado(String email) {
        Usuario usuario = usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado"));

        var roles = usuario.getRoles().stream()
                .map(Role::getNome)
                .collect(Collectors.toSet());

        return new UserResponse(
                usuario.getId(),
                usuario.getNome(),
                usuario.getEmail(),
                roles
        );
    }
}