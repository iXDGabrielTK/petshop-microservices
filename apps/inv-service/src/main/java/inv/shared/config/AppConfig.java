package inv.shared.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@Configuration
@EnableRetry(proxyTargetClass = true)
@EnableTransactionManagement(proxyTargetClass = true)
public class AppConfig {}