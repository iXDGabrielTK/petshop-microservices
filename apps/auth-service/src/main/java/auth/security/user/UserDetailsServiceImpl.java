package auth.security.user;

import auth.model.Usuario;
import auth.repository.UsuarioRepository;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Implementação do UserDetailsService para carregar os detalhes do usuário durante a autenticação.
 * Carrega o usuário pelo e-mail e atribui as autoridades (roles) correspondentes.
 */
@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UsuarioRepository usuarioRepository;

    private final Logger logger = LoggerFactory.getLogger(UserDetailsServiceImpl.class);

    public UserDetailsServiceImpl(UsuarioRepository usuarioRepository) {
        this.usuarioRepository = usuarioRepository;
    }

    /**
     * Carrega o usuário pelo e-mail e atribui as autoridades (roles) correspondentes.
     *
     * @param login O e-mail do usuário a ser carregado
     * @return Os detalhes do usuário autenticado
     * @throws UsernameNotFoundException Se o usuário não for encontrado
     */
    @Override
    public @NonNull UserDetails loadUserByUsername(@NonNull String login) throws UsernameNotFoundException {
        Optional<Usuario> usuarioOpt = usuarioRepository.findByEmail(login);

        if (usuarioOpt.isEmpty()) {
            throw new UsernameNotFoundException("Usuário não encontrado com o login: " + login);
        }

        Usuario usuario = usuarioOpt.get();
        var authorities = getAuthorities(usuario);

        return new UserDetailsImpl(usuario, authorities);
    }

    /**
     * Atribui as autoridades (roles) ao usuário.
     *
     * @param usuario O usuário cujas autoridades serão atribuídas
     * @return As autoridades atribuídas ao usuário
     */
    private Collection<? extends GrantedAuthority> getAuthorities(Usuario usuario) {
        var authorities = usuario.getRoles().stream()
                .map(role -> {
                    logger.info("Atribuindo papel: {}", role.getNome());
                    return new SimpleGrantedAuthority("ROLE_" + role.getNome());
                })
                .collect(Collectors.toList());

        logger.debug("Authorities finais: {}", authorities);
        return authorities;
    }

}