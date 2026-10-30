package auth.dto;

import auth.dto.request.ForgotPasswordRequest;
import auth.dto.request.RegisterRequest;
import auth.dto.request.ResetPasswordRequest;
import auth.dto.request.UserSettingsRequest;
import auth.model.Theme;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DTO Validation — Testes de Restrições e Validação de Entrada")
class DtoValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Nested
    @DisplayName("RegisterRequest")
    class RegisterRequestValidation {

        @Test
        @DisplayName("Deve ser válido com dados corretos e senha forte")
        void deveSerValidoComDadosCorretos() {
            RegisterRequest req = new RegisterRequest();
            req.setNome("Gabriel");
            req.setEmail("gabriel@petshop.com");
            req.setSenha("Forte@1234");

            Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(req);
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("Deve falhar quando nome contiver tags ou caracteres especiais proibidos (<, >)")
        void deveFalharComXssNoNome() {
            RegisterRequest req = new RegisterRequest();
            req.setNome("<script>alert(1)</script>");
            req.setEmail("gabriel@petshop.com");
            req.setSenha("Forte@1234");

            Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(req);
            assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("nome"));
        }

        @Test
        @DisplayName("Deve falhar quando nome for curto demais (< 3 caracteres)")
        void deveFalharComNomeCurto() {
            RegisterRequest req = new RegisterRequest();
            req.setNome("Ab");
            req.setEmail("gabriel@petshop.com");
            req.setSenha("Forte@1234");

            Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(req);
            assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("nome"));
        }

        @Test
        @DisplayName("Deve falhar quando email for inválido ou contiver < ou >")
        void deveFalharComEmailInvalido() {
            RegisterRequest req = new RegisterRequest();
            req.setNome("Gabriel");
            req.setEmail("email_invalido");
            req.setSenha("Forte@1234");

            Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(req);
            assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("email"));

            req.setEmail("gabriel<test>@petshop.com");
            violations = validator.validate(req);
            assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("email"));
        }

        @ParameterizedTest(name = "Senha fraca ou inválida: {0}")
        @ValueSource(strings = {
                "fraca", // < 8 caracteres
                "sem_numero_ou_maiuscula!", // sem maiúscula ou número
                "SEMNUMEROOUESPECIAL", // sem minúscula, número e especial
                "SemEspecial123", // sem caractere especial
                "Senha Com Espaco@123" // com espaços
        })
        @DisplayName("Deve rejeitar senhas que não atendem aos critérios de complexidade")
        void deveRejeitarSenhasFracas(String senhaInvalida) {
            RegisterRequest req = new RegisterRequest();
            req.setNome("Gabriel");
            req.setEmail("gabriel@petshop.com");
            req.setSenha(senhaInvalida);

            Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(req);
            assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("senha"));
        }
    }

    @Nested
    @DisplayName("ForgotPasswordRequest")
    class ForgotPasswordRequestValidation {

        @Test
        @DisplayName("Deve ser válido quando email for válido")
        void deveSerValido() {
            ForgotPasswordRequest req = new ForgotPasswordRequest();
            req.setEmail("usuario@petshop.com");

            Set<ConstraintViolation<ForgotPasswordRequest>> violations = validator.validate(req);
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("Deve falhar quando email for vazio ou inválido")
        void deveFalharComEmailInvalido() {
            ForgotPasswordRequest req = new ForgotPasswordRequest();
            req.setEmail("");

            Set<ConstraintViolation<ForgotPasswordRequest>> violations = validator.validate(req);
            assertThat(violations).isNotEmpty();

            req.setEmail("nao-e-um-email");
            violations = validator.validate(req);
            assertThat(violations).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("ResetPasswordRequest")
    class ResetPasswordRequestValidation {

        @Test
        @DisplayName("Deve ser válido quando token e nova senha forem preenchidos")
        void deveSerValido() {
            ResetPasswordRequest req = new ResetPasswordRequest();
            req.setToken("token-123");
            req.setNewPassword("NovaSenha@123");
            req.setConfirmPassword("NovaSenha@123");

            Set<ConstraintViolation<ResetPasswordRequest>> violations = validator.validate(req);
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("Deve falhar quando token ou newPassword forem brancos")
        void deveFalharComCamposBrancos() {
            ResetPasswordRequest req = new ResetPasswordRequest();
            req.setToken("");
            req.setNewPassword("  ");

            Set<ConstraintViolation<ResetPasswordRequest>> violations = validator.validate(req);
            assertThat(violations).hasSize(2);
        }
    }

    @Nested
    @DisplayName("UserSettingsRequest")
    class UserSettingsRequestValidation {

        @Test
        @DisplayName("Deve ser válido com tema não-nulo")
        void deveSerValido() {
            UserSettingsRequest req = new UserSettingsRequest(Theme.DARK);
            Set<ConstraintViolation<UserSettingsRequest>> violations = validator.validate(req);
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("Deve falhar quando tema for nulo")
        void deveFalharQuandoTemaNulo() {
            UserSettingsRequest req = new UserSettingsRequest(null);
            Set<ConstraintViolation<UserSettingsRequest>> violations = validator.validate(req);
            assertThat(violations).isNotEmpty();
        }
    }
}
