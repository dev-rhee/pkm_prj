package com.pkm.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/graph")
@RequiredArgsConstructor
public class GraphController {

    private final WebClient.Builder webClientBuilder;

    @Value("${app.ai-service-url}")
    private String aiServiceUrl;

    // ── 전체 그래프 (노드 + 엣지) ─────────────────────────
    // 프론트 D3.js가 이 데이터로 시각화
    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> getGraph(
            @RequestParam(defaultValue = "50") int limit
    ) {
        return webClientBuilder.build()
                .get()
                .uri(aiServiceUrl + "/graph?limit=" + limit)
                .retrieve()
                .bodyToMono(Map.class)
                .map(data -> ResponseEntity.ok((Map<String, Object>) data))
                .onErrorReturn(ResponseEntity.ok(Map.of("nodes", List.of(), "edges", List.of())));
    }

    // ── 특정 노드 중심 서브그래프 ─────────────────────────
    @GetMapping("/node/{nodeId}")
    public Mono<ResponseEntity<Map<String, Object>>> getNodeGraph(
            @PathVariable String nodeId
    ) {
        return webClientBuilder.build()
                .get()
                .uri(aiServiceUrl + "/graph/node/" + nodeId)
                .retrieve()
                .bodyToMono(Map.class)
                .map(data -> ResponseEntity.ok((Map<String, Object>) data))
                .onErrorReturn(ResponseEntity.ok(Map.of("nodes", List.of(), "edges", List.of())));
    }
}
