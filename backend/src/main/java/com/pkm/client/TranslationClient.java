package com.pkm.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * 검색어 전처리 — 한국어가 섞인 검색어를 영어로 변환
 * Semantic Scholar / OpenAlex / arXiv 등은 영어 쿼리에서만 정상 동작하므로,
 * 논문 검색 API 호출 전에 거친다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TranslationClient {

    private static final Pattern KOREAN = Pattern.compile("[\\uAC00-\\uD7A3]");

    private final WebClient.Builder webClientBuilder;

    @Value("${app.ai-service-url}")
    private String aiServiceUrl;

    /** 한글이 섞인 검색어만 영어로 변환하고, 아니면 원본을 그대로 반환 */
    @SuppressWarnings("unchecked")
    public Mono<String> toEnglishIfNeeded(String query) {
        if (query == null || query.isBlank() || !KOREAN.matcher(query).find()) {
            return Mono.just(query);
        }

        return webClientBuilder.build()
                .post()
                .uri(aiServiceUrl + "/translate/query")
                .bodyValue(Map.of("query", query))
                .retrieve()
                .bodyToMono(Map.class)
                .map(resp -> {
                    Object translated = resp.get("translated");
                    return (translated instanceof String s && !s.isBlank()) ? s : query;
                })
                .doOnNext(translated -> log.info("검색어 영어 변환: '{}' → '{}'", query, translated))
                .onErrorResume(e -> {
                    log.warn("검색어 번역 실패, 원본 쿼리 사용: {}", e.getMessage());
                    return Mono.just(query);
                });
    }
}
