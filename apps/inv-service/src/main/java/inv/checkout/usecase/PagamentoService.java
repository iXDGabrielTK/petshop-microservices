/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.checkout.usecase;

import common.exception.BusinessException;
import common.exception.ConflictException;
import common.exception.ResourceNotFoundException;
import inv.checkout.infrastructure.messaging.event.PagamentoConfirmadoEvent;
import inv.shared.domain.MetodoPagamento;
import inv.checkout.domain.model.Pagamento;
import inv.checkout.domain.model.StatusPagamento;
import inv.checkout.domain.model.Venda;
import inv.checkout.infrastructure.persistence.PagamentoRepository;
import inv.checkout.infrastructure.persistence.VendaRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class PagamentoService {

    private static final BigDecimal VALOR_MAXIMO_INTENCAO = new BigDecimal("99999999.99");

    private final PagamentoRepository pagamentoRepository;
    private final VendaRepository vendaRepository;
    private final ApplicationEventPublisher eventPublisher;

    public PagamentoService(PagamentoRepository pagamentoRepository, VendaRepository vendaRepository, ApplicationEventPublisher eventPublisher) {
        this.pagamentoRepository = pagamentoRepository;
        this.vendaRepository = vendaRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public Long criarIntencaoPagamento(Long vendaId,
                                       MetodoPagamento metodo,
                                       BigDecimal valor,
                                       UUID uuidReferencia) {

        if (metodo == null) {
            throw new BusinessException("Método de pagamento é obrigatório.");
        }
        if (uuidReferencia == null) {
            throw new BusinessException("UUID de referência é obrigatório.");
        }

        if (valor == null
                || valor.signum() <= 0
                || valor.compareTo(VALOR_MAXIMO_INTENCAO) > 0
                || valor.stripTrailingZeros().scale() > 2) {
            throw new BusinessException(
                    "Valor inválido para intenção de pagamento: deve ser positivo, com no máximo 8 dígitos inteiros e 2 casas decimais.");
        }

        // 1. Consulta rápida idempotente antes de adquirir lock na venda
        Optional<Pagamento> pagamentoExistenteRapido = pagamentoRepository.findByUuidReferencia(uuidReferencia);
        if (pagamentoExistenteRapido.isPresent()) {
            return validarIntencaoExistente(pagamentoExistenteRapido.get(), vendaId, metodo, valor);
        }

        // 2. Lock pessimista na venda (serializa com conclusão, expiração e cancelamento)
        Venda venda = vendaRepository.findByIdWithLock(vendaId)
                .orElseThrow(() -> new ResourceNotFoundException("Venda não encontrada"));

        // 3. Reconsulta pós-lock antes da validação de status da venda
        Optional<Pagamento> pagamentoExistentePosLock = pagamentoRepository.findByUuidReferencia(uuidReferencia);
        if (pagamentoExistentePosLock.isPresent()) {
            return validarIntencaoExistente(pagamentoExistentePosLock.get(), vendaId, metodo, valor);
        }

        // 4. Validação do status da venda e do saldo devedor para métodos eletrônicos
        if (!venda.getStatus().aceitaPagamento()) {
            throw new ConflictException("A venda não aceita pagamentos no status: " + venda.getStatus());
        }

        if (metodo.isEletronico()) {
            BigDecimal valorTotal = venda.getValorTotal() != null ? venda.getValorTotal() : BigDecimal.ZERO;
            BigDecimal valorPago = venda.getValorPago() != null ? venda.getValorPago() : BigDecimal.ZERO;
            BigDecimal emVoo = Optional.ofNullable(
                    pagamentoRepository.somarConfirmadosNaoContabilizados(venda.getId())).orElse(BigDecimal.ZERO);
            BigDecimal saldoDevedor = valorTotal.subtract(valorPago).subtract(emVoo);
            if (valor.compareTo(saldoDevedor) > 0) {
                throw new BusinessException(
                        "Valor da intenção excede o saldo devedor atual da venda (" + saldoDevedor + ").");
            }
        }

        // 5. Inserção atômica idempotente usando o domínio como fonte única dos valores iniciais
        Pagamento pagamento = new Pagamento(venda, metodo, valor, uuidReferencia);
        if (metodo.isExigeProcessamento()) {
            pagamento.processar();
        }

        Optional<Long> idInserido = pagamentoRepository.inserirIntencaoIdempotente(
                pagamento.getUuidReferencia(),
                venda.getId(),
                pagamento.getMetodo().name(),
                pagamento.getStatus().name(),
                pagamento.getValorEsperado(),
                pagamento.getValorRecebido(),
                pagamento.isExcedente(),
                pagamento.isContabilizado()
        );
        if (idInserido.isPresent()) {
            return idInserido.get();
        }

        // 6. Fallback em conflito concorrente no índice único de uuid_referencia
        Pagamento pagamentoConflitante = pagamentoRepository.findByUuidReferencia(uuidReferencia)
                .orElseThrow(() -> new ConflictException(
                        "Conflito de idempotência ao registrar intenção de pagamento para o UUID: " + uuidReferencia));
        return validarIntencaoExistente(pagamentoConflitante, vendaId, metodo, valor);
    }

    private Long validarIntencaoExistente(Pagamento existente, Long vendaId, MetodoPagamento metodo, BigDecimal valor) {
        Long vendaExistenteId = existente.getVenda() != null ? existente.getVenda().getId() : null;
        if (!Objects.equals(vendaExistenteId, vendaId)
                || existente.getMetodo() != metodo
                || existente.getValorEsperado() == null
                || existente.getValorEsperado().compareTo(valor) != 0) {
            throw new ConflictException(
                    "UUID de referência já utilizado por intenção de pagamento com dados divergentes.");
        }
        return existente.getId();
    }

    @Transactional(readOnly = true)
    public StatusPagamento buscarStatusPagamento(Long pagamentoId) {
        return pagamentoRepository.findById(pagamentoId)
                .map(Pagamento::getStatus)
                .orElseThrow(() -> new BusinessException("Pagamento não encontrado"));
    }

    @Transactional
    public void confirmarPagamentoWebhook(Long pagamentoId, BigDecimal valor, String nsu) {
        // Serializa a decisão e mantém o lock até o commit do pagamento e da outbox.
        // Não adquire lock na venda: consumidor e cancelamento partem da venda.
        Pagamento pagamento = pagamentoRepository.findByIdWithLock(pagamentoId)
                .orElseThrow(() -> new BusinessException("Pagamento não encontrado para o ID: " + pagamentoId));

        boolean confirmado = pagamento.confirmar(valor, nsu);
        if (!confirmado) {
            return;
        }

        pagamentoRepository.save(pagamento);

        eventPublisher.publishEvent(new PagamentoConfirmadoEvent(pagamento.getVenda().getId(), pagamento.getId()));
    }

    @Transactional
    public void falharPagamentoWebhook(Long pagamentoId) {
        Pagamento pagamento = pagamentoRepository.findByIdWithLock(pagamentoId)
                .orElseThrow(() -> new BusinessException("Pagamento não encontrado para o ID: " + pagamentoId));

        pagamento.falhar();
        pagamentoRepository.save(pagamento);
    }
}
