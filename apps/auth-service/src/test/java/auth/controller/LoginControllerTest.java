package auth.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LoginController — Testes Unitários de Rotas de Visualização e Home")
class LoginControllerTest {

    private final LoginController loginController = new LoginController();

    @Test
    @DisplayName("home() deve retornar mensagem de boas-vindas do serviço")
    void homeDeveRetornarMensagem() {
        String result = loginController.home();
        assertThat(result).contains("Auth Service do PetShop está rodando!");
    }

    @Test
    @DisplayName("login() deve retornar o nome do template da página de login")
    void loginDeveRetornarTemplateLogin() {
        String viewName = loginController.login();
        assertThat(viewName).isEqualTo("login");
    }
}
