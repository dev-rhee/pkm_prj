package com.pkm.client;

import com.pkm.model.Paper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.client.RestTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class ResearchApiClient {

    private final RestTemplate rest = new RestTemplate();

    // ── arXiv ─────────────────────────────────────────────
    public Mono<List<Paper>> searchArxiv(String query, int maxResults) {
        return Mono.fromCallable(() -> {
            String url = UriComponentsBuilder
                    .fromHttpUrl("https://export.arxiv.org/api/query")
                    .queryParam("search_query", buildArxivQuery(query))
                    .queryParam("start", 0)
                    .queryParam("max_results", maxResults)
                    .build()
                    .encode()
                    .toUriString();
            String xml = rest.getForObject(url, String.class);
            List<Paper> result = parseArxivXml(xml != null ? xml : "", query);
            log.info("arXiv 결과: {}건", result.size());
            return result;
        }).subscribeOn(Schedulers.boundedElastic()).onErrorResume(e -> {
            log.warn("arXiv 오류: {}", e.getMessage());
            return Mono.just(new ArrayList<>());
        });
    }

    private List<Paper> parseArxivXml(String xml, String query) {
        List<Paper> papers = new ArrayList<>();
        String[] entries = xml.split("<entry>");
        for (int i = 1; i < entries.length; i++) {
            String entry = entries[i];
            try {
                String rawId = extractTag(entry, "id");
                String id = rawId
                        .replace("https://arxiv.org/abs/", "")
                        .replace("http://arxiv.org/abs/", "");
                String title   = extractTag(entry, "title").trim().replace("\n", " ");
                String summary = extractTag(entry, "summary").trim();
                String pdfUrl  = "https://arxiv.org/pdf/" + id + ".pdf";

                papers.add(Paper.builder()
                        .externalId("arxiv:" + id)
                        .source("arxiv")
                        .title(title)
                        .abstractText(summary)
                        .fullTextUrl(pdfUrl)
                        .publishedAt(LocalDate.now())
                        .build());
            } catch (Exception e) {
                log.warn("arXiv 파싱 실패: {}", e.getMessage());
            }
        }
        return papers;
    }

    // ── CORE ──────────────────────────────────────────────
    public Mono<List<Paper>> searchCore(String query, int maxResults) {
        return Mono.fromCallable(() -> {
            String apiKey = System.getenv().getOrDefault("CORE_API_KEY", "");
            if (apiKey.isBlank()) return new ArrayList<Paper>();

            String url = UriComponentsBuilder
                    .fromHttpUrl("https://api.core.ac.uk/v3/search/works")
                    .queryParam("q", buildCoreQuery(query))
                    .queryParam("limit", maxResults)
                    .queryParam("fullTextIdentifier", true)
                    .build()
                    .encode()
                    .toUriString();

            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.set("Authorization", "Bearer " + apiKey);
            org.springframework.http.HttpEntity<Void> entity = new org.springframework.http.HttpEntity<>(headers);
            org.springframework.http.ResponseEntity<Map> resp =
                    rest.exchange(url, org.springframework.http.HttpMethod.GET, entity, Map.class);

            List<Paper> result = parseCoreResponse(resp.getBody() != null ? resp.getBody() : Map.of());
            log.info("CORE 결과: {}건", result.size());
            return result;
        }).subscribeOn(Schedulers.boundedElastic()).onErrorResume(e -> {
            log.warn("CORE 오류: {}", e.getMessage());
            return Mono.just(new ArrayList<>());
        });
    }

    @SuppressWarnings("unchecked")
    private List<Paper> parseCoreResponse(Map<String, Object> response) {
        List<Paper> papers = new ArrayList<>();
        Set<String> seenWorks = new HashSet<>();
        int duplicates = 0;
        List<Map<String, Object>> results =
                (List<Map<String, Object>>) response.getOrDefault("results", List.of());
        for (Map<String, Object> item : results) {
            try {
                String coreId = String.valueOf(item.get("id"));
                String title  = (String) item.getOrDefault("title", "");
                String abst   = (String) item.getOrDefault("abstract", "");
                String pdfUrl = (String) item.getOrDefault("downloadUrl", "");
                if (pdfUrl == null || pdfUrl.isBlank()) continue;

                // CORE는 같은 논문을 기관 리포지터리별 사본으로 각각 다른 최상위 id로 반환한다.
                // 사본들은 identifiers 안의 core_id를 공유하므로 그것으로 걸러낸다.
                // 제목으로 거르면 제목이 같은 별개 문서(연차 보고서 등)까지 함께 사라진다.
                String workId = canonicalWorkId(item);
                if (workId != null && !seenWorks.add(workId)) {
                    duplicates++;
                    continue;
                }

                papers.add(Paper.builder()
                        .externalId("core:" + coreId)
                        .source("core")
                        .title(title)
                        .abstractText(abst)
                        .fullTextUrl(pdfUrl)
                        .build());
            } catch (Exception e) {
                log.warn("CORE 파싱 실패: {}", e.getMessage());
            }
        }
        if (duplicates > 0) log.info("CORE 중복 사본 {}건 제거", duplicates);
        return papers;
    }

    @SuppressWarnings("unchecked")
    private String canonicalWorkId(Map<String, Object> item) {
        if (!(item.get("identifiers") instanceof List<?> identifiers)) return null;
        for (Object element : identifiers) {
            if (element instanceof Map<?, ?> identifier
                    && "core_id".equals(identifier.get("type"))
                    && identifier.get("identifier") != null) {
                return String.valueOf(identifier.get("identifier"));
            }
        }
        return null;
    }

    // ── PMC ───────────────────────────────────────────────
    public Mono<List<Paper>> searchPmc(String query, int maxResults) {
        return Mono.fromCallable(() -> {
            String searchUrl = UriComponentsBuilder
                    .fromHttpUrl("https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esearch.fcgi")
                    .queryParam("db", "pmc")
                    .queryParam("term", buildPmcQuery(query))
                    .queryParam("retmax", maxResults)
                    .queryParam("retmode", "json")
                    .build()
                    .encode()
                    .toUriString();

            @SuppressWarnings("rawtypes")
            Map searchResp = rest.getForObject(searchUrl, Map.class);
            if (searchResp == null) return new ArrayList<Paper>();
            @SuppressWarnings("unchecked")
            Map<String, Object> esearch = (Map<String, Object>) searchResp.get("esearchresult");
            if (esearch == null) return new ArrayList<Paper>();
            @SuppressWarnings("unchecked")
            List<String> ids = (List<String>) esearch.getOrDefault("idlist", List.of());
            if (ids.isEmpty()) return new ArrayList<Paper>();

            String idList = String.join(",", ids);
            String summaryUrl = UriComponentsBuilder
                    .fromHttpUrl("https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esummary.fcgi")
                    .queryParam("db", "pmc")
                    .queryParam("id", idList)
                    .queryParam("retmode", "json")
                    .build()
                    .encode()
                    .toUriString();

            @SuppressWarnings("rawtypes")
            Map summaryResp = rest.getForObject(summaryUrl, Map.class);
            List<Paper> result = parsePmcSummary(summaryResp, ids);
            log.info("PMC 결과: {}건", result.size());
            return result;
        }).subscribeOn(Schedulers.boundedElastic()).onErrorResume(e -> {
            log.warn("PMC 오류: {}", e.getMessage());
            return Mono.just(new ArrayList<>());
        });
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    private List<Paper> parsePmcSummary(Map resp, List<String> ids) {
        List<Paper> papers = new ArrayList<>();
        if (resp == null) return papers;
        Map<String, Object> result = (Map<String, Object>) resp.getOrDefault("result", Map.of());
        for (String pmcId : ids) {
            try {
                Map<String, Object> item = (Map<String, Object>) result.get(pmcId);
                if (item == null) continue;
                String title   = (String) item.getOrDefault("title", "");
                String fullUrl = "https://www.ncbi.nlm.nih.gov/pmc/articles/PMC" + pmcId + "/";
                papers.add(Paper.builder()
                        .externalId("pmc:" + pmcId)
                        .source("pmc")
                        .title(title)
                        .abstractText("")
                        .fullTextUrl(fullUrl)
                        .build());
            } catch (Exception e) {
                log.warn("PMC 파싱 실패: {}", e.getMessage());
            }
        }
        return papers;
    }

    // ── 통합 검색 ─────────────────────────────────────────
    public Mono<List<Paper>> searchAll(String query, int perSource) {
        // 전체 쿼리를 하나로 검색 — PMC는 buildPmcQuery에서 AND로 처리
        // 예: "adhd adult" → PMC: adhd[title/abstract] AND adult[title/abstract]
        return searchAllOnce(query, perSource, query);
    }

    private Mono<List<Paper>> searchAllOnce(String query, int perSource, String originalQuery) {
        return Mono.zip(
                searchArxiv(query, perSource),
                searchCore(query, perSource),
                searchPmc(query, perSource)
        ).map(tuple -> {
            List<Paper> all = new ArrayList<>();
            all.addAll(tuple.getT1());
            all.addAll(tuple.getT2());
            all.addAll(tuple.getT3());
            all.sort(Comparator.comparingInt((Paper paper) -> relevanceScore(paper, originalQuery, searchTerms(originalQuery))).reversed());
            log.info("검색 총계: {}건", all.size());
            return all;
        });
    }

    private String buildArxivQuery(String query) {
        List<String> terms = searchTerms(query);
        if (terms.isEmpty()) return "all:" + query;
        return String.join(" AND ", terms.stream().map(term -> "all:" + term).toList());
    }

    private String buildPmcQuery(String query) {
        List<String> terms = searchTerms(query);
        String titleAbstractQuery = terms.isEmpty()
                ? query + "[title/abstract]"
                : String.join(" AND ", terms.stream().map(term -> term + "[title/abstract]").toList());
        return titleAbstractQuery + " AND open access[filter]";
    }

    private String buildCoreQuery(String query) {
        return query;
    }

    // 공백뿐 아니라 하이픈도 단어 구분자로 취급 — 번역된 검색어가
    // "adult-adhd-inattention"처럼 붙어 나와도 개별 단어로 매칭되도록 함
    private List<String> searchTerms(String query) {
        return List.of(query.trim().split("[\\s-]+"))
                .stream()
                .filter(term -> !term.isBlank())
                .toList();
    }

    private int relevanceScore(Paper paper, String query, List<String> terms) {
        String title = paper.getTitle() != null ? paper.getTitle().toLowerCase() : "";
        String abstractText = paper.getAbstractText() != null ? paper.getAbstractText().toLowerCase() : "";
        String haystack = title + " " + abstractText;

        int score = 0;
        String normalizedQuery = query.trim().toLowerCase();
        if (!normalizedQuery.isBlank()) {
            if (title.contains(normalizedQuery)) score += 40;
            if (abstractText.contains(normalizedQuery)) score += 20;
        }

        int matchedTerms = 0;
        for (int i = 0; i < terms.size(); i++) {
            String term = terms.get(i).toLowerCase();
            boolean inTitle = title.contains(term);
            boolean inAbstract = abstractText.contains(term);
            if (inTitle || inAbstract) matchedTerms++;
            if (inTitle) score += 12;
            if (inAbstract) score += 4;
        }

        if (!terms.isEmpty() && matchedTerms == terms.size()) score += 30;
        if (terms.size() > 1 && matchedTerms == terms.size() - 1) score += 10;

        return score;
    }

    private String extractTag(String xml, String tag) {
        int start = xml.indexOf("<" + tag + ">");
        int end   = xml.indexOf("</" + tag + ">");
        if (start == -1 || end == -1) return "";
        return xml.substring(start + tag.length() + 2, end);
    }
}
