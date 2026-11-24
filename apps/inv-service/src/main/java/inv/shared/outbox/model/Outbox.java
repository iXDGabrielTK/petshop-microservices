/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.shared.outbox.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "outbox")
@Getter
@Setter
@NoArgsConstructor
public class Outbox {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_QUARANTINED = "QUARANTINED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String exchange;

    @Column(nullable = false)
    private String routingKey;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(nullable = false)
    private String eventType;

    @Column(nullable = false, columnDefinition = "INT DEFAULT 1")
    private int version = 1;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "status", nullable = false, length = 20)
    private String status = STATUS_PENDING;

    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @Column(name = "last_attempt_at")
    private LocalDateTime lastAttemptAt;

    @Column(name = "last_error_type", length = 64)
    private String lastErrorType;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "quarantined_at")
    private LocalDateTime quarantinedAt;

    @Column(name = "replayed_at")
    private LocalDateTime replayedAt;

    @Column(name = "replay_count", nullable = false)
    private int replayCount = 0;

    public void setStatus(String status) {
        this.status = (status == null || status.isBlank()) ? STATUS_PENDING : status;
    }

    @PrePersist
    void garantirPadroes() {
        if (this.status == null || this.status.isBlank()) {
            this.status = STATUS_PENDING;
        }
    }
}
