package com.pkm.service;

import com.pkm.client.ResearchApiClient;
import com.pkm.client.SearchIndexClient;
import com.pkm.client.NotionClient;
import com.pkm.controller.PaperController.SaveRequest;
import com.pkm.model.Paper;
import com.pkm.model.SavedPaper;
import com.pkm.repository.PaperRepository;
import com.pkm.repository.SavedPaperRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaperService {

    private final PaperRepository paperRepository;
    private final SavedPaperRepository savedPaperRepository;
    private final NotionClient notionClient;
    private final SearchIndexClient searchIndexClient;
    private final ResearchApiClient researchApiClient;
    private final JdbcTemplate jdbcTemplate;
    private final WebClient.Builder webClientBuilder;

    @Value("${app.ai-service-url}")
    private String aiServiceUrl;

    // ════════════════════════════════════════════════════
    // 저장 버튼 클릭 — 핵심 흐름
    // 1. Paper DB 저장
    // 2. 임베딩 생성 → pgvector 저장
    // 3. Notion에 요약 페이지 생성
    // 4. 관련 메모와 Notion 링크
    // 5. thought_edges 업데이트
    // ════════════════════════════════════════════════════
    public Mono<Map<String, Object>> savePaper(String externalId, SaveRequest req) {

        // 1. Paper upsert
        Paper paper = paperRepository.findByExternalId(externalId)
                .orElseGet(() -> paperRepository.save(Paper.builder()
                        .externalId(externalId)
                        .source(req.source())
                        .title(req.title())
                        .fullTextUrl(req.fullTextUrl())
                        .abstractText("")
                        .build()));

        // 2. 임베딩 생성 (요약본 기준)
        return embedText(req.summary())
                .flatMap(embedding -> {
                    updatePaperEmbedding(paper.getId().toString(), embedding);

                    // 3. Notion 저장
                    NotionClient.SaveRequest notionReq = new NotionClient.SaveRequest(
                            req.title(),
                            req.summary(),
                            req.fullTextUrl(),
                            req.tags() != null ? req.tags() : List.of(),
                            req.linkedNotionIds() != null ? req.linkedNotionIds() : List.of(),
                            req.indexNotionIds() != null ? req.indexNotionIds() : List.of()
                    );

                    return notionClient.savePaperSummary(notionReq)
                            .flatMap(notionPageId -> {

                                // 4. DB에 저장 기록
                                SavedPaper saved = SavedPaper.builder()
                                        .paper(paper)
                                        .notionPageId(notionPageId)
                                        .linkedNotionIds(req.linkedNotionIds())
                                        .build();
                                savedPaperRepository.save(saved);

                                // 5. 관련 메모 Notion 링크 + thought_edges
                                List<Mono<Void>> linkTasks = (req.linkedNotionIds() != null
                                        ? req.linkedNotionIds() : List.<String>of())
                                        .stream()
                                        // 메모 제목을 같이 넘긴다. 임베딩된 적 없는 메모는 notion_pages에
                                        // 행이 없어서, ai-service가 stub 행을 만들 때 제목이 필요하다.
                                        .map(memoId -> notionClient.getPageTitle(memoId)
                                                .flatMap(memoTitle -> notionClient
                                                        .linkToMemo(notionPageId, memoId)
                                                        .then(createEdge(
                                                                paper.getId().toString(), "paper",
                                                                memoId, "notion", memoTitle,
                                                                0.85f, "saved"
                                                        ))))
                                        .toList();

                                // 5-1. 색인 → thought_edges. Notion 페이지의 relation에만 넣으면
                                // 그래프가 색인을 알 수 없으므로 우리 쪽에도 엣지로 남긴다.
                                List<Mono<Void>> indexTasks = (req.indexNotionIds() != null
                                        ? req.indexNotionIds() : List.<String>of())
                                        .stream()
                                        .map(indexId -> notionClient.getPageTitle(indexId)
                                                .flatMap(indexTitle -> createEdge(
                                                        paper.getId().toString(), "paper",
                                                        indexId, "notion", indexTitle,
                                                        1.0f, "index"
                                                )))
                                        .toList();

                                // 6. 재발견 탐색 (비동기, 저장 완료 후)
                                Mono<Map<String, Object>> rediscoverMono =
                                        rediscover(paper.getId().toString(), embedding)
                                        .onErrorReturn(Map.of("discoveries", List.of(), "count", 0));

                                return Mono.when(linkTasks)
                                        .then(Mono.when(indexTasks))
                                        .then(rediscoverMono)
                                        .map(rediscoveries -> Map.of(
                                                "ok",             true,
                                                "notionPageId",   notionPageId,
                                                "paperId",        paper.getId().toString(),
                                                "rediscoveries",  rediscoveries
                                        ));
                            });
                });
    }

    // ════════════════════════════════════════════════════
    // 재발견 탐색
    // ════════════════════════════════════════════════════
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> rediscover(String paperId, List<Double> embedding) {
        return webClientBuilder.build()
                .post()
                .uri(aiServiceUrl + "/rediscover")
                .bodyValue(Map.of(
                        "paper_id",            paperId,
                        "embedding",           embedding,
                        "top_k",               5,
                        "similarity_threshold", 0.6
                ))
                .retrieve()
                .bodyToMono(Map.class)
                .map(r -> (Map<String, Object>) r)
                .onErrorReturn(Map.of("discoveries", List.of(), "count", 0));
    }

    // ════════════════════════════════════════════════════
    // Notion 메모 선택 → 논문 추천
    // ════════════════════════════════════════════════════
    public Mono<List<Map<String, Object>>> recommendFromNotion(
            String notionPageId, String title, String content
    ) {
        // 프론트는 제목만 보내므로 본문은 여기서 Notion에서 읽는다. 제목만으로 임베딩하면
        // "ADHD" 같은 한 단어짜리 메모는 사실상 주제를 담지 못한다.
        Mono<String> resolvedContent = (content != null && !content.isBlank())
                ? Mono.just(content)
                : notionClient.getPageContent(notionPageId)
                        .map(page -> (String) page.getOrDefault("content", ""))
                        .onErrorResume(e -> {
                            log.warn("메모 본문 조회 실패, 제목만 사용: {}", e.getMessage());
                            return Mono.just("");
                        });

        return resolvedContent.flatMap(body -> recommendWithContent(notionPageId, title, body));
    }

    @SuppressWarnings("unchecked")
    private Mono<List<Map<String, Object>>> recommendWithContent(
            String notionPageId, String title, String content
    ) {
        // DB 임베딩 검색
        Mono<List<Map<String, Object>>> dbMono = webClientBuilder.build()
                .post()
                .uri(aiServiceUrl + "/notion/embed")
                .bodyValue(Map.of("notion_page_id", notionPageId, "title", title, "content", content))
                .retrieve()
                .bodyToMono(Map.class)
                .flatMap(resp -> {
                    List<Double> embedding = (List<Double>) resp.get("embedding");
                    return webClientBuilder.build()
                            .post()
                            .uri(aiServiceUrl + "/similar")
                            .bodyValue(Map.of(
                                    "embedding",            embedding,
                                    "top_k",                5,
                                    "target",               "papers",
                                    "similarity_threshold", 0.6,
                                    "use_mmr",              true
                            ))
                            .retrieve()
                            .bodyToMono(Map.class)
                            .map(r -> ((List<Map<String, Object>>) r.getOrDefault("results", List.of()))
                                    .stream().map(p -> {
                                        Map<String, Object> m = new java.util.HashMap<>();
                                        m.put("externalId",   p.get("external_id"));
                                        m.put("source",       p.get("source"));
                                        m.put("title",        p.get("title"));
                                        m.put("fullTextUrl",  p.getOrDefault("full_text_url", ""));
                                        m.put("abstractText", "");
                                        m.put("authors",      List.of());
                                        return m;
                                    }).collect(java.util.stream.Collectors.toList()));
                })
                .onErrorReturn(List.of());

        // API 키워드 검색 — 영문 키워드가 있을 때만 실행 (한글 제목으로 검색하면 엉뚱한 결과)
        String searchQuery = extractEnglishQuery(title, content);
        Mono<List<Map<String, Object>>> apiMono = searchQuery == null
                ? Mono.just(List.of())
                : searchIndexClient.searchAll(searchQuery, 5)
                        .flatMap(papers -> papers.size() < 5
                                ? researchApiClient.searchAll(searchQuery, 5)
                                : Mono.just(papers))
                        .map(papers -> papers.stream().map(this::paperToMap).toList())
                        .onErrorReturn(List.of());

        return Mono.zip(dbMono, apiMono).map(tuple -> {
            List<Map<String, Object>> dbResults  = tuple.getT1();
            List<Map<String, Object>> apiResults = tuple.getT2();

            // DB 결과 먼저, API 결과 중 externalId 중복 제거 후 합산
            Map<String, Map<String, Object>> merged = new java.util.LinkedHashMap<>();
            dbResults.forEach(p -> merged.put((String) p.get("external_id"), p));
            apiResults.forEach(p -> merged.putIfAbsent((String) p.get("externalId"), p));

            log.info("추천 결과 — DB: {}건, API: {}건(쿼리: {}), 최종: {}건",
                    dbResults.size(), apiResults.size(), searchQuery != null ? searchQuery : "스킵", merged.size());
            return new java.util.ArrayList<>(merged.values());
        });
    }

    // ════════════════════════════════════════════════════
    // Notion 메모 목록
    // ════════════════════════════════════════════════════
    public Mono<List<Map<String, Object>>> listNotionPages() {
        return notionClient.listPages();
    }

    public Mono<List<Map<String, Object>>> listNotionIndexPages() {
        return notionClient.listIndexPages();
    }

    // ── 헬퍼 ──────────────────────────────────────────
    private Mono<List<Double>> embedText(String text) {
        return webClientBuilder.build()
                .post()
                .uri(aiServiceUrl + "/embed")
                .bodyValue(Map.of("text", text))
                .retrieve()
                .bodyToMono(Map.class)
                .map(r -> (List<Double>) r.get("embedding"));
    }

    private void updatePaperEmbedding(String paperId, List<Double> embedding) {
        String vector = "[" + embedding.stream()
                .map(String::valueOf)
                .reduce((a, b) -> a + "," + b)
                .orElse("") + "]";

        jdbcTemplate.update(
                "UPDATE papers SET embedding = ?::vector WHERE id = ?::uuid",
                vector,
                paperId
        );
    }

    /**
     * title + content에서 영문 단어만 추출해 API 검색 쿼리로 사용.
     * 영문이 없으면 null 반환 → API 검색 스킵 (한글 제목으로 영문 API 검색 방지).
     */
    private String extractEnglishQuery(String title, String content) {
        String combined = (title != null ? title : "") + " " + (content != null ? content : "");
        // 영문 알파벳으로만 이루어진 단어 추출 (2자 이상, 불용어 제외)
        java.util.Set<String> stopwords = java.util.Set.of(
                "the", "a", "an", "and", "or", "of", "in", "to", "is", "are",
                "was", "were", "for", "on", "at", "by", "with", "this", "that"
        );
        List<String> words = Arrays.stream(combined.split("[^a-zA-Z]+"))
                .filter(w -> w.length() >= 3)
                .filter(w -> !stopwords.contains(w.toLowerCase()))
                .distinct()
                .limit(5)
                .collect(java.util.stream.Collectors.toList());

        if (words.isEmpty()) return null;  // 영문 없음 → API 검색 스킵
        return String.join(" ", words);
    }

    @SuppressWarnings("unused")
    private String buildFallbackQuery(String title, String content) {
        // title이 있으면 title 우선 사용 (가장 정확한 쿼리)
        if (title != null && !title.isBlank()) return title.trim();

        // title 없으면 content 앞 5단어
        if (content != null && !content.isBlank()) {
            return Arrays.stream(content.split("\\s+"))
                    .filter(t -> !t.isBlank())
                    .limit(5)
                    .reduce((a, b) -> a + " " + b)
                    .orElse(content);
        }
        return "research paper";
    }

    private Map<String, Object> paperToMap(Paper paper) {
        Map<String, Object> map = new java.util.HashMap<>();
        map.put("externalId",    paper.getExternalId());
        map.put("source",        paper.getSource());
        map.put("title",         paper.getTitle());
        map.put("authors",       paper.getAuthors() != null ? paper.getAuthors() : List.of());
        map.put("abstractText",  paper.getAbstractText() != null ? paper.getAbstractText() : "");
        map.put("fullTextUrl",   paper.getFullTextUrl() != null ? paper.getFullTextUrl() : "");
        map.put("publishedAt",   paper.getPublishedAt() != null ? paper.getPublishedAt().toString() : "");
        map.put("citationCount", paper.getCitationCount());
        map.put("year",          paper.getYear());
        map.put("doi",           paper.getDoi());
        return map;
    }

    private Mono<Void> createEdge(
            String sourceId, String sourceType,
            String targetId, String targetType, String targetTitle,
            float similarity, String edgeType
    ) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of(
                "source_id", sourceId, "source_type", sourceType,
                "target_id", targetId, "target_type", targetType,
                "similarity", similarity, "edge_type", edgeType
        ));
        if (targetTitle != null && !targetTitle.isBlank()) body.put("target_title", targetTitle);

        return webClientBuilder.build()
                .post()
                .uri(aiServiceUrl + "/edges")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Void.class)
                .onErrorResume(e -> {
                    log.warn("엣지 생성 실패: {}", e.getMessage());
                    return Mono.empty();
                });
    }
}
