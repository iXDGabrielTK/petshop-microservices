package common.exception;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.*;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private ProblemDetail buildProblemDetail(HttpStatus status, String detail, String title, String typeSuffix) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        if (typeSuffix != null) {
            problem.setType(URI.create("https://petshop.com/errors/" + typeSuffix));
        }
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    // 1. Erro Genérico (500)
    @ExceptionHandler(Exception.class)
    ProblemDetail handleGeneralException(Exception ex) {
        log.error("Erro interno não tratado capturado pelo GlobalHandler: {}", ex.getMessage());
        return buildProblemDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ex.getMessage(),
                "Erro Interno do Servidor",
                "internal-server-error"
        );
    }

    // 2. Recurso não encontrado (404)
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(ResourceNotFoundException ex) {
        ProblemDetail problem = buildProblemDetail(
                HttpStatus.NOT_FOUND,
                ex.getMessage(),
                "Recurso não encontrado",
                "resource-not-found"
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    // 3. Regra de Negócio (400)
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ProblemDetail> handleBusiness(BusinessException ex) {
        ProblemDetail problem = buildProblemDetail(
                HttpStatus.BAD_REQUEST,
                ex.getMessage(),
                "Regra de Negócio Violada",
                "business-rule-violation"
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    // 4. Concorrência / Lock Otimista (409)
    @ExceptionHandler({OptimisticLockingFailureException.class, ObjectOptimisticLockingFailureException.class})
    public ResponseEntity<ProblemDetail> handleOptimisticLockingFailure(Exception ex) {
        log.warn("Conflito de concorrência detectado: {}", ex.getMessage());
        ProblemDetail problem = buildProblemDetail(
                HttpStatus.CONFLICT,
                "O registro foi alterado por outro usuário. Por favor, recarregue e tente novamente.",
                "Conflito de Concorrência",
                "optimistic-lock-failure"
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    // 5. Validação de Campos (400) - Bean Validation
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, @NonNull HttpHeaders headers, @NonNull HttpStatusCode status, @NonNull WebRequest request) {
        ProblemDetail problem = buildProblemDetail(
                HttpStatus.BAD_REQUEST,
                "Erro de validação nos campos",
                "Dados Inválidos",
                "invalid-data"
        );

        Map<String, String> fields = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
                fields.put(error.getField(), error.getDefaultMessage())
        );

        problem.setProperty("fields", fields);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    // 6. Autenticação (401)
    @ExceptionHandler({BadCredentialsException.class, InternalAuthenticationServiceException.class})
    ProblemDetail handleBadCredentialsException() {
        return buildProblemDetail(
                HttpStatus.UNAUTHORIZED,
                "Usuário ou senha inválidos",
                "Falha na Autenticação",
                "unauthorized"
        );
    }

    // 7. Autorização (403)
    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleAccessDeniedException(AccessDeniedException ex) {
        log.warn("Tentativa de acesso negado bloqueada: {}", ex.getMessage());

        return buildProblemDetail(
                HttpStatus.FORBIDDEN,
                "Você não tem permissão para acessar este recurso",
                "Acesso Negado",
                "forbidden"
        );
    }
}