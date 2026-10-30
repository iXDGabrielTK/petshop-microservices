package auth.service;

import auth.config.RabbitMQConfig;
import auth.dto.message.PasswordResetMessage;
import auth.dto.request.ForgotPasswordRequest;
import auth.dto.request.RegisterRequest;
import auth.dto.request.ResetPasswordRequest;
import auth.model.PasswordResetToken;
import auth.model.Role;
import auth.model.Usuario;
import auth.repository.PasswordResetTokenRepository;
import auth.repository.RoleRepository;
import auth.repository.UsuarioRepository;
import common.exception.BusinessException;
import common.exception.ResourceNotFoundException;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;
import java.util.logging.Logger;

@Service
@Slf4j
public class AuthService {

    Logger logger = Logger.getLogger(AuthService.class.getName());

    private final UsuarioRepository usuarioRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final RabbitTemplate rabbitTemplate;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UsuarioRepository usuarioRepository,
                       PasswordResetTokenRepository tokenRepository,
                       RabbitTemplate rabbitTemplate,
                       RoleRepository roleRepository,
                       PasswordEncoder passwordEncoder) {
        this.usuarioRepository = usuarioRepository;
        this.tokenRepository = tokenRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Registra um novo usuário na aplicação.
     *<p>
     * - Verifica se o email já existe e lança BusinessException se estiver em uso.
     * - Cria o Usuario com senha codificada e atribui a role padrão "USER".
     * - Persiste o usuário no repositório e retorna a entidade criada.
     *
     * @param request dados para cadastro (nome, email, senha)
     * @return Usuario recém-criado
     */
    public Usuario register(RegisterRequest request) {
        Objects.requireNonNull(request, "request não pode ser nulo");
        Objects.requireNonNull(request.getEmail(), "email não pode ser nulo");

        if (usuarioRepository.findByEmail(request.getEmail()).isPresent()) {
            throw new BusinessException("Email já cadastrado");
        }

        Usuario novoUsuario = new Usuario(
                request.getNome(),
                request.getEmail(),
                passwordEncoder.encode(request.getSenha())
        );

        Role roleUser = roleRepository.findByNome("USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "USER")));

        novoUsuario.adicionarRole(roleUser);

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
        Objects.requireNonNull(request, "request não pode ser nulo");
        Objects.requireNonNull(request.getEmail(), "email não pode ser nulo");

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
                1,
                UUID.randomUUID().toString(),
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
        Objects.requireNonNull(request, "request não pode ser nulo");
        Objects.requireNonNull(request.getToken(), "token não pode ser nulo");

        PasswordResetToken resetToken = tokenRepository.findByToken(request.getToken())
                .orElseThrow(() -> new ResourceNotFoundException("Token inválido"));

        if (!Objects.equals(request.getNewPassword(), request.getConfirmPassword())) {
            throw new BusinessException("As senhas não conferem.");
        }

        if (resetToken.isExpired()) {
            tokenRepository.delete(resetToken);
            throw new BusinessException("Token expirado");
        }

        Usuario usuario = resetToken.getUsuario();

        usuario.alterarSenha(passwordEncoder.encode(request.getNewPassword()));
        usuarioRepository.save(usuario);

        tokenRepository.delete(resetToken);
    }
}