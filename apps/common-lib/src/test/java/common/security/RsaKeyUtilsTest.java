package common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RsaKeyUtils — Testes Unitários de Manipulação de Chaves Criptográficas RSA")
class RsaKeyUtilsTest {

    @Test
    @DisplayName("generateKeyPair() deve gerar par de chaves RSA de 2048 bits funcional")
    void deveGerarKeyPair() {
        KeyPair keyPair = RsaKeyUtils.generateKeyPair();
        assertThat(keyPair).isNotNull();
        assertThat(keyPair.getPublic()).isInstanceOf(RSAPublicKey.class);
        assertThat(keyPair.getPrivate()).isInstanceOf(RSAPrivateKey.class);

        RSAPublicKey pub = (RSAPublicKey) keyPair.getPublic();
        assertThat(pub.getModulus().bitLength()).isEqualTo(2048);
    }

    @Test
    @DisplayName("parsePublicKey() deve decodificar chave pública válida em formato Base64 com headers PEM")
    void deveFazerParsePublicKeyComHeaders() {
        KeyPair keyPair = RsaKeyUtils.generateKeyPair();
        String base64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        String pem = "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----";

        RSAPublicKey parsed = RsaKeyUtils.parsePublicKey(pem);
        assertThat(parsed).isNotNull();
        assertThat(parsed.getModulus()).isEqualTo(((RSAPublicKey) keyPair.getPublic()).getModulus());
    }

    @Test
    @DisplayName("parsePrivateKey() deve decodificar chave privada válida em formato Base64 com headers PEM")
    void deveFazerParsePrivateKeyComHeaders() {
        KeyPair keyPair = RsaKeyUtils.generateKeyPair();
        String base64 = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
        String pem = "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----";

        RSAPrivateKey parsed = RsaKeyUtils.parsePrivateKey(pem);
        assertThat(parsed).isNotNull();
        assertThat(parsed.getModulus()).isEqualTo(((RSAPrivateKey) keyPair.getPrivate()).getModulus());
    }

    @Test
    @DisplayName("parsePublicKey() deve lançar IllegalStateException quando a chave for inválida")
    void deveLancarExcecaoParaPublicKeyInvalida() {
        assertThatThrownBy(() -> RsaKeyUtils.parsePublicKey("chave-invalida-nao-base64"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Erro ao converter chave pública RSA");
    }

    @Test
    @DisplayName("parsePrivateKey() deve lançar IllegalStateException quando a chave for inválida")
    void deveLancarExcecaoParaPrivateKeyInvalida() {
        assertThatThrownBy(() -> RsaKeyUtils.parsePrivateKey("chave-invalida-nao-base64"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Erro ao converter chave privada RSA");
    }
}
