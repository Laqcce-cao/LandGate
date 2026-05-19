package com.landgate.infrastructure.captcha;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.types.exception.CaptchaVerificationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@Slf4j
@Service
public class CaptchaService {

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${landgate.captcha.turnstile-secret-key:}")
    private String turnstileSecretKey;

    @Value("${landgate.captcha.enabled:false}")
    private boolean captchaEnabled;

    private static final String TURNSTILE_VERIFY_URL =
            "https://challenges.cloudflare.com/turnstile/v0/siteverify";

    public void verify(String token, String remoteIp) {
        if (!captchaEnabled) {
            log.debug("Captcha verification disabled, skipping");
            return;
        }
        if (token == null || token.isBlank()) {
            throw new CaptchaVerificationException("Captcha token is required");
        }
        try {
            String body = "secret=" + turnstileSecretKey +
                    "&response=" + token +
                    "&remoteip=" + remoteIp;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TURNSTILE_VERIFY_URL))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            JsonNode json = objectMapper.readTree(response.body());
            if (!json.get("success").asBoolean()) {
                log.warn("Captcha verification failed: {}", json);
                throw new CaptchaVerificationException("Captcha verification failed");
            }
        } catch (CaptchaVerificationException e) {
            throw e;
        } catch (Exception e) {
            log.error("Captcha verification error", e);
            throw new CaptchaVerificationException("Captcha verification error");
        }
    }
}
