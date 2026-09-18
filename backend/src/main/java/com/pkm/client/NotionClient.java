package com.pkm.client;


import com.pkm.model.Paper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotionClient {

    private final WebClient.Builder webClientBuilder;

    @Value("${app.notion.api-key}")
    private String apiKey;

    @Value("${app.notion.base-url}")
    private String baseUrl;

    @Value("${app.notion.api-version}")
    private String apiVersion;

    @Value("${app.notion.parent-page-id:}")
    private String parentPageId;

    @Value("${app.notion.resource-db-id:}")
    private String resourceDbId;

    // 대상 DB(제텔×PARA)의 분류 속성. 논문 요약은 PARA의 '리소스'로 넣고, 색인은 저장 시 고른다.
    private static final String TYPE_PROPERTY  = "유형";
    private static final String TYPE_VALUE     = "리소스";
    private static final String INDEX_PROPERTY = "색인";

    private volatile Map<String, Object> cachedSchema;

    private WebClient client() {
        return webClientBuilder.baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Notion-Version", apiVersion)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    // ── 메모 목록 조회 ─────────────────────────────────────
    // 리소스 DB가 설정돼 있으면 그 안의 페이지만 보여준다. 전체 검색(/search)은
    // 대시보드·논문 요약 페이지까지 다 섞여 나와 "연결할 메모"로 고르기에 맞지 않는다.
    @SuppressWarnings("unchecked")
    public Mono<List<Map<String, Object>>> listPages() {
        boolean fromDatabase = resourceDbId != null && !resourceDbId.isBlank();
        var request = fromDatabase
                ? client().post()
                        .uri("/databases/" + resourceDbId + "/query")
                        .bodyValue(Map.of("page_size", 100))
                : client().post()
                        .uri("/search")
                        .bodyValue(Map.of(
                                "filter", Map.of("property", "object", "value", "page"),
                                "page_size", 100
                        ));

        return request
                .retrieve()
                .bodyToMono(Map.class)
                .map(resp -> {
                    List<Map<String, Object>> results =
                            (List<Map<String, Object>>) resp.getOrDefault("results", List.of());
                    return results.stream()
                            .map(this::extractPageSummary)
                            .toList();
                })
                .doOnSuccess(pages -> log.info("Notion 페이지 조회: {}건", pages.size()))
                .onErrorResume(e -> {
                    logNotionError("Notion 페이지 조회 실패", e);
                    return Mono.just(new ArrayList<>());
                });
    }

    // ── 단일 페이지 제목 조회 ──────────────────────────────
    @SuppressWarnings("unchecked")
    public Mono<String> getPageTitle(String pageId) {
        return client().get()
                .uri("/pages/" + pageId)
                .retrieve()
                .bodyToMono(Map.class)
                .map(page -> (String) extractPageSummary((Map<String, Object>) page).get("title"))
                .onErrorResume(e -> {
                    logNotionError("Notion 페이지 제목 조회 실패", e);
                    return Mono.just("");
                });
    }

    // ── 단일 페이지 내용 조회 ──────────────────────────────
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> getPageContent(String pageId) {
        return client().get()
                .uri("/blocks/" + pageId + "/children")
                .retrieve()
                .bodyToMono(Map.class)
                .map(resp -> {
                    List<Map<String, Object>> blocks =
                            (List<Map<String, Object>>) resp.getOrDefault("results", List.of());
                    String text = extractTextFromBlocks(blocks);
                    return Map.of("pageId", pageId, "content", text);
                });
    }

    // ── 논문 요약 저장 (새 페이지 생성) ───────────────────
    // NOTION_RESOURCE_DB_ID가 있으면 그 데이터베이스 안에, 없으면 부모 페이지 아래에 만든다.
    public Mono<String> savePaperSummary(SaveRequest req) {
        boolean toDatabase = resourceDbId != null && !resourceDbId.isBlank();
        if (!toDatabase && (parentPageId == null || parentPageId.isBlank())) {
            log.warn("Notion 저장 실패: NOTION_RESOURCE_DB_ID와 NOTION_PARENT_PAGE_ID가 모두 비어 있습니다.");
            return Mono.just("");
        }

        // DB에 넣을 때는 속성 이름이 DB마다 달라 스키마를 먼저 읽는다. 페이지 아래면 "title" 고정.
        Mono<Map<String, Object>> schema = toDatabase ? databaseSchema() : Mono.just(Map.of());

        return schema
                .flatMap(props -> {
                    Map<String, Object> properties = new HashMap<>();
                    properties.put(toDatabase ? titleProperty(props) : "title",
                            Map.of("title", List.of(Map.of("text", Map.of("content", req.title())))));

                    if (toDatabase) {
                        // 분류 속성은 DB에 있을 때만 채운다. 없어도 저장은 되게 둔다.
                        if (hasProperty(props, TYPE_PROPERTY, "select")) {
                            properties.put(TYPE_PROPERTY, Map.of("select", Map.of("name", TYPE_VALUE)));
                        }
                        if (hasProperty(props, INDEX_PROPERTY, "relation")
                                && req.indexNotionIds() != null && !req.indexNotionIds().isEmpty()) {
                            properties.put(INDEX_PROPERTY, Map.of("relation",
                                    req.indexNotionIds().stream().map(id -> Map.of("id", id)).toList()));
                        }
                    }

                    Map<String, Object> body = new HashMap<>();
                    body.put("parent", toDatabase
                            ? Map.of("database_id", resourceDbId)
                            : Map.of("page_id", parentPageId));
                    body.put("properties", properties);
                    body.put("children", buildPageBlocks(req));
                    return client().post()
                            .uri("/pages")
                            .bodyValue(body)
                            .retrieve()
                            .bodyToMono(Map.class);
                })
                .map(resp -> (String) resp.get("id"))
                .doOnSuccess(id -> log.info("Notion 저장 완료: {} → {}", id,
                        toDatabase ? "DB " + resourceDbId : "부모 페이지"))
                .onErrorResume(e -> {
                    logNotionError("Notion 저장 실패", e);
                    return Mono.just("");
                });
    }

    // ── 색인 목록 조회 (저장 시 고를 수 있게) ───────────────
    // 대상 DB의 '색인' relation이 가리키는 DB를 스키마에서 찾아 그 항목들을 돌려준다.
    @SuppressWarnings("unchecked")
    public Mono<List<Map<String, Object>>> listIndexPages() {
        if (resourceDbId == null || resourceDbId.isBlank()) return Mono.just(List.of());

        return databaseSchema()
                .flatMap(props -> {
                    if (!(props.get(INDEX_PROPERTY) instanceof Map<?, ?> prop)
                            || !(prop.get("relation") instanceof Map<?, ?> relation)
                            || !(relation.get("database_id") instanceof String indexDbId)) {
                        log.warn("대상 DB에 '{}' relation이 없어 색인 목록을 비웁니다.", INDEX_PROPERTY);
                        return Mono.just(List.<Map<String, Object>>of());
                    }
                    return client().post()
                            .uri("/databases/" + indexDbId + "/query")
                            .bodyValue(Map.of("page_size", 100))
                            .retrieve()
                            .bodyToMono(Map.class)
                            .map(resp -> ((List<Map<String, Object>>) resp.getOrDefault("results", List.of()))
                                    .stream().map(this::extractPageSummary).toList());
                })
                .doOnSuccess(pages -> log.info("Notion 색인 조회: {}건", pages.size()))
                .onErrorResume(e -> {
                    logNotionError("Notion 색인 조회 실패", e);
                    return Mono.just(new ArrayList<>());
                });
    }

    /** 대상 DB의 properties. 스키마는 바뀌지 않으므로 한 번 읽으면 재사용한다. */
    @SuppressWarnings("unchecked")
    private Mono<Map<String, Object>> databaseSchema() {
        if (cachedSchema != null) return Mono.just(cachedSchema);

        return client().get()
                .uri("/databases/" + resourceDbId)
                .retrieve()
                .bodyToMono(Map.class)
                .map(db -> (Map<String, Object>) db.getOrDefault("properties", Map.of()))
                .doOnNext(props -> {
                    cachedSchema = props;
                    log.info("Notion DB 스키마 로드: 제목='{}', 속성={}", titleProperty(props), props.keySet());
                });
    }

    private static String titleProperty(Map<String, Object> props) {
        return props.entrySet().stream()
                .filter(e -> e.getValue() instanceof Map<?, ?> p && "title".equals(p.get("type")))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("DB에 title 속성이 없습니다"));
    }

    private static boolean hasProperty(Map<String, Object> props, String name, String type) {
        return props.get(name) instanceof Map<?, ?> p && type.equals(p.get("type"));
    }

    // ── 관련 메모와 Relation 연결 ──────────────────────────
    public Mono<Void> linkToMemo(String savedPageId, String memoPageId) {
        // Notion relation은 DB 기반만 가능하므로 코멘트로 대체
        Map<String, Object> body = Map.of(
                "parent", Map.of("page_id", savedPageId),
                "rich_text", List.of(Map.of(
                        "text", Map.of(
                                "content", "관련 메모: ",
                                "link", Map.of("url", "https://notion.so/" +
                                        memoPageId.replace("-", ""))
                        )
                ))
        );

        return client().post()
                .uri("/comments")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Void.class)
                .onErrorResume(e -> {
                    log.warn("Notion 링크 실패: {}", e.getMessage());
                    return Mono.empty();
                });
    }

    // ── 헬퍼 ──────────────────────────────────────────────
    @SuppressWarnings("unchecked")
    private Map<String, Object> extractPageSummary(Map<String, Object> page) {
        String id = (String) page.get("id");
        Map<String, Object> props =
                (Map<String, Object>) page.getOrDefault("properties", Map.of());

        String title = props.values().stream()
                .filter(Map.class::isInstance)
                .map(prop -> (Map<String, Object>) prop)
                .filter(prop -> "title".equals(prop.get("type")))
                .findFirst()
                .map(this::extractTitle)
                .orElse("(제목 없음)");

        // 메모가 속한 색인. 프론트가 색인 폴더별로 메모를 묶는 데 쓴다. 색인 페이지 자신은 빈 목록.
        List<String> indexIds = props.get(INDEX_PROPERTY) instanceof Map<?, ?> prop
                && prop.get("relation") instanceof List<?> relation
                ? relation.stream()
                        .filter(Map.class::isInstance)
                        .map(r -> (String) ((Map<?, ?>) r).get("id"))
                        .filter(rid -> rid != null)
                        .toList()
                : List.of();

        return Map.of("id", id, "title", title, "indexIds", indexIds);
    }

    @SuppressWarnings("unchecked")
    private String extractTitle(Map<String, Object> titleProp) {
        List<Map<String, Object>> titleArr =
                (List<Map<String, Object>>) titleProp.getOrDefault("title", List.of());

        return titleArr.stream()
                .map(rt -> (Map<String, Object>) rt.getOrDefault("text", Map.of()))
                .map(text -> (String) text.getOrDefault("content", ""))
                .reduce("", String::concat)
                .trim();
    }

    @SuppressWarnings("unchecked")
    private String extractTextFromBlocks(List<Map<String, Object>> blocks) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> block : blocks) {
            String type = (String) block.getOrDefault("type", "");
            Map<String, Object> typeContent =
                    (Map<String, Object>) block.getOrDefault(type, Map.of());
            List<Map<String, Object>> richText =
                    (List<Map<String, Object>>) typeContent.getOrDefault("rich_text", List.of());
            for (Map<String, Object> rt : richText) {
                Map<String, Object> text =
                        (Map<String, Object>) rt.getOrDefault("text", Map.of());
                sb.append(text.getOrDefault("content", "")).append(" ");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private List<Map<String, Object>> buildPageBlocks(SaveRequest req) {
        return List.of(
                // 요약 섹션
                Map.of("object", "block", "type", "heading_2",
                        "heading_2", Map.of("rich_text", List.of(
                                Map.of("text", Map.of("content", "AI 요약"))))),
                Map.of("object", "block", "type", "paragraph",
                        "paragraph", Map.of("rich_text", List.of(
                                Map.of("text", Map.of("content", req.summary() != null ? req.summary() : ""))))),
                // 원문 링크
                Map.of("object", "block", "type", "heading_2",
                        "heading_2", Map.of("rich_text", List.of(
                                Map.of("text", Map.of("content", "원문"))))),
                Map.of("object", "block", "type", "paragraph",
                        "paragraph", Map.of("rich_text", List.of(
                                Map.of("text", Map.of(
                                        "content", req.fullTextUrl() != null ? req.fullTextUrl() : "",
                                        "link", Map.of("url", req.fullTextUrl() != null ? req.fullTextUrl() : "https://example.com")))))),
                // 태그
                Map.of("object", "block", "type", "heading_2",
                        "heading_2", Map.of("rich_text", List.of(
                                Map.of("text", Map.of("content", "태그"))))),
                Map.of("object", "block", "type", "paragraph",
                        "paragraph", Map.of("rich_text", List.of(
                                Map.of("text", Map.of("content",
                                        String.join(" ", req.tags()))))))
        );
    }

    private void logNotionError(String message, Throwable e) {
        if (e instanceof WebClientResponseException webError) {
            log.warn("{}: status={}, body={}",
                    message,
                    webError.getStatusCode(),
                    webError.getResponseBodyAsString());
            return;
        }
        log.warn("{}: {}", message, e.getMessage());
    }

    public record SaveRequest(
            String title,
            String summary,
            String fullTextUrl,
            List<String> tags,
            List<String> linkedNotionIds,
            List<String> indexNotionIds
    ) {}
}
