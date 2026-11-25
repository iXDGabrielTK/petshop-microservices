/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.checkout.usecase;

import common.exception.BusinessException;
import common.exception.ConflictException;
import common.exception.ResourceNotFoundException;
import inv.checkout.domain.model.Pagamento;
import inv.checkout.domain.model.StatusPagamento;
import inv.checkout.domain.model.StatusVenda;
import inv.checkout.domain.model.Venda;
import inv.checkout.infrastructure.messaging.event.PagamentoConfirmadoEvent;
import inv.checkout.infrastructure.persistence.PagamentoRepository;
import inv.checkout.infrastructure.persistence.VendaRepository;
import inv.shared.domain.MetodoPagamento;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
        @DisplayName("Cenário 2 / F3 — Webhook duplicado em pagamento já CONFIRMADO deve agir como no-op idempotente sem salvar ou publicar novamente")
        void webhookDuplicadoEmPagamentoJaConfirmadoDeveSerIdempotenteSemSalvarOuPublicarNovamente() {
            // ARRANGE
            Long pagamentoId = 50L;
            Long vendaId = 10L;
            BigDecimal valor = new BigDecimal("100.00");
            String nsu = "NSU-123456";

            Pagamento pagamento = criarPagamento(pagamentoId, vendaId, MetodoPagamento.CARTAO_CREDITO, StatusPagamento.PROCESSANDO);
            when(pagamentoRepository.findByIdWithLock(pagamentoId)).thenReturn(Optional.of(pagamento));

            // ACT 1 — Primeira entrega de webhook: processamento normal
            pagamentoService.confirmarPagamentoWebhook(pagamentoId, valor, nsu);

            assertThat(pagamento.getStatus()).isEqualTo(StatusPagamento.CONFIRMADO);
            assertThat(pagamento.getValorRecebido()).isEqualByComparingTo(valor);
            assertThat(pagamento.getNsuTransacao()).isEqualTo(nsu);
            LocalDateTime primeiraDataHora = pagamento.getDataHoraPagamento();
            assertThat(primeiraDataHora).isNotNull();
            verify(pagamentoRepository, times(1)).save(pagamento);
            verify(eventPublisher, times(1)).publishEvent(any(PagamentoConfirmadoEvent.class));
            verify(eventPublisher, times(1)).publishEvent(eq(new PagamentoConfirmadoEvent(vendaId, pagamentoId)));

            // ACT 2 — Segunda entrega de webhook (retry/duplicação do gateway)
            pagamentoService.confirmarPagamentoWebhook(pagamentoId, valor, nsu);

            // ASSERT — Idempotência de F3: pagamento.confirmar() retorna false, save e publishEvent NÃO são invocados novamente
            assertThat(pagamento.getStatus()).isEqualTo(StatusPagamento.CONFIRMADO);
            assertThat(pagamento.getValorRecebido()).isEqualByComparingTo(valor);
            assertThat(pagamento.getNsuTransacao()).isEqualTo(nsu);
            assertThat(pagamento.getDataHoraPagamento()).isEqualTo(primeiraDataHora);

            // save e publishEvent permanecem tendo sido invocados apenas 1 vez (da primeira entrega)
            verify(pagamentoRepository, times(1)).save(pagamento);
            verify(eventPublisher, times(1)).publishEvent(any(PagamentoConfirmadoEvent.class));
            verify(eventPublisher, times(1)).publishEvent(eq(new PagamentoConfirmadoEvent(vendaId, pagamentoId)));
        }

        @Test
        @DisplayName("Cenário 2 / F3 — Webhook duplicado em pagamento PIX já CONFIRMADO deve agir como no-op idempotente")
        void webhookDuplicadoEmPagamentoPixJaConfirmadoDeveSerIdempotente() {
            Long pagamentoId = 60L;
            Long vendaId = 20L;
            BigDecimal valor = new BigDecimal("45.90");
            String nsu = "PIX-E2E-123";

            Pagamento pagamento = criarPagamento(pagamentoId, vendaId, MetodoPagamento.PIX, StatusPagamento.PENDENTE);
            when(pagamentoRepository.findByIdWithLock(pagamentoId)).thenReturn(Optional.of(pagamento));

            // ACT 1 — Primeira entrega de webhook: confirmação normal
            pagamentoService.confirmarPagamentoWebhook(pagamentoId, valor, nsu);

            assertThat(pagamento.getStatus()).isEqualTo(StatusPagamento.CONFIRMADO);
            assertThat(pagamento.getValorRecebido()).isEqualByComparingTo(valor);
            LocalDateTime primeiraDataHora = pagamento.getDataHoraPagamento();
            assertThat(primeiraDataHora).isNotNull();
            verify(pagamentoRepository, times(1)).save(pagamento);
            verify(eventPublisher, times(1)).publishEvent(any(PagamentoConfirmadoEvent.class));
            verify(eventPublisher, times(1)).publishEvent(eq(new PagamentoConfirmadoEvent(vendaId, pagamentoId)));

            // ACT 2 — Segunda entrega redundante de webhook
            pagamentoService.confirmarPagamentoWebhook(pagamentoId, valor, nsu);

            // ASSERT — Idempotência de F3 para método sem processamento prévio
            assertThat(pagamento.getStatus()).isEqualTo(StatusPagamento.CONFIRMADO);
            assertThat(pagamento.getDataHoraPagamento()).isEqualTo(primeiraDataHora);
            verify(pagamentoRepository, times(1)).save(pagamento);
            verify(eventPublisher, times(1)).publishEvent(any(PagamentoConfirmadoEvent.class));
            verify(eventPublisher, times(1)).publishEvent(eq(new PagamentoConfirmadoEvent(vendaId, pagamentoId)));
        }

        @Test
        @DisplayName("Cenário 2 / F3 — Webhook em pagamento previamente CONFIRMADO não deve salvar nem publicar evento")
        void webhookEmPagamentoPreviamenteConfirmadoNaoDeveSalvarNemPublicar() {
            // ARRANGE
            Long pagamentoId = 51L;
            Long vendaId = 11L;
            BigDecimal valorInicial = new BigDecimal("80.00");
            String nsuInicial = "NSU-EXISTENTE";

            Pagamento pagamento = criarPagamento(pagamentoId, vendaId, MetodoPagamento.CARTAO_CREDITO, StatusPagamento.CONFIRMADO);
            ReflectionTestUtils.setField(pagamento, "valorRecebido", valorInicial);
            ReflectionTestUtils.setField(pagamento, "nsuTransacao", nsuInicial);
            when(pagamentoRepository.findByIdWithLock(pagamentoId)).thenReturn(Optional.of(pagamento));

            // ACT
            pagamentoService.confirmarPagamentoWebhook(pagamentoId, new BigDecimal("999.00"), "NSU-NOVO-IGNORADO");

            // ASSERT — Entidade permaneceu com os valores da primeira confirmação
            assertThat(pagamento.getStatus()).isEqualTo(StatusPagamento.CONFIRMADO);
            assertThat(pagamento.getValorRecebido()).isEqualByComparingTo(valorInicial);
            assertThat(pagamento.getNsuTransacao()).isEqualTo(nsuInicial);

            // E save e publishEvent NÃO são invocados (no-op estrito)
            verify(pagamentoRepository, never()).save(any());
            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("Cenário 2 — Deve confirmar com sucesso quando método exige processamento (CARTAO_CREDITO) e status for PROCESSANDO")
        void deveConfirmarComSucessoQuandoMetodoExigeProcessamentoEStatusProcessando() {
            Long pagamentoId = 50L;
            Long vendaId = 10L;
            BigDecimal valor = new BigDecimal("150.00");
            String nsu = "NSU-CREDITO-01";

            Pagamento pagamento = criarPagamento(pagamentoId, vendaId, MetodoPagamento.CARTAO_CREDITO, StatusPagamento.PROCESSANDO, valor, UUID.randomUUID());
            when(pagamentoRepository.findByIdWithLock(pagamentoId)).thenReturn(Optional.of(pagamento));

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

            Pagamento pagamento = criarPagamento(pagamentoId, vendaId, MetodoPagamento.CARTAO_DEBITO, StatusPagamento.PROCESSANDO, valor, UUID.randomUUID());
            when(pagamentoRepository.findByIdWithLock(pagamentoId)).thenReturn(Optional.of(pagamento));

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
            when(pagamentoRepository.findByIdWithLock(pagamentoId)).thenReturn(Optional.of(pagamento));

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
            when(pagamentoRepository.findByIdWithLock(pagamentoId)).thenReturn(Optional.of(pagamento));

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
            when(pagamentoRepository.findByIdWithLock(pagamentoId)).thenReturn(Optional.of(pagamento));

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
            when(pagamentoRepository.findByIdWithLock(pagamentoId)).thenReturn(Optional.of(pagamento));

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
            when(pagamentoRepository.findByIdWithLock(pagamentoId)).thenReturn(Optional.of(pagamento));

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
            when(pagamentoRepository.findByIdWithLock(pagamentoId)).thenReturn(Optional.of(pagamento));

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
            when(pagamentoRepository.findByIdWithLock(pagamentoId)).thenReturn(Optional.of(pagamento));

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
            when(pagamentoRepository.findByIdWithLock(pagamentoId)).thenReturn(Optional.of(pagamento));

            pagamentoService.confirmarPagamentoWebhook(pagamentoId, valor, nsu);

            assertThat(pagamento.getStatus()).isEqualTo(StatusPagamento.CONFIRMADO);
            assertThat(pagamento.getValorRecebido()).isEqualByComparingTo(valor);
            assertThat(pagamento.getNsuTransacao()).isEqualTo(nsu);

            verify(pagamentoRepository, times(1)).save(pagamento);
            verify(eventPublisher, times(1)).publishEvent(new PagamentoConfirmadoEvent(vendaId, pagamentoId));
        }

        @Test
        @DisplayName("Cenário 2 / F3 — Deve lançar BusinessException ao tentar confirmar PIX em status FALHOU")
        void deveLancarBusinessExceptionQuandoPixEmStatusFalhou() {
            Long pagamentoId = 62L;
            Pagamento pagamento = criarPagamento(pagamentoId, 22L, MetodoPagamento.PIX, StatusPagamento.FALHOU);
            when(pagamentoRepository.findByIdWithLock(pagamentoId)).thenReturn(Optional.of(pagamento));

            assertThatThrownBy(() -> pagamentoService.confirmarPagamentoWebhook(pagamentoId, new BigDecimal("75.00"), "PIX-FALHOU-01"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Transição inválida: Pagamento com status FALHOU não pode ser confirmado.");

            verify(pagamentoRepository, never()).save(any());
            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("Cenário 2 / F3 — Deve lançar BusinessException ao tentar confirmar PIX em status ESTORNADO")
        void deveLancarBusinessExceptionQuandoPixEmStatusEstornado() {
            Long pagamentoId = 63L;
            Pagamento pagamento = criarPagamento(pagamentoId, 23L, MetodoPagamento.PIX, StatusPagamento.ESTORNADO);
            when(pagamentoRepository.findByIdWithLock(pagamentoId)).thenReturn(Optional.of(pagamento));

            assertThatThrownBy(() -> pagamentoService.confirmarPagamentoWebhook(pagamentoId, new BigDecimal("85.00"), "PIX-ESTORNADO-01"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Transição inválida: Pagamento com status ESTORNADO não pode ser confirmado.");

            verify(pagamentoRepository, never()).save(any());
            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("Cenário 2 / F3 — Deve lançar BusinessException ao tentar confirmar DINHEIRO em status FALHOU")
        void deveLancarBusinessExceptionQuandoDinheiroEmStatusFalhou() {
            Long pagamentoId = 64L;
            Pagamento pagamento = criarPagamento(pagamentoId, 24L, MetodoPagamento.DINHEIRO, StatusPagamento.FALHOU);
            when(pagamentoRepository.findByIdWithLock(pagamentoId)).thenReturn(Optional.of(pagamento));

            assertThatThrownBy(() -> pagamentoService.confirmarPagamentoWebhook(pagamentoId, new BigDecimal("90.00"), "DINHEIRO-FALHOU"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Transição inválida: Pagamento com status FALHOU não pode ser confirmado.");

            verify(pagamentoRepository, never()).save(any());
            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("Cenário 2 / F3 — Deve lançar BusinessException ao tentar confirmar DINHEIRO em status ESTORNADO")
        void deveLancarBusinessExceptionQuandoDinheiroEmStatusEstornado() {
            Long pagamentoId = 65L;
            Pagamento pagamento = criarPagamento(pagamentoId, 25L, MetodoPagamento.DINHEIRO, StatusPagamento.ESTORNADO);
            when(pagamentoRepository.findByIdWithLock(pagamentoId)).thenReturn(Optional.of(pagamento));

            assertThatThrownBy(() -> pagamentoService.confirmarPagamentoWebhook(pagamentoId, new BigDecimal("95.00"), "DINHEIRO-ESTORNADO"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Transição inválida: Pagamento com status ESTORNADO não pode ser confirmado.");

            verify(pagamentoRepository, never()).save(any());
            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("Cenário 2 — Deve lançar BusinessException quando pagamentoId não for encontrado no repositório")
        void deveLancarBusinessExceptionQuandoPagamentoNaoEncontradoAoConfirmar() {
            when(pagamentoRepository.findByIdWithLock(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> pagamentoService.confirmarPagamentoWebhook(999L, new BigDecimal("100.00"), "NSU-999"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Pagamento não encontrado para o ID: 999");

            verify(pagamentoRepository, never()).save(any());
            verifyNoInteractions(eventPublisher);
        }

        @ParameterizedTest(name = "Método eletrônico {0} rejeita valor divergente")
        @EnumSource(value = MetodoPagamento.class, names = {"CARTAO_CREDITO", "CARTAO_DEBITO"})
        @DisplayName("Deve rejeitar confirmação webhook com valor divergente para pagamento eletrônico")
        void deveRejeitarConfirmacaoWebhookComValorDivergenteParaEletronico(MetodoPagamento metodo) {
            Long pagamentoId = 70L;
            Pagamento pagamento = criarPagamento(pagamentoId, 10L, metodo, StatusPagamento.PROCESSANDO,
                    new BigDecimal("100.00"), UUID.randomUUID());
            when(pagamentoRepository.findByIdWithLock(pagamentoId)).thenReturn(Optional.of(pagamento));

            assertThatThrownBy(() -> pagamentoService.confirmarPagamentoWebhook(pagamentoId, new BigDecimal("99.99"), "NSU-DIV"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Valor recebido diverge do valor esperado para pagamento eletrônico.");

            verify(pagamentoRepository, never()).save(any());
            verifyNoInteractions(eventPublisher);
        }
    }

    @Nested
    @DisplayName("criarIntencaoPagamento(Long, MetodoPagamento, BigDecimal, UUID)")
    class CriarIntencaoPagamentoTests {

        @Test
        @DisplayName("Etapa 1 — Deve retornar ID existente de forma idempotente quando uuidReferencia já existir com payload igual")
        void deveRetornarIdExistenteQuandoUuidReferenciaJaExistir() {
            UUID uuidRef = UUID.randomUUID();
            Pagamento pagamentoExistente = criarPagamento(100L, 10L, MetodoPagamento.PIX, StatusPagamento.PENDENTE,
                    new BigDecimal("50.00"), uuidRef);
            when(pagamentoRepository.findByUuidReferencia(uuidRef)).thenReturn(Optional.of(pagamentoExistente));

            Long id = pagamentoService.criarIntencaoPagamento(10L, MetodoPagamento.PIX, new BigDecimal("50.00"), uuidRef);

            assertThat(id).isEqualTo(100L);
            verifyNoInteractions(vendaRepository);
            verify(pagamentoRepository, never()).inserirIntencaoIdempotente(any(), any(), any(), any(), any(), any(), anyBoolean(), anyBoolean());
        }

        @ParameterizedTest(name = "Retry com valor equivalente {0} vs 10.00")
        @ValueSource(strings = {"10.0", "10.000", "10"})
        @DisplayName("Cenário 1 — Retry idêntico com escala equivalente (10.0, 10.000, 10 vs 10.00) retorna ID existente")
        void deveRetornarIdExistenteQuandoEscalaEquivalente(String valorRetry) {
            UUID uuidRef = UUID.randomUUID();
            Pagamento pagamentoExistente = criarPagamento(101L, 10L, MetodoPagamento.PIX, StatusPagamento.PENDENTE,
                    new BigDecimal("10.00"), uuidRef);
            when(pagamentoRepository.findByUuidReferencia(uuidRef)).thenReturn(Optional.of(pagamentoExistente));

            Long id = pagamentoService.criarIntencaoPagamento(10L, MetodoPagamento.PIX, new BigDecimal(valorRetry), uuidRef);

            assertThat(id).isEqualTo(101L);
            verifyNoInteractions(vendaRepository);
            verify(pagamentoRepository, never()).inserirIntencaoIdempotente(any(), any(), any(), any(), any(), any(), anyBoolean(), anyBoolean());
        }

        @Test
        @DisplayName("Cenário 1 — Etapa 1 deve lançar ConflictException (409) quando uuidReferencia divergir em vendaId, metodo ou valor")
        void deveLancarConflictExceptionNaEtapa1QuandoPayloadDivergir() {
            UUID uuidRef = UUID.randomUUID();
            Pagamento existente = criarPagamento(100L, 10L, MetodoPagamento.PIX, StatusPagamento.PENDENTE,
                    new BigDecimal("50.00"), uuidRef);
            when(pagamentoRepository.findByUuidReferencia(uuidRef)).thenReturn(Optional.of(existente));

            assertThatThrownBy(() -> pagamentoService.criarIntencaoPagamento(99L, MetodoPagamento.PIX, new BigDecimal("50.00"), uuidRef))
                    .isInstanceOf(ConflictException.class);

            assertThatThrownBy(() -> pagamentoService.criarIntencaoPagamento(10L, MetodoPagamento.CARTAO_CREDITO, new BigDecimal("50.00"), uuidRef))
                    .isInstanceOf(ConflictException.class);

            assertThatThrownBy(() -> pagamentoService.criarIntencaoPagamento(10L, MetodoPagamento.PIX, new BigDecimal("50.01"), uuidRef))
                    .isInstanceOf(ConflictException.class);

            verifyNoInteractions(vendaRepository);
            verify(pagamentoRepository, never()).inserirIntencaoIdempotente(any(), any(), any(), any(), any(), any(), anyBoolean(), anyBoolean());
        }

        @Test
        @DisplayName("Cenário 1 — Etapa 2 deve lançar ResourceNotFoundException (404) quando vendaId não existir")
        void deveLancarResourceNotFoundExceptionQuandoVendaNaoEncontrada() {
            UUID uuidRef = UUID.randomUUID();
            when(pagamentoRepository.findByUuidReferencia(uuidRef)).thenReturn(Optional.empty());
            when(vendaRepository.findByIdWithLock(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> pagamentoService.criarIntencaoPagamento(999L, MetodoPagamento.CARTAO_CREDITO, new BigDecimal("50.00"), uuidRef))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("Venda não encontrada");

            verify(pagamentoRepository, never()).inserirIntencaoIdempotente(any(), any(), any(), any(), any(), any(), anyBoolean(), anyBoolean());
        }

        @ParameterizedTest(name = "Venda em status incompatível: {0}")
        @EnumSource(value = StatusVenda.class, names = {"ABERTA", "CONCLUIDA", "CANCELADA", "CANCELAMENTO_SOLICITADO"})
        @DisplayName("Cenário 1 — Etapa 4 deve lançar ConflictException (409) quando venda não aceitar pagamento")
        void deveLancarConflictExceptionQuandoVendaEmStatusIncompativel(StatusVenda statusIncompativel) {
            UUID uuidRef = UUID.randomUUID();
            Venda venda = criarVenda(10L, statusIncompativel);

            when(pagamentoRepository.findByUuidReferencia(uuidRef)).thenReturn(Optional.empty());
            when(vendaRepository.findByIdWithLock(10L)).thenReturn(Optional.of(venda));

            assertThatThrownBy(() -> pagamentoService.criarIntencaoPagamento(10L, MetodoPagamento.PIX, new BigDecimal("50.00"), uuidRef))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("A venda não aceita pagamentos no status: " + statusIncompativel);

            verify(pagamentoRepository, never()).inserirIntencaoIdempotente(any(), any(), any(), any(), any(), any(), anyBoolean(), anyBoolean());
        }

        @Test
        @DisplayName("Cenário 2 — Defesa em profundidade deve rejeitar valor null com BusinessException antes de acessar repositórios")
        void deveRejeitarValorNuloComBusinessException() {
            UUID uuidRef = UUID.randomUUID();

            assertThatThrownBy(() -> pagamentoService.criarIntencaoPagamento(10L, MetodoPagamento.PIX, null, uuidRef))
                    .isInstanceOf(BusinessException.class);

            verifyNoInteractions(pagamentoRepository, vendaRepository);
        }

        @ParameterizedTest(name = "Valor inválido rejeitado no service: {0}")
        @ValueSource(strings = {"0", "0.00", "-1.00", "10.005", "100000000.00"})
        @DisplayName("Cenário 2 — Defesa em profundidade deve rejeitar <= 0, escala > 2 ou > 99999999.99 com BusinessException")
        void deveRejeitarValoresInvalidosComBusinessException(String valorInvalido) {
            UUID uuidRef = UUID.randomUUID();

            assertThatThrownBy(() -> pagamentoService.criarIntencaoPagamento(
                    10L, MetodoPagamento.PIX, new BigDecimal(valorInvalido), uuidRef))
                    .isInstanceOf(BusinessException.class);

            verifyNoInteractions(pagamentoRepository, vendaRepository);
        }

        @ParameterizedTest(name = "Valor aceito sem arredondamento: {0}")
        @ValueSource(strings = {"10.000", "10.00", "100"})
        @DisplayName("Cenário 2 — Service deve aceitar 10.000, 10.00 e 100 preservando escala efetiva <= 2")
        void deveAceitarValoresValidosComEscalaEfetivaAteDuasCasas(String valorTexto) {
            UUID uuidRef = UUID.randomUUID();
            BigDecimal valor = new BigDecimal(valorTexto);
            Venda venda = criarVenda(10L, StatusVenda.AGUARDANDO_PAGAMENTO);

            when(pagamentoRepository.findByUuidReferencia(uuidRef)).thenReturn(Optional.empty());
            when(vendaRepository.findByIdWithLock(10L)).thenReturn(Optional.of(venda));
            when(pagamentoRepository.inserirIntencaoIdempotente(
                    eq(uuidRef),
                    eq(10L),
                    eq("PIX"),
                    eq("PENDENTE"),
                    valorComEscalaEfetivaAteDuasCasas(valor),
                    eq(BigDecimal.ZERO),
                    eq(false),
                    eq(false)))
                    .thenReturn(Optional.of(210L));

            Long id = pagamentoService.criarIntencaoPagamento(10L, MetodoPagamento.PIX, valor, uuidRef);

            assertThat(id).isEqualTo(210L);
            ArgumentCaptor<BigDecimal> valorCaptor = ArgumentCaptor.forClass(BigDecimal.class);
            verify(pagamentoRepository).inserirIntencaoIdempotente(
                    eq(uuidRef),
                    eq(10L),
                    eq("PIX"),
                    eq("PENDENTE"),
                    valorCaptor.capture(),
                    eq(BigDecimal.ZERO),
                    eq(false),
                    eq(false));
            BigDecimal valorEnviado = valorCaptor.getValue();
            assertThat(valorEnviado).isNotNull().isEqualByComparingTo(valor);
            assertThat(valorEnviado.stripTrailingZeros().scale()).isLessThanOrEqualTo(2);
        }

        @Test
        @DisplayName("Deve criar intenção com status PROCESSANDO quando método exige processamento (CARTAO_CREDITO)")
        void deveCriarIntencaoComStatusProcessandoQuandoMetodoExigeProcessamento() {
            UUID uuidRef = UUID.randomUUID();
            BigDecimal valor = new BigDecimal("150.00");
            Venda venda = criarVendaComSaldo(10L, StatusVenda.AGUARDANDO_PAGAMENTO, new BigDecimal("150.00"), BigDecimal.ZERO);

            when(pagamentoRepository.findByUuidReferencia(uuidRef)).thenReturn(Optional.empty());
            when(vendaRepository.findByIdWithLock(10L)).thenReturn(Optional.of(venda));
            when(pagamentoRepository.inserirIntencaoIdempotente(
                    uuidRef, 10L, "CARTAO_CREDITO", "PROCESSANDO", valor, BigDecimal.ZERO, false, false))
                    .thenReturn(Optional.of(200L));

            Long id = pagamentoService.criarIntencaoPagamento(10L, MetodoPagamento.CARTAO_CREDITO, valor, uuidRef);

            assertThat(id).isEqualTo(200L);
            verify(pagamentoRepository).inserirIntencaoIdempotente(
                    uuidRef, 10L, "CARTAO_CREDITO", "PROCESSANDO", valor, BigDecimal.ZERO, false, false);
        }

        @Test
        @DisplayName("Deve rejeitar intenção eletrônica quando valor exceder saldo devedor atual da venda")
        void deveRejeitarIntencaoEletronicaQuandoExcederSaldoDevedor() {
            UUID uuidRef = UUID.randomUUID();
            Venda venda = criarVendaComSaldo(10L, StatusVenda.AGUARDANDO_PAGAMENTO, new BigDecimal("100.00"), new BigDecimal("40.00"));

            when(pagamentoRepository.findByUuidReferencia(uuidRef)).thenReturn(Optional.empty());
            when(vendaRepository.findByIdWithLock(10L)).thenReturn(Optional.of(venda));

            assertThatThrownBy(() -> pagamentoService.criarIntencaoPagamento(
                    10L, MetodoPagamento.CARTAO_CREDITO, new BigDecimal("60.01"), uuidRef))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("saldo devedor atual da venda");

            verify(pagamentoRepository, never()).inserirIntencaoIdempotente(any(), any(), any(), any(), any(), any(), anyBoolean(), anyBoolean());
        }

        @Test
        @DisplayName("Deve rejeitar intenção quando metodo ou uuidReferencia forem nulos")
        void deveRejeitarMetodoOuUuidNulo() {
            assertThatThrownBy(() -> pagamentoService.criarIntencaoPagamento(
                    10L, null, new BigDecimal("50.00"), UUID.randomUUID()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Método");

            assertThatThrownBy(() -> pagamentoService.criarIntencaoPagamento(
                    10L, MetodoPagamento.PIX, new BigDecimal("50.00"), null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("UUID");

            verifyNoInteractions(pagamentoRepository, vendaRepository);
        }

        @Test
        @DisplayName("Deve validar teto de saldo devedor para CARTAO_DEBITO (rejeitar excesso, saldo zero e aceitar igual)")
        void deveValidarSaldoDevedorParaCartaoDebito() {
            UUID uuidExcesso = UUID.randomUUID();
            Venda vendaSaldo60 = criarVendaComSaldo(10L, StatusVenda.AGUARDANDO_PAGAMENTO, new BigDecimal("100.00"), new BigDecimal("40.00"));
            when(pagamentoRepository.findByUuidReferencia(uuidExcesso)).thenReturn(Optional.empty());
            when(vendaRepository.findByIdWithLock(10L)).thenReturn(Optional.of(vendaSaldo60));

            assertThatThrownBy(() -> pagamentoService.criarIntencaoPagamento(
                    10L, MetodoPagamento.CARTAO_DEBITO, new BigDecimal("60.01"), uuidExcesso))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("saldo devedor atual da venda");

            UUID uuidSaldoZero = UUID.randomUUID();
            Venda vendaQuitada = criarVendaComSaldo(11L, StatusVenda.AGUARDANDO_PAGAMENTO, new BigDecimal("100.00"), new BigDecimal("100.00"));
            when(pagamentoRepository.findByUuidReferencia(uuidSaldoZero)).thenReturn(Optional.empty());
            when(vendaRepository.findByIdWithLock(11L)).thenReturn(Optional.of(vendaQuitada));

            assertThatThrownBy(() -> pagamentoService.criarIntencaoPagamento(
                    11L, MetodoPagamento.CARTAO_DEBITO, new BigDecimal("1.00"), uuidSaldoZero))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("saldo devedor atual da venda");

            UUID uuidIgual = UUID.randomUUID();
            when(pagamentoRepository.findByUuidReferencia(uuidIgual)).thenReturn(Optional.empty());
            when(pagamentoRepository.inserirIntencaoIdempotente(
                    uuidIgual, 10L, "CARTAO_DEBITO", "PROCESSANDO", new BigDecimal("60.00"), BigDecimal.ZERO, false, false))
                    .thenReturn(Optional.of(206L));

            Long id = pagamentoService.criarIntencaoPagamento(10L, MetodoPagamento.CARTAO_DEBITO, new BigDecimal("60.00"), uuidIgual);
            assertThat(id).isEqualTo(206L);
        }

        @Test
        @DisplayName("Deve permitir intenção em DINHEIRO acima do saldo devedor (para troco)")
        void devePermitirIntencaoDinheiroAcimaDoSaldoDevedor() {
            UUID uuidRef = UUID.randomUUID();
            BigDecimal valor = new BigDecimal("150.00");
            Venda venda = criarVendaComSaldo(10L, StatusVenda.AGUARDANDO_PAGAMENTO, new BigDecimal("100.00"), BigDecimal.ZERO);

            when(pagamentoRepository.findByUuidReferencia(uuidRef)).thenReturn(Optional.empty());
            when(vendaRepository.findByIdWithLock(10L)).thenReturn(Optional.of(venda));
            when(pagamentoRepository.inserirIntencaoIdempotente(
                    uuidRef, 10L, "DINHEIRO", "PENDENTE", valor, BigDecimal.ZERO, false, false))
                    .thenReturn(Optional.of(205L));

            Long id = pagamentoService.criarIntencaoPagamento(10L, MetodoPagamento.DINHEIRO, valor, uuidRef);

            assertThat(id).isEqualTo(205L);
        }

        @Test
        @DisplayName("Deve criar intenção com status PENDENTE quando método NÃO exige processamento (PIX)")
        void deveCriarIntencaoComStatusPendenteQuandoMetodoNaoExigeProcessamento() {
            UUID uuidRef = UUID.randomUUID();
            BigDecimal valor = new BigDecimal("75.00");
            Venda venda = criarVenda(10L, StatusVenda.AGUARDANDO_PAGAMENTO);

            when(pagamentoRepository.findByUuidReferencia(uuidRef)).thenReturn(Optional.empty());
            when(vendaRepository.findByIdWithLock(10L)).thenReturn(Optional.of(venda));
            when(pagamentoRepository.inserirIntencaoIdempotente(
                    uuidRef, 10L, "PIX", "PENDENTE", valor, BigDecimal.ZERO, false, false))
                    .thenReturn(Optional.of(201L));

            Long id = pagamentoService.criarIntencaoPagamento(10L, MetodoPagamento.PIX, valor, uuidRef);

            assertThat(id).isEqualTo(201L);
            verify(pagamentoRepository).inserirIntencaoIdempotente(
                    uuidRef, 10L, "PIX", "PENDENTE", valor, BigDecimal.ZERO, false, false);
        }

        @Test
        @DisplayName("Deve criar intenção com status PROCESSANDO quando método for CARTAO_DEBITO")
        void deveCriarIntencaoComStatusProcessandoQuandoCartaoDebito() {
            UUID uuidRef = UUID.randomUUID();
            BigDecimal valor = new BigDecimal("89.90");
            Venda venda = criarVendaComSaldo(15L, StatusVenda.AGUARDANDO_PAGAMENTO, new BigDecimal("89.90"), BigDecimal.ZERO);

            when(pagamentoRepository.findByUuidReferencia(uuidRef)).thenReturn(Optional.empty());
            when(vendaRepository.findByIdWithLock(15L)).thenReturn(Optional.of(venda));
            when(pagamentoRepository.inserirIntencaoIdempotente(
                    uuidRef, 15L, "CARTAO_DEBITO", "PROCESSANDO", valor, BigDecimal.ZERO, false, false))
                    .thenReturn(Optional.of(202L));

            Long id = pagamentoService.criarIntencaoPagamento(15L, MetodoPagamento.CARTAO_DEBITO, valor, uuidRef);

            assertThat(id).isEqualTo(202L);
            verify(pagamentoRepository).inserirIntencaoIdempotente(
                    uuidRef, 15L, "CARTAO_DEBITO", "PROCESSANDO", valor, BigDecimal.ZERO, false, false);
        }

        @Test
        @DisplayName("Deve criar intenção com status PENDENTE quando método for DINHEIRO")
        void deveCriarIntencaoComStatusPendenteQuandoDinheiro() {
            UUID uuidRef = UUID.randomUUID();
            BigDecimal valor = new BigDecimal("120.00");
            Venda venda = criarVenda(16L, StatusVenda.AGUARDANDO_PAGAMENTO);

            when(pagamentoRepository.findByUuidReferencia(uuidRef)).thenReturn(Optional.empty());
            when(vendaRepository.findByIdWithLock(16L)).thenReturn(Optional.of(venda));
            when(pagamentoRepository.inserirIntencaoIdempotente(
                    uuidRef, 16L, "DINHEIRO", "PENDENTE", valor, BigDecimal.ZERO, false, false))
                    .thenReturn(Optional.of(203L));

            Long id = pagamentoService.criarIntencaoPagamento(16L, MetodoPagamento.DINHEIRO, valor, uuidRef);

            assertThat(id).isEqualTo(203L);
            verify(pagamentoRepository).inserirIntencaoIdempotente(
                    uuidRef, 16L, "DINHEIRO", "PENDENTE", valor, BigDecimal.ZERO, false, false);
        }

        @ParameterizedTest(name = "Etapa 1 com venda em status {0}")
        @EnumSource(value = StatusVenda.class, names = {"CONCLUIDA", "CANCELADA"})
        @DisplayName("Cenário 3 — Etapa 1 retorna ID existente ou ConflictException quando pagamento já existe mesmo com venda finalizada")
        void etapa1DeveRetornarIdOuConflitoMesmoComVendaFinalizada(StatusVenda statusFinal) {
            UUID uuidRef = UUID.randomUUID();
            Venda vendaFinalizada = criarVenda(10L, statusFinal);
            Pagamento existente = new Pagamento(vendaFinalizada, MetodoPagamento.PIX, new BigDecimal("100.00"), uuidRef);
            ReflectionTestUtils.setField(existente, "id", 299L);
            ReflectionTestUtils.setField(existente, "status", StatusPagamento.CONFIRMADO);
            when(pagamentoRepository.findByUuidReferencia(uuidRef)).thenReturn(Optional.of(existente));

            Long id = pagamentoService.criarIntencaoPagamento(10L, MetodoPagamento.PIX, new BigDecimal("100.00"), uuidRef);
            assertThat(id).isEqualTo(299L);

            assertThatThrownBy(() -> pagamentoService.criarIntencaoPagamento(10L, MetodoPagamento.PIX, new BigDecimal("99.00"), uuidRef))
                    .isInstanceOf(ConflictException.class);

            verifyNoInteractions(vendaRepository);
        }

        @ParameterizedTest(name = "Retry pós-lock com venda já em status {0}")
        @EnumSource(value = StatusVenda.class, names = {"CONCLUIDA", "CANCELADA"})
        @DisplayName("Cenário 3 — Reconsulta pós-lock (Etapa 3) deve retornar ID mesmo com venda já CONCLUIDA/CANCELADA se payload coincidir")
        void deveRetornarIdNaReconsultaPosLockMesmoComVendaFinalizada(StatusVenda statusPosLock) {
            UUID uuidRef = UUID.randomUUID();
            Venda venda = criarVenda(10L, statusPosLock);
            Pagamento concorrente = criarPagamento(300L, 10L, MetodoPagamento.PIX, StatusPagamento.CONFIRMADO,
                    new BigDecimal("100.00"), uuidRef);

            when(pagamentoRepository.findByUuidReferencia(uuidRef))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(concorrente));
            when(vendaRepository.findByIdWithLock(10L)).thenReturn(Optional.of(venda));

            Long id = pagamentoService.criarIntencaoPagamento(10L, MetodoPagamento.PIX, new BigDecimal("100.0"), uuidRef);

            assertThat(id).isEqualTo(300L);
            verify(pagamentoRepository, never()).inserirIntencaoIdempotente(any(), any(), any(), any(), any(), any(), anyBoolean(), anyBoolean());
        }

        @Test
        @DisplayName("Cenário 3 — Reconsulta pós-lock (Etapa 3) deve lançar ConflictException quando payload divergir")
        void deveLancarConflictExceptionNaReconsultaPosLockQuandoPayloadDivergir() {
            UUID uuidRef = UUID.randomUUID();
            Venda venda = criarVenda(10L, StatusVenda.CONCLUIDA);
            Pagamento concorrente = criarPagamento(300L, 10L, MetodoPagamento.PIX, StatusPagamento.CONFIRMADO,
                    new BigDecimal("100.00"), uuidRef);

            when(pagamentoRepository.findByUuidReferencia(uuidRef))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(concorrente));
            when(vendaRepository.findByIdWithLock(10L)).thenReturn(Optional.of(venda));

            assertThatThrownBy(() -> pagamentoService.criarIntencaoPagamento(10L, MetodoPagamento.CARTAO_CREDITO, new BigDecimal("100.00"), uuidRef))
                    .isInstanceOf(ConflictException.class);

            verify(pagamentoRepository, never()).inserirIntencaoIdempotente(any(), any(), any(), any(), any(), any(), anyBoolean(), anyBoolean());
        }

        @Test
        @DisplayName("Cenário 4 — Fallback da Etapa 6 deve retornar ID existente quando conflito no INSERT tiver payload igual")
        void deveRetornarIdNoFallbackEtapa6QuandoPayloadIgual() {
            UUID uuidRef = UUID.randomUUID();
            BigDecimal valor = new BigDecimal("50.0");
            Venda venda = criarVenda(10L, StatusVenda.AGUARDANDO_PAGAMENTO);
            Pagamento vencedor = criarPagamento(400L, 10L, MetodoPagamento.PIX, StatusPagamento.PENDENTE,
                    new BigDecimal("50.00"), uuidRef);

            when(pagamentoRepository.findByUuidReferencia(uuidRef))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(vencedor));
            when(vendaRepository.findByIdWithLock(10L)).thenReturn(Optional.of(venda));
            when(pagamentoRepository.inserirIntencaoIdempotente(
                    eq(uuidRef),
                    eq(10L),
                    eq("PIX"),
                    eq("PENDENTE"),
                    valorComEscalaEfetivaAteDuasCasas(valor),
                    eq(BigDecimal.ZERO),
                    eq(false),
                    eq(false)))
                    .thenReturn(Optional.empty());

            Long id = pagamentoService.criarIntencaoPagamento(10L, MetodoPagamento.PIX, valor, uuidRef);

            assertThat(id).isEqualTo(400L);
            verify(pagamentoRepository, times(3)).findByUuidReferencia(uuidRef);
        }

        @ParameterizedTest(name = "Fallback Etapa 6 divergindo em {0}")
        @CsvSource({
                "vendaId, 401, 99, PIX, 50.00",
                "metodo, 402, 10, DINHEIRO, 50.00",
                "valor, 403, 10, PIX, 60.00"
        })
        @DisplayName("Cenário 4 — Fallback da Etapa 6 deve lançar ConflictException quando conflito no INSERT divergir em vendaId, metodo ou valor")
        void deveLancarConflictExceptionNoFallbackEtapa6QuandoPayloadDivergir(
                String campoDivergente,
                Long pagamentoVencedorId,
                Long vendaVencedoraId,
                MetodoPagamento metodoVencedor,
                BigDecimal valorVencedor) {
            UUID uuidRef = UUID.randomUUID();
            BigDecimal valorSolicitado = new BigDecimal("50.00");
            Venda venda = criarVenda(10L, StatusVenda.AGUARDANDO_PAGAMENTO);
            Pagamento vencedor = criarPagamento(pagamentoVencedorId, vendaVencedoraId, metodoVencedor, StatusPagamento.PENDENTE,
                    valorVencedor, uuidRef);

            when(pagamentoRepository.findByUuidReferencia(uuidRef))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(vencedor));
            when(vendaRepository.findByIdWithLock(10L)).thenReturn(Optional.of(venda));
            when(pagamentoRepository.inserirIntencaoIdempotente(
                    eq(uuidRef),
                    eq(10L),
                    eq("PIX"),
                    eq("PENDENTE"),
                    valorComEscalaEfetivaAteDuasCasas(valorSolicitado),
                    eq(BigDecimal.ZERO),
                    eq(false),
                    eq(false)))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> pagamentoService.criarIntencaoPagamento(10L, MetodoPagamento.PIX, valorSolicitado, uuidRef))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("UUID de referência já utilizado por intenção de pagamento com dados divergentes.");

            InOrder inOrder = inOrder(pagamentoRepository, vendaRepository);
            inOrder.verify(pagamentoRepository).findByUuidReferencia(uuidRef);
            inOrder.verify(vendaRepository).findByIdWithLock(10L);
            inOrder.verify(pagamentoRepository).findByUuidReferencia(uuidRef);
            inOrder.verify(pagamentoRepository).inserirIntencaoIdempotente(
                    eq(uuidRef),
                    eq(10L),
                    eq("PIX"),
                    eq("PENDENTE"),
                    valorComEscalaEfetivaAteDuasCasas(valorSolicitado),
                    eq(BigDecimal.ZERO),
                    eq(false),
                    eq(false));
            inOrder.verify(pagamentoRepository).findByUuidReferencia(uuidRef);
            verifyNoMoreInteractions(pagamentoRepository, vendaRepository);
            verifyNoInteractions(eventPublisher);
        }

        private static BigDecimal valorComEscalaEfetivaAteDuasCasas(BigDecimal esperado) {
            return argThat(v -> v != null
                    && v.compareTo(esperado) == 0
                    && v.stripTrailingZeros().scale() <= 2);
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
            when(pagamentoRepository.findByIdWithLock(50L)).thenReturn(Optional.of(pagamento));

            pagamentoService.falharPagamentoWebhook(50L);

            assertThat(pagamento.getStatus()).isEqualTo(StatusPagamento.FALHOU);
            verify(pagamentoRepository, times(1)).save(pagamento);
        }

        @Test
        @DisplayName("Deve lançar BusinessException ao tentar falhar pagamento que não está em PROCESSANDO")
        void deveLancarBusinessExceptionAoFalharQuandoStatusNaoForProcessando() {
            Pagamento pagamento = criarPagamento(50L, 10L, MetodoPagamento.CARTAO_CREDITO, StatusPagamento.PENDENTE);
            when(pagamentoRepository.findByIdWithLock(50L)).thenReturn(Optional.of(pagamento));

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
            when(pagamentoRepository.findByIdWithLock(pagamentoId)).thenReturn(Optional.of(pagamento));

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
            when(pagamentoRepository.findByIdWithLock(pagamentoId)).thenReturn(Optional.of(pagamento));

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
            when(pagamentoRepository.findByIdWithLock(pagamentoId)).thenReturn(Optional.of(pagamento));

            assertThatThrownBy(() -> pagamentoService.falharPagamentoWebhook(pagamentoId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Transição inválida para falha de pagamento.");

            verify(pagamentoRepository, never()).save(any());
        }

        @Test
        @DisplayName("Deve lançar BusinessException ao tentar falhar pagamento inexistente")
        void deveLancarBusinessExceptionQuandoPagamentoNaoEncontradoAoFalhar() {
            when(pagamentoRepository.findByIdWithLock(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> pagamentoService.falharPagamentoWebhook(999L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Pagamento não encontrado para o ID: 999");

            verify(pagamentoRepository, never()).save(any());
        }
    }

    private static Pagamento criarPagamento(Long pagamentoId, Long vendaId, MetodoPagamento metodo, StatusPagamento status) {
        return criarPagamento(pagamentoId, vendaId, metodo, status, new BigDecimal("100.00"), UUID.randomUUID());
    }

    private static Pagamento criarPagamento(Long pagamentoId,
                                            Long vendaId,
                                            MetodoPagamento metodo,
                                            StatusPagamento status,
                                            BigDecimal valorEsperado,
                                            UUID uuidReferencia) {
        Venda venda = criarVenda(vendaId, StatusVenda.AGUARDANDO_PAGAMENTO);
        Pagamento pagamento = new Pagamento(venda, metodo, valorEsperado, uuidReferencia);
        ReflectionTestUtils.setField(pagamento, "id", pagamentoId);
        ReflectionTestUtils.setField(pagamento, "status", status);
        return pagamento;
    }

    private static Venda criarVenda(Long vendaId, StatusVenda status) {
        return criarVendaComSaldo(vendaId, status, new BigDecimal("100.00"), BigDecimal.ZERO);
    }

    private static Venda criarVendaComSaldo(Long vendaId, StatusVenda status, BigDecimal valorTotal, BigDecimal valorPago) {
        Venda venda = new Venda();
        ReflectionTestUtils.setField(venda, "id", vendaId);
        ReflectionTestUtils.setField(venda, "status", status);
        ReflectionTestUtils.setField(venda, "valorTotal", valorTotal);
        ReflectionTestUtils.setField(venda, "valorPago", valorPago);
        return venda;
    }
}
