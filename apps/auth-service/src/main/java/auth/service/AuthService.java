package auth.service;

import auth.dto.request.LoginRequest;
import auth.dto.request.LogoutRequest;
import auth.dto.request.RefreshTokenRequest;
import auth.dto.response.LoginResponse;
import auth.model.Usuario;
import auth.repository.UsuarioRepository;
import auth.security.jwt.JwtTokenService;
import auth.security.jwt.JwtTokenValidator;
import auth.security.jwt.TokenBlacklistService;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class AuthService {

    private final UsuarioRepository usuarioRepository;
    private final AuthenticationManager authenticationManager;
    private final JwtTokenService tokenService;
    private final JwtTokenValidator tokenValidator;
    private final TokenBlacklistService blacklistService;
    private final UserDetailsService userDetailsService;
    private final auth.repository.RoleRepository roleRepository;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    public AuthService(UsuarioRepository usuarioRepository,
                       AuthenticationManager authenticationManager,
                       JwtTokenService tokenService,
                       JwtTokenValidator tokenValidator,
                       TokenBlacklistService blacklistService,
                       UserDetailsService userDetailsService,
                       auth.repository.RoleRepository roleRepository,
                       org.springframework.security.crypto.password.PasswordEncoder passwordEncoder) {
        this.usuarioRepository = usuarioRepository;
        this.authenticationManager = authenticationManager;
        this.tokenService = tokenService;
        this.tokenValidator = tokenValidator;
        this.blacklistService = blacklistService;
        this.userDetailsService = userDetailsService;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Realiza autenticação do usuário.
     *<p>
     * - Recebe um LoginRequest com email e senha.
     * - Autentica via AuthenticationManager e obtém UserDetails.
     * - Busca o Usuario no repositório e gera access e refresh tokens.
     * - Retorna um LoginResponse com tokens e dados do usuário.
     *
     * @param request dados de login (email, senha)
     * @return LoginResponse contendo accessToken, refreshToken e dados do usuário
     */
    public LoginResponse login(LoginRequest request) {
        Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getEmail(), request.getSenha())
        );

        UserDetails userDetails = null;
        Object principal = auth.getPrincipal();
        if (principal instanceof UserDetails) {
            userDetails = (UserDetails) principal;
        }

        if (userDetails == null) {
            userDetails = userDetailsService.loadUserByUsername(request.getEmail());
        }

        Usuario usuario = usuarioRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new RuntimeException("Usuário não encontrado"));

        String accessToken = tokenService.generateAccessToken(userDetails);
        String refreshToken = tokenService.generateRefreshToken(userDetails);

        return new LoginResponse(accessToken, refreshToken, usuario.getId().toString(), usuario.getNome(), usuario.getEmail());
    }

    /**
     * Valida e processa um refresh token para emitir novos tokens.
     *<p>
     * - Recebe um RefreshTokenRequest contendo o refresh token.
     * - Verifica se é realmente um refresh token e se é válido/nao expirado.
     * - Coloca o refresh token atual na blacklist e gera novos access/refresh tokens.
     * - Retorna um mapa com os novos tokens.
     *
     * @param request dados contendo o refresh token
     * @return mapa com "access_token" e "refresh_token" novos
     */
    public Map<String, String> refreshToken(RefreshTokenRequest request) {
        String refreshToken = request.getRefreshToken();

        if (refreshToken == null || !tokenValidator.isRefreshToken(refreshToken)) {
            throw new IllegalArgumentException("Token inválido para refresh");
        }

        tokenValidator.validateTokenOrThrow(refreshToken);
        long expiration = tokenValidator.extractExpiration(refreshToken);
        blacklistService.blacklistToken(refreshToken, expiration);

        String username = tokenValidator.extractUsername(refreshToken);
        UserDetails userDetails = userDetailsService.loadUserByUsername(username);

        String newAccessToken = tokenService.generateAccessToken(userDetails);
        String newRefreshToken = tokenService.generateRefreshToken(userDetails);

        Map<String, String> tokens = new HashMap<>();
        tokens.put("access_token", newAccessToken);
        tokens.put("refresh_token", newRefreshToken);
        return tokens;
    }

    /**
     * Revoga tokens e efetua logout.
     *<p>
     * - Aceita header Authorization com Access Token e/ou um LogoutRequest com Refresh Token.
     * - Valida tokens e os adiciona à blacklist para impedir uso futuro.
     * - Retorna um mapa indicando quais tokens foram revogados.
     *
     * @param accessTokenHeader header Authorization (pode ser nulo)
     * @param logoutRequest corpo opcional contendo refreshToken
     * @return mapa com mensagem e flags de revogação de access/refresh token
     */
    public Map<String, Object> logout(String accessTokenHeader, LogoutRequest logoutRequest) {
        boolean accessTokenRevoked = false;
        boolean refreshTokenRevoked = false;

        if (accessTokenHeader != null && accessTokenHeader.startsWith("Bearer ")) {
            String token = accessTokenHeader.substring(7);
            try {
                tokenValidator.validateTokenOrThrow(token);
                long expiration = tokenValidator.extractExpiration(token);
                blacklistService.blacklistToken(token, expiration);
                accessTokenRevoked = true;
            } catch (Exception ignored) {}
        }

        if (logoutRequest != null && logoutRequest.getRefreshToken() != null) {
            String token = logoutRequest.getRefreshToken();
            try {
                tokenValidator.validateTokenOrThrow(token);
                long expiration = tokenValidator.extractExpiration(token);
                blacklistService.blacklistToken(token, expiration);
                refreshTokenRevoked = true;
            } catch (Exception ignored) {}
        }

        Map<String, Object> response = new HashMap<>();
        response.put("message", "Logout realizado");
        response.put("accessTokenRevoked", accessTokenRevoked);
        response.put("refreshTokenRevoked", refreshTokenRevoked);
        return response;
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
        throw new IllegalArgumentException("Email já cadastrado");
    }

    Usuario novoUsuario = new Usuario();
    novoUsuario.setNome(request.getNome());
    novoUsuario.setEmail(request.getEmail());
    novoUsuario.setSenha(passwordEncoder.encode(request.getSenha()));

    auth.model.Role roleUser = roleRepository.findByNome("USER")
            .orElseGet(() -> roleRepository.save(new auth.model.Role(null, "USER")));

    novoUsuario.setRoles(java.util.Collections.singleton(roleUser));

    return usuarioRepository.save(novoUsuario);
    }
}