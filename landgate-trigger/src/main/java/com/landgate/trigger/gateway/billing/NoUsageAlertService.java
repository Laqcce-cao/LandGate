package com.landgate.trigger.gateway.billing;

import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.domain.auth.adapter.port.IEmailPort;
import com.landgate.domain.group.model.entity.GroupEntity;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class NoUsageAlertService {

    private final IEmailPort emailPort;
    private final boolean enabled;
    private final List<String> recipients;
    private final int threshold;
    private final Duration window;
    private final Duration cooldown;
    private final Map<String, AccountNoUsageState> states = new ConcurrentHashMap<>();

    public NoUsageAlertService(IEmailPort emailPort,
                               @Value("${landgate.gateway.no-usage-alert.enabled:false}") boolean enabled,
                               @Value("${landgate.gateway.no-usage-alert.recipients:${LANDGATE_ADMIN_EMAIL:}}") String recipients,
                               @Value("${landgate.gateway.no-usage-alert.threshold:3}") int threshold,
                               @Value("${landgate.gateway.no-usage-alert.window-seconds:300}") long windowSeconds,
                               @Value("${landgate.gateway.no-usage-alert.cooldown-seconds:1800}") long cooldownSeconds) {
        this.emailPort = emailPort;
        this.enabled = enabled;
        this.recipients = parseRecipients(recipients);
        this.threshold = Math.max(1, threshold);
        this.window = Duration.ofSeconds(Math.max(1, windowSeconds));
        this.cooldown = Duration.ofSeconds(Math.max(1, cooldownSeconds));
    }

    public void onNoUsage(String model,
                          String platform,
                          Long userId,
                          Long apiKeyId,
                          AccountEntity account,
                          GroupEntity group,
                          boolean stream,
                          boolean clientDisconnected,
                          long durationMs,
                          HttpServletRequest request,
                          String requestId,
                          String reason) {
        if (!enabled || recipients.isEmpty() || account == null || account.getId() == null) {
            return;
        }

        String reasonType = extractReasonType(reason);
        String key = account.getId() + ":" + reasonType;
        AccountNoUsageState state = states.computeIfAbsent(key, ignored -> new AccountNoUsageState());
        AlertSnapshot snapshot;
        Instant now = Instant.now();
        synchronized (state) {
            Instant cutoff = now.minus(window);
            while (!state.events.isEmpty() && state.events.peekFirst().isBefore(cutoff)) {
                state.events.removeFirst();
            }
            state.events.addLast(now);
            int count = state.events.size();
            if (count < threshold) {
                return;
            }
            if (state.lastAlertAt != null && state.lastAlertAt.plus(cooldown).isAfter(now)) {
                return;
            }
            state.lastAlertAt = now;
            snapshot = new AlertSnapshot(count, state.events.peekFirst(), now);
        }

        sendAlert(snapshot, reasonType, model, platform, userId, apiKeyId, account, group,
                stream, clientDisconnected, durationMs, request, requestId, reason);
    }

    private void sendAlert(AlertSnapshot snapshot,
                           String reasonType,
                           String model,
                           String platform,
                           Long userId,
                           Long apiKeyId,
                           AccountEntity account,
                           GroupEntity group,
                           boolean stream,
                           boolean clientDisconnected,
                           long durationMs,
                           HttpServletRequest request,
                           String requestId,
                           String reason) {
        String subject = "[LandGate] " + reasonType + " alert: account " + account.getId();
        String html = buildHtml(snapshot, reasonType, model, platform, userId, apiKeyId, account, group,
                stream, clientDisconnected, durationMs, request, requestId, reason);

        for (String recipient : recipients) {
            try {
                emailPort.sendAlertEmail(recipient, subject, html);
            } catch (Exception e) {
                log.warn("[{}] Failed to send no-usage alert email: recipient={}, account_id={}",
                        requestId, recipient, account.getId(), e);
            }
        }
    }

    private String buildHtml(AlertSnapshot snapshot,
                             String reasonType,
                             String model,
                             String platform,
                             Long userId,
                             Long apiKeyId,
                             AccountEntity account,
                             GroupEntity group,
                             boolean stream,
                             boolean clientDisconnected,
                             long durationMs,
                             HttpServletRequest request,
                             String requestId,
                             String reason) {
        return """
                <div style="max-width:760px;margin:0 auto;font-family:Arial,sans-serif;color:#111827;">
                  <h2>LandGate %s alert</h2>
                  <p>Account <b>%s</b> triggered <b>%d</b> %s events within %d seconds.</p>
                  <table style="border-collapse:collapse;width:100%%;">
                    %s
                    %s
                    %s
                    %s
                    %s
                    %s
                    %s
                    %s
                    %s
                    %s
                    %s
                    %s
                    %s
                    %s
                    %s
                    %s
                  </table>
                </div>
                """.formatted(
                escape(reasonType),
                escape(account.getName()),
                snapshot.count(),
                escape(reasonType),
                window.toSeconds(),
                row("Account ID", account.getId()),
                row("Account Name", account.getName()),
                row("Account Platform", platform),
                row("Account Type", account.getType()),
                row("Model", model),
                row("User ID", userId),
                row("API Key ID", apiKeyId),
                row("Group ID", group != null ? group.getId() : null),
                row("Request ID", requestId),
                row("Reason", reason),
                row("Stream", stream),
                row("Client Disconnected", clientDisconnected),
                row("Duration Ms", durationMs),
                row("Remote Addr", request != null ? request.getRemoteAddr() : null),
                row("First Event At", snapshot.firstEventAt()),
                row("Alert At", snapshot.alertAt()));
    }

    private static String extractReasonType(String reason) {
        if (reason == null || reason.isBlank()) {
            return "usage_unknown";
        }
        int semicolon = reason.indexOf(';');
        String type = semicolon >= 0 ? reason.substring(0, semicolon) : reason;
        type = type.trim();
        return type.isEmpty() ? "usage_unknown" : type;
    }

    private static String row(String label, Object value) {
        return """
                <tr>
                  <td style="border:1px solid #E5E7EB;padding:8px;background:#F9FAFB;width:180px;">%s</td>
                  <td style="border:1px solid #E5E7EB;padding:8px;">%s</td>
                </tr>
                """.formatted(escape(label), escape(value));
    }

    private static String escape(Object value) {
        if (value == null) return "";
        return value.toString()
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static List<String> parseRecipients(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }

    private static class AccountNoUsageState {
        private final Deque<Instant> events = new ArrayDeque<>();
        private Instant lastAlertAt;
    }

    private record AlertSnapshot(int count, Instant firstEventAt, Instant alertAt) {
    }
}
