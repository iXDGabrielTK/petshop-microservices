package inv.checkout.usecase;

import common.exception.BusinessException;
import inv.checkout.domain.model.Pagamento;
import inv.checkout.domain.model.StatusPagamento;
import inv.checkout.domain.model.Venda;
import inv.checkout.infrastructure.messaging.event.PagamentoConfirmadoEvent;
import inv.checkout.infrastructure.persistence.PagamentoRepository;
import inv.checkout.infrastructure.persistence.VendaRepository;
import inv.shared.domain.MetodoPagamento;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PagamentoService — Testes Unitários e Caracterização de Pagamento")
class PagamentoServiceTest {

    @Mock
    private PagamentoRepository pagamentoRepository;

    @Mock
    private VendaRepository vendaRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private PagamentoService pagamentoService;

    @Nested
    @DisplayName("confirmarPagamentoWebhook(Long, BigDecimal, String)")
    class ConfirmarPagamentoWebhookTests {

        @Test
        @DisplayName("Cenário 1 / F3 — Webhook duplicado em pagamento já CONFIRMADO deve salvar e publicar evento redundante (estado pré-E1)")
        void webhookDuplicadoEmPagamentoJaConfirmadoDeveSalvarEPublicarNovamente() {
            // ARRANGE
            Long pagamentoId = 50L;
            Long vendaId = 10L;
            BigDecimal valor = new BigDecimal("100.00");
            String nsu = "NSU-123456";

            Pagamento pagamento = criarPagamento(pagamentoId, vendaId, MetodoPagamento.CARTAO_CREDITO, StatusPagamento.PROCESSANDO);
            when(pagamentoRepository.findById(pagamentoId)).thenReturn(Optional.of(pagamento));

            // ACT 1 — Primeira entrega de webhook: processamento normal
            pagamentoService.confirmarPagamentoWebhook(pagamentoId, valor, nsu);

            assertThat(pagamento.getStatus()).isEqualTo(StatusPagamento.CONFIRMADO);
            assertThat(pagamento.getValorRecebido()).isEqualByComparingTo(valor);
            assertThat(pagamento.getNsuTransacao()).isEqualTo(nsu);
            LocalDateTime primeiraDataHora = pagamento.getDataHoraPagamento();
            assertThat(primeiraDataHora).isNotNull();
            verify(pagamentoRepository, times(1)).save(pagamento);
            verify(eventPublisher, times(1)).publishEvent(any(PagamentoConfirmadoEvent.class));

            // ACT 2 — Segunda entrega de webhook (retry/duplicação do gateway)
            pagamentoService.confirmarPagamentoWebhook(pagamentoId, valor, nsu);

            // ASSERT — Caracterização de F3: pagamento.confirmar() é no-op, mas o service executa save e publishEvent incondicionalmente
            assertThat(pagamento.getStatus()).isEqualTo(StatusPagamento.CONFIRMADO);
            assertThat(pagamento.getValorRecebido()).isEqualByComparingTo(valor);
            assertThat(pagamento.getNsuTransacao()).isEqualTo(nsu);
            assertThat(pagamento.getDataHoraPagamento()).isEqualTo(primeiraDataHora);

            // No estado pré-E1, save e publishEvent foram invocados 2 vezes no total
            verify(pagamentoRepository, times(2)).save(pagamento);
            verify(eventPublisher, times(2)).publishEvent(any(PagamentoConfirmadoEvent.class));
            verify(eventPublisher, times(2)).publishEvent(eq(new PagamentoConfirmadoEvent(vendaId, pagamentoId)));
        }

        @Test
        @DisplayName("Cenário 1 / F3 — Webhook duplicado em pagamento PIX já CONFIRMADO deve salvar e publicar evento redundante (estado pré-E1)")
        void webhookDuplicadoEmPagamentoPixJaConfirmadoDeveSalvarEPublicarNovamente() {
            Long pagamentoId = 60L;
            Long vendaId = 20L;
            BigDecimal valor = new BigDecimal("45.90");
            String nsu = "PIX-E2E-123";

            Pagamento pagamento = criarPagamento(pagamentoId, vendaId, MetodoPagamento.PIX, StatusPagamento.PENDENTE);
            when(pagamentoRepository.findById(pagamentoId)).thenReturn(Optional.of(pagamento));

            // ACT 1 — Primeira entrega de webhook: confirmação normal
            pagamentoService.confirmarPagamentoWebhook(pagamentoId, valor, nsu);

            assertThat(pagamento.getStatus()).isEqualTo(StatusPagamento.CONFIRMADO);
            assertThat(pagamento.getValorRecebido()).isEqualByComparingTo(valor);
            LocalDateTime primeiraDataHora = pagamento.getDataHoraPagamento();
            assertThat(primeiraDataHora).isNotNull();
            verify(pagamentoRepository, times(1)).save(pagamento);
            verify(eventPublisher, times(1)).publishEvent(any(PagamentoConfirmadoEvent.class));

            // ACT 2 — Segunda entrega redundante de webhook
            pagamentoService.confirmarPagamentoWebhook(pagamentoId, valor, nsu);

            // ASSERT — Caracterização de F3 para método sem processamento prévio
            assertThat(pagamento.getStatus()).isEqualTo(StatusPagamento.CONFIRMADO);
            assertThat(pagamento.getDataHoraPagamento()).isEqualTo(primeiraDataHora);
            verify(pagamentoRepository, times(2)).save(pagamento);
            verify(eventPublisher, times(2)).publishEvent(any(PagamentoConfirmadoEvent.class));
            verify(eventPublisher, times(2)).publishEvent(eq(new PagamentoConfirmadoEvent(vendaId, pagamentoId)));
        }

        @Test
        @DisplayName("F3 — Webhook em pagamento previamente CONFIRMADO persiste e publica mesmo sem alterar campos da entidade")
        void webhookEmPagamentoPreviamenteConfirmadoDeveSalvarEPublicarMesmoSemAlterarEntidade() {
            // ARRANGE
            Long pagamentoId = 51L;
            Long vendaId = 11L;
            BigDecimal valorInicial = new BigDecimal("80.00");
            String nsuInicial = "NSU-EXISTENTE";

            Pagamento pagamento = criarPagamento(pagamentoId, vendaId, MetodoPagamento.CARTAO_CREDITO, StatusPagamento.CONFIRMADO);
            ReflectionTestUtils.setField(pagamento, "valorRecebido", valorInicial);
            ReflectionTestUtils.setField(pagamento, "nsuTransacao", nsuInicial);
            when(pagamentoRepository.findById(pagamentoId)).thenReturn(Optional.of(pagamento));

            // ACT
            pagamentoService.confirmarPagamentoWebhook(pagamentoId, new BigDecimal("999.00"), "NSU-NOVO-IGNORADO");

            // ASSERT — Entidade permaneceu com os valores da primeira confirmação
            assertThat(pagamento.getStatus()).isEqualTo(StatusPagamento.CONFIRMADO);
            assertThat(pagamento.getValorRecebido()).isEqualByComparingTo(valorInicial);
            assertThat(pagamento.getNsuTransacao()).isEqualTo(nsuInicial);

            // Mas save e publishEvent foram invocados
            verify(pagamentoRepository, times(1)).save(pagamento);
            verify(eventPublisher, times(1)).publishEvent(new PagamentoConfirmadoEvent(vendaId, pagamentoId));
        }

        @Test
        @DisplayName("Cenário 2 — Deve confirmar com sucesso quando método exige processamento (CARTAO_CREDITO) e status for PROCESSANDO")
        void deveConfirmarComSucessoQuandoMetodoExigeProcessamentoEStatusProcessando() {
            Long pagamentoId = 50L;
            Long vendaId = 10L;
            BigDecimal valor = new BigDecimal("150.00");
            String nsu = "NSU-CREDITO-01";

            Pagamento pagamento = criarPagamento(pagamentoId, vendaId, MetodoPagamento.CARTAO_CREDITO, StatusPagamento.PROCESSANDO);
            when(pagamentoRepository.findById(pagamentoId)).thenReturn(Optional.of(pagamento));

            pagamentoService.confirmarPagamentoWebhook(pagamentoId, valor, nsu);

            assertThat(pagamento.getStatus()).isEqualTo(StatusPagamento.CONFIRMADO);
            assertThat(pagamento.getValorRecebido()).isEqualByComparingTo(valor);
            assertThat(pagamento.getNsuTransacao()).isEqualTo(nsu);
            assertThat(pagamento.getDataHoraPagamento()).isNotNull();

            verify(pagamentoRepository, times(1)).save(pagamento);
            verify(eventPublisher, times(1)).publishEvent(new PagamentoConfirmadoEvent(vendaId, pagamentoId));
        }

        @Test
        @DisplayName("Cenário 2 — Deve confirmar com sucesso quando método exige processamento (CARTAO_DEBITO) e status for PROCESSANDO")
        void deveConfirmarComSucessoQuandoCartaoDebitoEStatusProcessando() {
            Long pagamentoId = 52L;
            Long vendaId = 12L;
            BigDecimal valor = new BigDecimal("60.00");
            String nsu = "NSU-DEBITO-01";

            Pagamento pagamento = criarPagamento(pagamentoId, vendaId, MetodoPagamento.CARTAO_DEBITO, StatusPagamento.PROCESSANDO);
            when(pagamentoRepository.findById(pagamentoId)).thenReturn(Optional.of(pagamento));

            pagamentoService.confirmarPagamentoWebhook(pagamentoId, valor, nsu);

            assertThat(pagamento.getStatus()).isEqualTo(StatusPagamento.CONFIRMADO);
            assertThat(pagamento.getValorRecebido()).isEqualByComparingTo(valor);
            assertThat(pagamento.getNsuTransacao()).isEqualTo(nsu);

            verify(pagamentoRepository, times(1)).save(pagamento);
            verify(eventPublisher, times(1)).publishEvent(new PagamentoConfirmadoEvent(vendaId, pagamentoId));
        }

        @Test
        @DisplayName("Cenário 2 — Deve lançar BusinessException ao tentar confirmar método que exige processamento em status PENDENTE")
        void deveLancarBusinessExceptionQuandoMetodoExigeProcessamentoEStatusPendente() {
            Long pagamentoId = 50L;
            Pagamento pagamento = criarPagamento(pagamentoId, 10L, MetodoPagamento.CARTAO_CREDITO, StatusPagamento.PENDENTE);
            when(pagamentoRepository.findById(pagamentoId)).thenReturn(Optional.of(pagamento));

            assertThatThrownBy(() -> pagamentoService.confirmarPagamentoWebhook(pagamentoId, new BigDecimal("100.00"), "NSU-01"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Transição inválida: Pagamento exige processamento prévio.");

            verify(pagamentoRepository, never()).save(any());
            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("Cenário 2 — Deve lançar BusinessException ao tentar confirmar método que exige processamento em status FALHOU")
        void deveLancarBusinessExceptionQuandoMetodoExigeProcessamentoEStatusFalhou() {
            Long pagamentoId = 50L;
            Pagamento pagamento = criarPagamento(pagamentoId, 10L, MetodoPagamento.CARTAO_CREDITO, StatusPagamento.FALHOU);
            when(pagamentoRepository.findById(pagamentoId)).thenReturn(Optional.of(pagamento));

            assertThatThrownBy(() -> pagamentoService.confirmarPagamentoWebhook(pagamentoId, new BigDecimal("100.00"), "NSU-01"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Transição inválida: Pagamento exige processamento prévio.");

            verify(pagamentoRepository, never()).save(any());
            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("Cenário 2 — Deve lançar BusinessException ao tentar confirmar método que exige processamento em status ESTORNADO")
        void deveLancarBusinessExceptionQuandoMetodoExigeProcessamentoEStatusEstornado() {
            Long pagamentoId = 50L;
            Pagamento pagamento = criarPagamento(pagamentoId, 10L, MetodoPagamento.CARTAO_CREDITO, StatusPagamento.ESTORNADO);
            when(pagamentoRepository.findById(pagamentoId)).thenReturn(Optional.of(pagamento));

            assertThatThrownBy(() -> pagamentoService.confirmarPagamentoWebhook(pagamentoId, new BigDecimal("100.00"), "NSU-01"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Transição inválida: Pagamento exige processamento prévio.");

            verify(pagamentoRepository, never()).save(any());
            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("Cenário 2 — Deve lançar BusinessException ao tentar confirmar CARTAO_DEBITO em status PENDENTE")
        void deveLancarBusinessExceptionQuandoCartaoDebitoEStatusPendente() {
            Long pagamentoId = 52L;
            Pagamento pagamento = criarPagamento(pagamentoId, 12L, MetodoPagamento.CARTAO_DEBITO, StatusPagamento.PENDENTE);
            when(pagamentoRepository.findById(pagamentoId)).thenReturn(Optional.of(pagamento));

            assertThatThrownBy(() -> pagamentoService.confirmarPagamentoWebhook(pagamentoId, new BigDecimal("60.00"), "NSU-02"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Transição inválida: Pagamento exige processamento prévio.");

            verify(pagamentoRepository, never()).save(any());
            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("Cenário 2 — Deve lançar BusinessException ao tentar confirmar CARTAO_DEBITO em status FALHOU")
        void deveLancarBusinessExceptionQuandoCartaoDebitoEStatusFalhou() {
            Long pagamentoId = 52L;
            Pagamento pagamento = criarPagamento(pagamentoId, 12L, MetodoPagamento.CARTAO_DEBITO, StatusPagamento.FALHOU);
            when(pagamentoRepository.findById(pagamentoId)).thenReturn(Optional.of(pagamento));

            assertThatThrownBy(() -> pagamentoService.confirmarPagamentoWebhook(pagamentoId, new BigDecimal("60.00"), "NSU-02"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Transição inválida: Pagamento exige processamento prévio.");

            verify(pagamentoRepository, never()).save(any());
            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("Cenário 2 — Deve lançar BusinessException ao tentar confirmar CARTAO_DEBITO em status ESTORNADO")
        void deveLancarBusinessExceptionQuandoCartaoDebitoEStatusEstornado() {
            Long pagamentoId = 52L;
            Pagamento pagamento = criarPagamento(pagamentoId, 12L, MetodoPagamento.CARTAO_DEBITO, StatusPagamento.ESTORNADO);
            when(pagamentoRepository.findById(pagamentoId)).thenReturn(Optional.of(pagamento));

            assertThatThrownBy(() -> pagamentoService.confirmarPagamentoWebhook(pagamentoId, new BigDecimal("60.00"), "NSU-02"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Transição inválida: Pagamento exige processamento prévio.");

            verify(pagamentoRepository, never()).save(any());
            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("Cenário 2 — Deve confirmar com sucesso método que NÃO exige processamento (PIX) a partir de status PENDENTE")
        void deveConfirmarComSucessoQuandoMetodoNaoExigeProcessamentoEStatusPendente() {
            Long pagamentoId = 60L;
            Long vendaId = 20L;
            BigDecimal valor = new BigDecimal("45.90");
            String nsu = "PIX-E2E-123";

            Pagamento pagamento = criarPagamento(pagamentoId, vendaId, MetodoPagamento.PIX, StatusPagamento.PENDENTE);
            when(pagamentoRepository.findById(pagamentoId)).thenReturn(Optional.of(pagamento));

            pagamentoService.confirmarPagamentoWebhook(pagamentoId, valor, nsu);

            assertThat(pagamento.getStatus()).isEqualTo(StatusPagamento.CONFIRMADO);
            assertThat(pagamento.getValorRecebido()).isEqualByComparingTo(valor);
            assertThat(pagamento.getNsuTransacao()).isEqualTo(nsu);

            verify(pagamentoRepository, times(1)).save(pagamento);
            verify(eventPublisher, times(1)).publishEvent(new PagamentoConfirmadoEvent(vendaId, pagamentoId));
        }

        @Test
        @DisplayName("Cenário 2 — Deve confirmar com sucesso método DINHEIRO a partir de status PENDENTE")
        void deveConfirmarComSucessoQuandoMetodoDinheiroEStatusPendente() {
            Long pagamentoId = 61L;
            Long vendaId = 21L;
            BigDecimal valor = new BigDecimal("100.00");
            String nsu = "DINHEIRO-CAIXA";

            Pagamento pagamento = criarPagamento(pagamentoId, vendaId, MetodoPagamento.DINHEIRO, StatusPagamento.PENDENTE);
            when(pagamentoRepository.findById(pagamentoId)).thenReturn(Optional.of(pagamento));

            pagamentoService.confirmarPagamentoWebhook(pagamentoId, valor, nsu);

            assertThat(pagamento.getStatus()).isEqualTo(StatusPagamento.CONFIRMADO);
            assertThat(pagamento.getValorRecebido()).isEqualByComparingTo(valor);
            assertThat(pagamento.getNsuTransacao()).isEqualTo(nsu);

            verify(pagamentoRepository, times(1)).save(pagamento);
            verify(eventPublisher, times(1)).publishEvent(new PagamentoConfirmadoEvent(vendaId, pagamentoId));
        }

        @Test
        @DisplayName("Cenário 2 / Pré-E1 — Caracteriza que PIX em status FALHOU transita para CONFIRMADO por ausência de guarda de validação")
        void preE1_devePermitirConfirmacaoDePixEmStatusFalhouDevidoAFaltaDeValidacao() {
            Long pagamentoId = 62L;
            Long vendaId = 22L;
            BigDecimal valor = new BigDecimal("75.00");
            String nsu = "PIX-FALHOU-01";

            Pagamento pagamento = criarPagamento(pagamentoId, vendaId, MetodoPagamento.PIX, StatusPagamento.FALHOU);
            when(pagamentoRepository.findById(pagamentoId)).thenReturn(Optional.of(pagamento));

            pagamentoService.confirmarPagamentoWebhook(pagamentoId, valor, nsu);

            assertThat(pagamento.getStatus()).isEqualTo(StatusPagamento.CONFIRMADO);
            assertThat(pagamento.getValorRecebido()).isEqualByComparingTo(valor);
            assertThat(pagamento.getNsuTransacao()).isEqualTo(nsu);
            assertThat(pagamento.getDataHoraPagamento()).isNotNull();

            verify(pagamentoRepository, times(1)).save(pagamento);
            verify(eventPublisher, times(1)).publishEvent(new PagamentoConfirmadoEvent(vendaId, pagamentoId));
        }

        @Test
        @DisplayName("Cenário 2 / Pré-E1 — Caracteriza que PIX em status ESTORNADO transita para CONFIRMADO por ausência de guarda de validação")
        void preE1_devePermitirConfirmacaoDePixEmStatusEstornadoDevidoAFaltaDeValidacao() {
            Long pagamentoId = 63L;
            Long vendaId = 23L;
            BigDecimal valor = new BigDecimal("85.00");
            String nsu = "PIX-ESTORNADO-01";

            Pagamento pagamento = criarPagamento(pagamentoId, vendaId, MetodoPagamento.PIX, StatusPagamento.ESTORNADO);
            when(pagamentoRepository.findById(pagamentoId)).thenReturn(Optional.of(pagamento));

            pagamentoService.confirmarPagamentoWebhook(pagamentoId, valor, nsu);

            assertThat(pagamento.getStatus()).isEqualTo(StatusPagamento.CONFIRMADO);
            assertThat(pagamento.getValorRecebido()).isEqualByComparingTo(valor);
            assertThat(pagamento.getNsuTransacao()).isEqualTo(nsu);

            verify(pagamentoRepository, times(1)).save(pagamento);
            verify(eventPublisher, times(1)).publishEvent(new PagamentoConfirmadoEvent(vendaId, pagamentoId));
        }

        @Test
        @DisplayName("Cenário 2 / Pré-E1 — Caracteriza que DINHEIRO em status FALHOU transita para CONFIRMADO por ausência de guarda de validação")
        void preE1_devePermitirConfirmacaoDeDinheiroEmStatusFalhouDevidoAFaltaDeValidacao() {
            Long pagamentoId = 64L;
            Long vendaId = 24L;
            BigDecimal valor = new BigDecimal("90.00");
            String nsu = "DINHEIRO-FALHOU";

            Pagamento pagamento = criarPagamento(pagamentoId, vendaId, MetodoPagamento.DINHEIRO, StatusPagamento.FALHOU);
            when(pagamentoRepository.findById(pagamentoId)).thenReturn(Optional.of(pagamento));

            pagamentoService.confirmarPagamentoWebhook(pagamentoId, valor, nsu);

            assertThat(pagamento.getStatus()).isEqualTo(StatusPagamento.CONFIRMADO);
            assertThat(pagamento.getValorRecebido()).isEqualByComparingTo(valor);

            verify(pagamentoRepository, times(1)).save(pagamento);
            verify(eventPublisher, times(1)).publishEvent(new PagamentoConfirmadoEvent(vendaId, pagamentoId));
        }

        @Test
        @DisplayName("Cenário 2 — Deve lançar BusinessException quando pagamentoId não for encontrado no repositório")
        void deveLancarBusinessExceptionQuandoPagamentoNaoEncontradoAoConfirmar() {
            when(pagamentoRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> pagamentoService.confirmarPagamentoWebhook(999L, new BigDecimal("100.00"), "NSU-999"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Pagamento não encontrado para o ID: 999");

            verify(pagamentoRepository, never()).save(any());
            verifyNoInteractions(eventPublisher);
        }
    }

    @Nested
    @DisplayName("criarIntencaoPagamento(Long, MetodoPagamento, BigDecimal, UUID)")
    class CriarIntencaoPagamentoTests {

        @Test
        @DisplayName("Deve retornar ID existente de forma idempotente quando uuidReferencia já existir")
        void deveRetornarIdExistenteQuandoUuidReferenciaJaExistir() {
            UUID uuidRef = UUID.randomUUID();
            Pagamento pagamentoExistente = criarPagamento(100L, 10L, MetodoPagamento.PIX, StatusPagamento.PENDENTE);
            when(pagamentoRepository.findByUuidReferencia(uuidRef)).thenReturn(Optional.of(pagamentoExistente));

            Long id = pagamentoService.criarIntencaoPagamento(10L, MetodoPagamento.PIX, new BigDecimal("50.00"), uuidRef);

            assertThat(id).isEqualTo(100L);
            verifyNoInteractions(vendaRepository);
            verify(pagamentoRepository, never()).save(any());
        }

        @Test
        @DisplayName("Deve lançar BusinessException quando vendaId não for encontrada")
        void deveLancarBusinessExceptionQuandoVendaNaoEncontrada() {
            UUID uuidRef = UUID.randomUUID();
            when(pagamentoRepository.findByUuidReferencia(uuidRef)).thenReturn(Optional.empty());
            when(vendaRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> pagamentoService.criarIntencaoPagamento(999L, MetodoPagamento.CARTAO_CREDITO, new BigDecimal("50.00"), uuidRef))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Venda não encontrada");

            verify(pagamentoRepository, never()).save(any());
        }

        @Test
        @DisplayName("Deve criar intenção com status PROCESSANDO quando método exige processamento (CARTAO_CREDITO)")
        void deveCriarIntencaoComStatusProcessandoQuandoMetodoExigeProcessamento() {
            UUID uuidRef = UUID.randomUUID();
            Venda venda = new Venda();
            ReflectionTestUtils.setField(venda, "id", 10L);

            when(pagamentoRepository.findByUuidReferencia(uuidRef)).thenReturn(Optional.empty());
            when(vendaRepository.findById(10L)).thenReturn(Optional.of(venda));
            when(pagamentoRepository.save(any(Pagamento.class))).thenAnswer(invocation -> {
                Pagamento p = invocation.getArgument(0);
                ReflectionTestUtils.setField(p, "id", 200L);
                return p;
            });

            Long id = pagamentoService.criarIntencaoPagamento(10L, MetodoPagamento.CARTAO_CREDITO, new BigDecimal("150.00"), uuidRef);

            assertThat(id).isEqualTo(200L);

            ArgumentCaptor<Pagamento> captor = ArgumentCaptor.forClass(Pagamento.class);
            verify(pagamentoRepository).save(captor.capture());
            Pagamento salvo = captor.getValue();

            assertThat(salvo.getStatus()).isEqualTo(StatusPagamento.PROCESSANDO);
            assertThat(salvo.getMetodo()).isEqualTo(MetodoPagamento.CARTAO_CREDITO);
            assertThat(salvo.getValorEsperado()).isEqualByComparingTo("150.00");
            assertThat(salvo.getUuidReferencia()).isEqualTo(uuidRef);
            assertThat(salvo.getVenda()).isEqualTo(venda);
        }

        @Test
        @DisplayName("Deve criar intenção com status PENDENTE quando método NÃO exige processamento (PIX)")
        void deveCriarIntencaoComStatusPendenteQuandoMetodoNaoExigeProcessamento() {
            UUID uuidRef = UUID.randomUUID();
            Venda venda = new Venda();
            ReflectionTestUtils.setField(venda, "id", 10L);

            when(pagamentoRepository.findByUuidReferencia(uuidRef)).thenReturn(Optional.empty());
            when(vendaRepository.findById(10L)).thenReturn(Optional.of(venda));
            when(pagamentoRepository.save(any(Pagamento.class))).thenAnswer(invocation -> {
                Pagamento p = invocation.getArgument(0);
                ReflectionTestUtils.setField(p, "id", 201L);
                return p;
            });

            Long id = pagamentoService.criarIntencaoPagamento(10L, MetodoPagamento.PIX, new BigDecimal("75.00"), uuidRef);

            assertThat(id).isEqualTo(201L);

            ArgumentCaptor<Pagamento> captor = ArgumentCaptor.forClass(Pagamento.class);
            verify(pagamentoRepository).save(captor.capture());
            Pagamento salvo = captor.getValue();

            assertThat(salvo.getStatus()).isEqualTo(StatusPagamento.PENDENTE);
            assertThat(salvo.getMetodo()).isEqualTo(MetodoPagamento.PIX);
            assertThat(salvo.getValorEsperado()).isEqualByComparingTo("75.00");
            assertThat(salvo.getUuidReferencia()).isEqualTo(uuidRef);
        }

        @Test
        @DisplayName("Deve criar intenção com status PROCESSANDO quando método for CARTAO_DEBITO")
        void deveCriarIntencaoComStatusProcessandoQuandoCartaoDebito() {
            UUID uuidRef = UUID.randomUUID();
            Venda venda = new Venda();
            ReflectionTestUtils.setField(venda, "id", 15L);

            when(pagamentoRepository.findByUuidReferencia(uuidRef)).thenReturn(Optional.empty());
            when(vendaRepository.findById(15L)).thenReturn(Optional.of(venda));
            when(pagamentoRepository.save(any(Pagamento.class))).thenAnswer(invocation -> {
                Pagamento p = invocation.getArgument(0);
                ReflectionTestUtils.setField(p, "id", 202L);
                return p;
            });

            Long id = pagamentoService.criarIntencaoPagamento(15L, MetodoPagamento.CARTAO_DEBITO, new BigDecimal("89.90"), uuidRef);

            assertThat(id).isEqualTo(202L);

            ArgumentCaptor<Pagamento> captor = ArgumentCaptor.forClass(Pagamento.class);
            verify(pagamentoRepository).save(captor.capture());
            Pagamento salvo = captor.getValue();

            assertThat(salvo.getStatus()).isEqualTo(StatusPagamento.PROCESSANDO);
            assertThat(salvo.getMetodo()).isEqualTo(MetodoPagamento.CARTAO_DEBITO);
            assertThat(salvo.getValorEsperado()).isEqualByComparingTo("89.90");
            assertThat(salvo.getUuidReferencia()).isEqualTo(uuidRef);
        }

        @Test
        @DisplayName("Deve criar intenção com status PENDENTE quando método for DINHEIRO")
        void deveCriarIntencaoComStatusPendenteQuandoDinheiro() {
            UUID uuidRef = UUID.randomUUID();
            Venda venda = new Venda();
            ReflectionTestUtils.setField(venda, "id", 16L);

            when(pagamentoRepository.findByUuidReferencia(uuidRef)).thenReturn(Optional.empty());
            when(vendaRepository.findById(16L)).thenReturn(Optional.of(venda));
            when(pagamentoRepository.save(any(Pagamento.class))).thenAnswer(invocation -> {
                Pagamento p = invocation.getArgument(0);
                ReflectionTestUtils.setField(p, "id", 203L);
                return p;
            });

            Long id = pagamentoService.criarIntencaoPagamento(16L, MetodoPagamento.DINHEIRO, new BigDecimal("120.00"), uuidRef);

            assertThat(id).isEqualTo(203L);

            ArgumentCaptor<Pagamento> captor = ArgumentCaptor.forClass(Pagamento.class);
            verify(pagamentoRepository).save(captor.capture());
            Pagamento salvo = captor.getValue();

            assertThat(salvo.getStatus()).isEqualTo(StatusPagamento.PENDENTE);
            assertThat(salvo.getMetodo()).isEqualTo(MetodoPagamento.DINHEIRO);
            assertThat(salvo.getValorEsperado()).isEqualByComparingTo("120.00");
            assertThat(salvo.getUuidReferencia()).isEqualTo(uuidRef);
        }
    }

    @Nested
    @DisplayName("buscarStatusPagamento(Long)")
    class BuscarStatusPagamentoTests {

        @Test
        @DisplayName("Deve retornar status correto quando pagamento for encontrado")
        void deveRetornarStatusQuandoPagamentoExistir() {
            Pagamento pagamento = criarPagamento(50L, 10L, MetodoPagamento.PIX, StatusPagamento.CONFIRMADO);
            when(pagamentoRepository.findById(50L)).thenReturn(Optional.of(pagamento));

            StatusPagamento status = pagamentoService.buscarStatusPagamento(50L);

            assertThat(status).isEqualTo(StatusPagamento.CONFIRMADO);
            verify(pagamentoRepository, times(1)).findById(50L);
        }

        @Test
        @DisplayName("Deve lançar BusinessException quando pagamento não for encontrado")
        void deveLancarBusinessExceptionQuandoPagamentoNaoEncontrado() {
            when(pagamentoRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> pagamentoService.buscarStatusPagamento(999L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Pagamento não encontrado");
        }
    }

    @Nested
    @DisplayName("falharPagamentoWebhook(Long)")
    class FalharPagamentoWebhookTests {

        @Test
        @DisplayName("Deve marcar como FALHOU quando status for PROCESSANDO")
        void deveFalharPagamentoComSucessoQuandoStatusForProcessando() {
            Pagamento pagamento = criarPagamento(50L, 10L, MetodoPagamento.CARTAO_CREDITO, StatusPagamento.PROCESSANDO);
            when(pagamentoRepository.findById(50L)).thenReturn(Optional.of(pagamento));

            pagamentoService.falharPagamentoWebhook(50L);

            assertThat(pagamento.getStatus()).isEqualTo(StatusPagamento.FALHOU);
            verify(pagamentoRepository, times(1)).save(pagamento);
        }

        @Test
        @DisplayName("Deve lançar BusinessException ao tentar falhar pagamento que não está em PROCESSANDO")
        void deveLancarBusinessExceptionAoFalharQuandoStatusNaoForProcessando() {
            Pagamento pagamento = criarPagamento(50L, 10L, MetodoPagamento.CARTAO_CREDITO, StatusPagamento.PENDENTE);
            when(pagamentoRepository.findById(50L)).thenReturn(Optional.of(pagamento));

            assertThatThrownBy(() -> pagamentoService.falharPagamentoWebhook(50L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Transição inválida para falha de pagamento.");

            verify(pagamentoRepository, never()).save(any());
        }

        @Test
        @DisplayName("Pré-E1 — Webhook de falha duplicado lança BusinessException por ausência de idempotência na falha")
        void deveLancarBusinessExceptionAoTentarFalharNovamentePagamentoJaFalhado() {
            Long pagamentoId = 55L;
            Pagamento pagamento = criarPagamento(pagamentoId, 10L, MetodoPagamento.CARTAO_CREDITO, StatusPagamento.PROCESSANDO);
            when(pagamentoRepository.findById(pagamentoId)).thenReturn(Optional.of(pagamento));

            // Primeira chamada: transita com sucesso PROCESSANDO -> FALHOU
            pagamentoService.falharPagamentoWebhook(pagamentoId);
            assertThat(pagamento.getStatus()).isEqualTo(StatusPagamento.FALHOU);
            verify(pagamentoRepository, times(1)).save(pagamento);

            // Segunda chamada: status agora é FALHOU -> lança BusinessException comprovando falta de idempotência
            assertThatThrownBy(() -> pagamentoService.falharPagamentoWebhook(pagamentoId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Transição inválida para falha de pagamento.");

            verify(pagamentoRepository, times(1)).save(pagamento);
        }

        @Test
        @DisplayName("Deve lançar BusinessException ao tentar falhar pagamento que já está CONFIRMADO")
        void deveLancarBusinessExceptionAoTentarFalharPagamentoJaConfirmado() {
            Long pagamentoId = 56L;
            Pagamento pagamento = criarPagamento(pagamentoId, 10L, MetodoPagamento.CARTAO_CREDITO, StatusPagamento.CONFIRMADO);
            when(pagamentoRepository.findById(pagamentoId)).thenReturn(Optional.of(pagamento));

            assertThatThrownBy(() -> pagamentoService.falharPagamentoWebhook(pagamentoId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Transição inválida para falha de pagamento.");

            verify(pagamentoRepository, never()).save(any());
        }

        @Test
        @DisplayName("Deve lançar BusinessException ao tentar falhar pagamento PIX que nasce em PENDENTE")
        void deveLancarBusinessExceptionAoTentarFalharPagamentoPixQueNaoExigeProcessamento() {
            Long pagamentoId = 57L;
            Pagamento pagamento = criarPagamento(pagamentoId, 10L, MetodoPagamento.PIX, StatusPagamento.PENDENTE);
            when(pagamentoRepository.findById(pagamentoId)).thenReturn(Optional.of(pagamento));

            assertThatThrownBy(() -> pagamentoService.falharPagamentoWebhook(pagamentoId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Transição inválida para falha de pagamento.");

            verify(pagamentoRepository, never()).save(any());
        }

        @Test
        @DisplayName("Deve lançar BusinessException ao tentar falhar pagamento inexistente")
        void deveLancarBusinessExceptionQuandoPagamentoNaoEncontradoAoFalhar() {
            when(pagamentoRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> pagamentoService.falharPagamentoWebhook(999L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Pagamento não encontrado para o ID: 999");

            verify(pagamentoRepository, never()).save(any());
        }
    }

    private static Pagamento criarPagamento(Long pagamentoId, Long vendaId, MetodoPagamento metodo, StatusPagamento status) {
        Venda venda = new Venda();
        ReflectionTestUtils.setField(venda, "id", vendaId);
        Pagamento pagamento = new Pagamento(venda, metodo, new BigDecimal("100.00"), UUID.randomUUID());
        ReflectionTestUtils.setField(pagamento, "id", pagamentoId);
        ReflectionTestUtils.setField(pagamento, "status", status);
        return pagamento;
    }
}
