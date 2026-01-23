package auth.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class LoginController {

    @GetMapping("/")
    @ResponseBody
    public String home() {
        return "🐶 Auth Service do PetShop está rodando! (Você está logado)";
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }
}