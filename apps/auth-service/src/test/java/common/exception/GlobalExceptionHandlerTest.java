package common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.ServletWebRequest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GlobalExceptionHandler — Testes Unitários de Tratamento Global de Erros")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("handleGeneralException deve mapear exceção genérica para HTTP 500 com ProblemDetail")
    void deveTratarExcecaoGenerica() {
        Exception ex = new RuntimeException("Erro inesperado");
        ProblemDetail problem = handler.handleGeneralException(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(problem.getTitle()).isEqualTo("Erro Interno do Servidor");
        assertThat(problem.getDetail()).isEqualTo("Erro inesperado");
        assertThat(problem.getType().toString()).contains("internal-server-error");
        assertThat(problem.getProperties()).containsKey("timestamp");
    }

    @Test
    @DisplayName("handleNotFound deve mapear ResourceNotFoundException para HTTP 404")
    void deveTratarResourceNotFoundException() {
        ResourceNotFoundException ex = new ResourceNotFoundException("Usuário não encontrado");
        ResponseEntity<ProblemDetail> response = handler.handleNotFound(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTitle()).isEqualTo("Recurso não encontrado");
        assertThat(response.getBody().getDetail()).isEqualTo("Usuário não encontrado");
        assertThat(response.getBody().getType().toString()).contains("resource-not-found");
    }

    @Test
    @DisplayName("handleBusiness deve mapear BusinessException para HTTP 400")
    void deveTratarBusinessException() {
        BusinessException ex = new BusinessException("Email já cadastrado");
        ResponseEntity<ProblemDetail> response = handler.handleBusiness(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTitle()).isEqualTo("Regra de Negócio Violada");
        assertThat(response.getBody().getDetail()).isEqualTo("Email já cadastrado");
        assertThat(response.getBody().getType().toString()).contains("business-rule-violation");
    }

    @Test
    @DisplayName("handleOptimisticLockingFailure deve mapear conflito de concorrência para HTTP 409")
    void deveTratarOptimisticLockingFailure() {
        OptimisticLockingFailureException ex = new OptimisticLockingFailureException("Versão conflitante");
        ResponseEntity<ProblemDetail> response = handler.handleOptimisticLockingFailure(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTitle()).isEqualTo("Conflito de Concorrência");
        assertThat(response.getBody().getDetail()).contains("O registro foi alterado por outro usuário");
    }

    @Test
    @DisplayName("handleBadCredentialsException deve mapear falha de credenciais para HTTP 401")
    void deveTratarBadCredentialsException() {
        ProblemDetail problem = handler.handleBadCredentialsException();

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(problem.getTitle()).isEqualTo("Falha na Autenticação");
        assertThat(problem.getDetail()).isEqualTo("Usuário ou senha inválidos");
    }

    @Test
    @DisplayName("handleAccessDeniedException deve mapear acesso negado para HTTP 403")
    void deveTratarAccessDeniedException() {
        AccessDeniedException ex = new AccessDeniedException("Sem permissão");
        ProblemDetail problem = handler.handleAccessDeniedException(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(problem.getTitle()).isEqualTo("Acesso Negado");
        assertThat(problem.getDetail()).isEqualTo("Você não tem permissão para acessar este recurso");
    }

    @Test
    @DisplayName("handleMethodArgumentNotValid deve mapear erros de validação de campos para HTTP 400 com mapa de campos")
    void deveTratarMethodArgumentNotValid() throws Exception {
        Object target = new Object();
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(target, "requestDto");
        bindingResult.addError(new FieldError("requestDto", "email", "Email inválido"));
        bindingResult.addError(new FieldError("requestDto", "senha", "Senha fraca"));

        var method = this.getClass().getDeclaredMethod("dummyMethod", String.class);
        MethodParameter parameter = new MethodParameter(method, 0);

        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(parameter, bindingResult);

        ResponseEntity<Object> response = handler.handleMethodArgumentNotValid(
                ex,
                new HttpHeaders(),
                HttpStatus.BAD_REQUEST,
                new ServletWebRequest(new MockHttpServletRequest())
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isInstanceOf(ProblemDetail.class);

        ProblemDetail problem = (ProblemDetail) response.getBody();
        assertThat(problem.getTitle()).isEqualTo("Dados Inválidos");

        @SuppressWarnings("unchecked")
        Map<String, String> fields = (Map<String, String>) problem.getProperties().get("fields");
        assertThat(fields).containsEntry("email", "Email inválido");
        assertThat(fields).containsEntry("senha", "Senha fraca");
    }

    private void dummyMethod(String param) {}
}
