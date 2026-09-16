package com.pkm.controller;

import com.pkm.client.ResearchApiClient;
import com.pkm.client.SearchIndexClient;
import com.pkm.client.TranslationClient;
import com.pkm.model.Paper;
import com.pkm.service.PaperService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import org.springframework.beans.factory.annotation.Value;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/papers")
@RequiredArgsConstructor
public class PaperController {

    private static final int MAX_PER_SOURCE = 50;   // 소스 하나당 가져올 최대 건수
    private static final int MAX_RESULTS    = 300;  // 재랭킹 후 내려줄 최대 건수

    private final ResearchApiClient researchApiClient;
    private final SearchIndexClient searchIndexClient;
    private final TranslationClient translationClient;
    private final PaperService paperService;
    private final WebClient.Builder webClientBuilder;

    @Value("${app.ai-service-url}")
    private String aiServiceUrl;

    // ── 1. 논문 검색 ───────────────────────────────────────
    // 한글이 섞인 검색어는 영어로 변환 후 검색 API에 전달
    // (Semantic Scholar/OpenAlex/arXiv 등은 영어 쿼리에서만 정상 동작)
    @GetMapping("/search")
    public ResponseEntity<List<Paper>> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "50") int perSource
    ) {
        int fetchPerSource = Math.min(perSource, MAX_PER_SOURCE);

        List<Paper> papers = translationClient.toEnglishIfNeeded(q)
                .flatMap(query -> reactor.core.publisher.Mono.zip(
                                searchIndexClient.searchAll(query, fetchPerSource),
                                researchApiClient.searchAll(query, fetchPerSource))
                        // 각 API가 자기 랭킹대로 준 결과를 합친 뒤, 전체를 기준으로 다시 정렬한다.
                        .map(both -> searchIndexClient.rerank(
                                merge(both.getT1(), both.getT2()), query, MAX_RESULTS)))
                .defaultIfEmpty(List.of())
                .block();

        log.info("검색 '{}' — 최종 {}건", q, papers != null ? papers.size() : 0);
        return ResponseEntity.ok(papers != null ? papers : List.of());
    }

    /** externalId 기준으로 중복만 제거해 합친다. 순위는 이후 rerank가 다시 매긴다. */
    private List<Paper> merge(List<Paper> indexed, List<Paper> extra) {
        Map<String, Paper> unique = new LinkedHashMap<>();
        for (Paper paper : indexed) unique.putIfAbsent(paper.getExternalId(), paper);
        for (Paper paper : extra)   unique.putIfAbsent(paper.getExternalId(), paper);
        return new ArrayList<>(unique.values());
    }

    // ── 2. 요약 보기 — SSE 스트리밍 ───────────────────────
    @PostMapping(value = "/{paperId}/summarize",
                 produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> summarize(
            @PathVariable String paperId,
            @RequestBody Map<String, String> body
    ) {
        String title     = body.getOrDefault("title", "");
        String abstract_ = body.getOrDefault("abstract", "");

        return webClientBuilder.build()
                .post()
                .uri(aiServiceUrl + "/summarize/stream")
                .bodyValue(Map.of(
                        "paper_id", paperId,
                        "title",    title,
                        "abstract", abstract_
                ))
                .retrieve()
                .bodyToFlux(String.class);
    }

    // ── 3. 저장 ───────────────────────────────────────────
    @PostMapping("/{paperId}/save")
    public ResponseEntity<Map<String, Object>> save(
            @PathVariable String paperId,
            @RequestBody SaveRequest req
    ) {
        Map<String, Object> result = paperService.savePaper(paperId, req)
                .defaultIfEmpty(Map.of("ok", false))
                .block();
        return ResponseEntity.ok(result != null ? result : Map.of("ok", false));
    }

    // ── 4. Notion 메모 목록 ────────────────────────────────
    @GetMapping("/notion/pages")
    public ResponseEntity<List<Map<String, Object>>> notionPages() {
        List<Map<String, Object>> pages = paperService.listNotionPages()
                .defaultIfEmpty(List.of())
                .block();
        return ResponseEntity.ok(pages != null ? pages : List.of());
    }

    // ── 4-1. Notion 색인 목록 (저장 시 선택용) ─────────────
    @GetMapping("/notion/index")
    public ResponseEntity<List<Map<String, Object>>> notionIndexPages() {
        List<Map<String, Object>> pages = paperService.listNotionIndexPages()
                .defaultIfEmpty(List.of())
                .block();
        return ResponseEntity.ok(pages != null ? pages : List.of());
    }

    // ── 5. Notion 메모 선택 → 논문 추천 ───────────────────
    @PostMapping("/notion/recommend")
    public ResponseEntity<List<Map<String, Object>>> recommend(
            @RequestBody Map<String, String> body
    ) {
        String notionPageId = body.get("notionPageId");
        String title        = body.getOrDefault("title", "");
        String content      = body.getOrDefault("content", "");

        List<Map<String, Object>> results = paperService
                .recommendFromNotion(notionPageId, title, content)
                .defaultIfEmpty(List.of())
                .block();
        return ResponseEntity.ok(results != null ? results : List.of());
    }

    public record SaveRequest(
            String title,
            String summary,
            String fullTextUrl,
            String source,
            String externalId,
            List<String> tags,
            List<String> linkedNotionIds,
            List<String> indexNotionIds
    ) {}
}
