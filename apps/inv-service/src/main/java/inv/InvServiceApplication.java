package inv;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {"inv", "common"})
public class InvServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(InvServiceApplication.class, args);
    }
}