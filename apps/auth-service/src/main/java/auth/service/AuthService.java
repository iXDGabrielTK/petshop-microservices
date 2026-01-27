package auth.service;

import auth.dto.request.*;
import auth.dto.message.PasswordResetMessage;
import auth.config.RabbitMQConfig;
import auth.model.Usuario;
import auth.model.PasswordResetToken;
import auth.repository.UsuarioRepository;
import common.exception.BusinessException;
import common.exception.ResourceNotFoundException;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

@Service
@Slf4j
public class AuthService {

    Logger logger = Logger.getLogger(AuthService.class.getName());

    private final UsuarioRepository usuarioRepository;
    private final auth.repository.PasswordResetTokenRepository tokenRepository;
    private final RabbitTemplate rabbitTemplate;
    private final auth.repository.RoleRepository roleRepository;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    public AuthService(UsuarioRepository usuarioRepository, auth.repository.PasswordResetTokenRepository tokenRepository,
                       RabbitTemplate rabbitTemplate,
                       auth.repository.RoleRepository roleRepository,
                       org.springframework.security.crypto.password.PasswordEncoder passwordEncoder) {
        this.usuarioRepository = usuarioRepository;
        this.tokenRepository = tokenRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Registra um novo usuário na aplicação.
     *<p>
     * - Verifica se o email já existe e lança IllegalArgumentException se estiver em uso.
     * - Cria o Usuario com senha codificada e atribui a role padrão "USER".
     * - Persiste o usuário no repositório e retorna a entidade criada.
     *
     * @param request dados para cadastro (nome, email, senha)
     * @return Usuario recém-criado
     */
    public Usuario register(auth.dto.request.RegisterRequest request) {
    if (usuarioRepository.findByEmail(request.getEmail()).isPresent()) {
        throw new BusinessException("Email já cadastrado");
    }

    Usuario novoUsuario = new Usuario();
    novoUsuario.setNome(request.getNome());
    novoUsuario.setEmail(request.getEmail());
    novoUsuario.setSenha(passwordEncoder.encode(request.getSenha()));

    auth.model.Role roleUser = roleRepository.findByNome("USER")
            .orElseGet(() -> roleRepository.save(new auth.model.Role(null, "USER")));

        novoUsuario.setRoles(new HashSet<>(Set.of(roleUser)));

    return usuarioRepository.save(novoUsuario);
    }

    /**
     * Inicia o processo de recuperação de senha.
     *<p>
     * - Recebe um ForgotPasswordRequest contendo o email do usuário.
     * - Gera um token único e persiste como PasswordResetToken associado ao usuário.
     * - Envia uma mensagem para RabbitMQ com os detalhes para envio de email.
     *
     * @param request dados contendo o email do usuário
     */
    @Transactional
    public void forgotPassword(ForgotPasswordRequest request) {
        Usuario usuario = usuarioRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new ResourceNotFoundException("Email não encontrado"));

        String tokenGerado = UUID.randomUUID().toString();

        PasswordResetToken token = tokenRepository.findByUsuario(usuario)
                .map(existingToken -> {
                    existingToken.atualizarToken(tokenGerado);
                    return existingToken;
                })
                .orElseGet(() -> new PasswordResetToken(tokenGerado, usuario));

        tokenRepository.save(token);

        PasswordResetMessage message = new PasswordResetMessage(
                usuario.getEmail(),
                tokenGerado,
                usuario.getNome()
        );

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.EXCHANGER_NAME,
                RabbitMQConfig.ROUTING_KEY,
                message
        );
        logger.info("🐇 Mensagem enviada para RabbitMQ: " + usuario.getEmail());
    }

    /**
     * Reseta a senha do usuário usando um token válido.
     *<p>
     * - Recebe um ResetPasswordRequest com token e nova senha.
     * - Valida o token, verifica expiração e obtém o usuário associado.
     * - Atualiza a senha do usuário com a nova senha codificada.
     * - Remove o token usado do repositório.
     *
     * @param request dados contendo token e nova senha
     */
    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        PasswordResetToken resetToken = tokenRepository.findByToken(request.getToken())
                .orElseThrow(() -> new ResourceNotFoundException("Token inválido"));

        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new BusinessException("As senhas não conferem.");
        }

        if (resetToken.isExpired()) {
            tokenRepository.delete(resetToken);
            throw new BusinessException("Token expirado");
        }

        Usuario usuario = resetToken.getUsuario();

        usuario.setSenha(passwordEncoder.encode(request.getNewPassword()));
        usuarioRepository.save(usuario);

        tokenRepository.delete(resetToken);
    }
}