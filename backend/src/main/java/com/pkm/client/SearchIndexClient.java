package com.pkm.client;

import com.pkm.model.Paper;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 검색 인덱스 레이어 — Semantic Scholar + OpenAlex
 *
 * 역할: 검색 품질, citation count, related papers
 * ResearchApiClient(arXiv/CORE/PMC)는 full-text URL 확보용 폴백으로 분리
 */
@Slf4j
@Component
public class SearchIndexClient {

    private static final int MAX_FETCH = 50;
    private final RestTemplate rest = new RestTemplate();

    // ── Semantic Scholar ──────────────────────────────────────
    public Mono<List<Paper>> searchSemanticScholar(String query, int maxResults) {
        return Mono.fromCallable(() -> {
            int limit = Math.min(maxResults, MAX_FETCH);

            String url = UriComponentsBuilder
                    .fromHttpUrl("https://api.semanticscholar.org/graph/v1/paper/search")
                    .queryParam("query", query)
                    .queryParam("fields", "paperId,title,abstract,authors,year,citationCount,openAccessPdf,externalIds")
                    .queryParam("limit", limit)
                    .build().encode().toUriString();

            HttpHeaders headers = new HttpHeaders();
            String apiKey = System.getenv().getOrDefault("SEMANTIC_SCHOLAR_API_KEY", "").trim();
            if (!apiKey.isBlank()) headers.set("x-api-key", apiKey);
            else log.warn("Semantic Scholar API 키 없음 — 공용 쿼터로 호출하므로 429가 잦다");

            ResponseEntity<Map> resp = rest.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            List<Paper> result = parseSemanticScholar(resp.getBody() != null ? resp.getBody() : Map.of());
            log.info("Semantic Scholar 결과: {}건", result.size());
            return result;
        }).subscribeOn(Schedulers.boundedElastic()).onErrorResume(e -> {
            log.warn("Semantic Scholar 오류: {}", e.getMessage());
            return Mono.just(new ArrayList<>());
        });
    }

    @SuppressWarnings("unchecked")
    private List<Paper> parseSemanticScholar(Map<String, Object> response) {
        List<Paper> papers = new ArrayList<>();
        List<Map<String, Object>> data = (List<Map<String, Object>>) response.getOrDefault("data", List.of());

        for (Map<String, Object> item : data) {
            try {
                String paperId    = String.valueOf(item.get("paperId"));
                String title      = (String) item.getOrDefault("title", "");
                String abstract_  = (String) item.getOrDefault("abstract", "");
                Integer year      = (Integer) item.get("year");
                Integer citations = (Integer) item.get("citationCount");

                List<String> authors = new ArrayList<>();
                for (Map<String, Object> a : (List<Map<String, Object>>) item.getOrDefault("authors", List.of())) {
                    String name = (String) a.get("name");
                    if (name != null) authors.add(name);
                }

                Map<String, Object> externalIds = (Map<String, Object>) item.getOrDefault("externalIds", Map.of());
                String arxivId = (String) externalIds.get("ArXiv");
                String doi     = (String) externalIds.get("DOI");
                String pmcId   = (String) externalIds.get("PubMedCentral");

                String pdfUrl = null;
                Map<String, Object> oaPdf = (Map<String, Object>) item.get("openAccessPdf");
                if (oaPdf != null) pdfUrl = (String) oaPdf.get("url");
                if ((pdfUrl == null || pdfUrl.isBlank()) && arxivId != null)
                    pdfUrl = "https://arxiv.org/pdf/" + arxivId + ".pdf";
                // OA이지만 직접 PDF 없는 경우 — PMC URL로 폴백 (무료 열람 가능)
                if ((pdfUrl == null || pdfUrl.isBlank()) && pmcId != null)
                    pdfUrl = "https://www.ncbi.nlm.nih.gov/pmc/articles/PMC" + pmcId + "/";
                // 여전히 없으면 유료 논문으로 간주 → 스킵
                if (pdfUrl == null || pdfUrl.isBlank()) continue;

                papers.add(Paper.builder()
                        .externalId("s2:" + paperId)
                        .source("semantic_scholar")
                        .title(title)
                        .abstractText(abstract_)
                        .authors(authors)
                        .fullTextUrl(pdfUrl)
                        .publishedAt(year != null ? LocalDate.of(year, 1, 1) : null)
                        .year(year)
                        .citationCount(citations)
                        .doi(doi)
                        .build());
            } catch (Exception e) {
                log.warn("Semantic Scholar 파싱 실패: {}", e.getMessage());
            }
        }
        return papers;
    }

    // ── OpenAlex ─────────────────────────────────────────────
    public Mono<List<Paper>> searchOpenAlex(String query, int maxResults) {
        return Mono.fromCallable(() -> {
            int limit = Math.min(maxResults, MAX_FETCH);

            String url = UriComponentsBuilder
                    .fromHttpUrl("https://api.openalex.org/works")
                    .queryParam("search", query)
                    .queryParam("per-page", limit)
                    // is_oa:true 제거 — 의학/심리 논문처럼 OA가 아닌 논문도 검색되도록
                    .queryParam("select", "id,title,abstract_inverted_index,authorships,publication_year,cited_by_count,doi,open_access,primary_location")
                    .build().encode().toUriString();

            Map resp = rest.getForObject(url, Map.class);
            List<Paper> result = parseOpenAlex(resp != null ? resp : Map.of());
            log.info("OpenAlex 결과: {}건", result.size());
            return result;
        }).subscribeOn(Schedulers.boundedElastic()).onErrorResume(e -> {
            log.warn("OpenAlex 오류: {}", e.getMessage());
            return Mono.just(new ArrayList<>());
        });
    }

    @SuppressWarnings("unchecked")
    private List<Paper> parseOpenAlex(Map<String, Object> response) {
        List<Paper> papers = new ArrayList<>();
        List<Map<String, Object>> results = (List<Map<String, Object>>) response.getOrDefault("results", List.of());

        for (Map<String, Object> item : results) {
            try {
                String openAlexId = ((String) item.getOrDefault("id", "")).replace("https://openalex.org/", "");
                String title      = (String) item.getOrDefault("title", "");
                Integer year      = (Integer) item.get("publication_year");
                Integer citations = (Integer) item.get("cited_by_count");
                String doi        = (String) item.get("doi");
                if (doi != null) doi = doi.replace("https://doi.org/", "");

                Map<String, Object> invertedIndex = (Map<String, Object>) item.get("abstract_inverted_index");
                String abstract_ = invertedIndex != null ? reconstructAbstract(invertedIndex) : "";

                List<String> authors = new ArrayList<>();
                for (Map<String, Object> as : (List<Map<String, Object>>) item.getOrDefault("authorships", List.of())) {
                    Map<String, Object> a = (Map<String, Object>) as.get("author");
                    if (a != null) { String n = (String) a.get("display_name"); if (n != null) authors.add(n); }
                }

                String pdfUrl = null;
                Map<String, Object> oa = (Map<String, Object>) item.get("open_access");
                Boolean isOa = oa != null ? (Boolean) oa.get("is_oa") : null;
                if (oa != null) pdfUrl = (String) oa.get("oa_url");
                Map<String, Object> loc = (Map<String, Object>) item.get("primary_location");
                // oa_url 없어도 OA 논문이면 landing_page_url 허용 (PMC 등)
                if ((pdfUrl == null || pdfUrl.isBlank()) && Boolean.TRUE.equals(isOa) && loc != null)
                    pdfUrl = (String) loc.get("landing_page_url");
                // OA가 아니면 유료 논문 → 스킵
                if (pdfUrl == null || pdfUrl.isBlank()) continue;

                papers.add(Paper.builder()
                        .externalId("oa:" + openAlexId)
                        .source("openalex")
                        .title(title)
                        .abstractText(abstract_)
                        .authors(authors)
                        .fullTextUrl(pdfUrl)
                        .publishedAt(year != null ? LocalDate.of(year, 1, 1) : null)
                        .year(year)
                        .citationCount(citations)
                        .doi(doi)
                        .build());
            } catch (Exception e) {
                log.warn("OpenAlex 파싱 실패: {}", e.getMessage());
            }
        }
        return papers;
    }

    @SuppressWarnings("unchecked")
    private String reconstructAbstract(Map<String, Object> invertedIndex) {
        TreeMap<Integer, String> posToWord = new TreeMap<>();
        for (Map.Entry<String, Object> entry : invertedIndex.entrySet()) {
            for (Integer pos : (List<Integer>) entry.getValue()) {
                posToWord.put(pos, entry.getKey());
            }
        }
        return String.join(" ", posToWord.values());
    }

    // ── 통합 검색 ────────────────────────────────────────────
    public Mono<List<Paper>> searchAll(String query, int perSource) {
        int fetchSize = Math.min(perSource * 3, MAX_FETCH);

        return Mono.zip(
                searchSemanticScholar(query, fetchSize),
                searchOpenAlex(query, fetchSize)
        ).map(tuple -> {
            Map<String, Paper> unique = new LinkedHashMap<>();
            for (Paper p : tuple.getT1()) unique.putIfAbsent(p.getExternalId(), p);
            for (Paper p : tuple.getT2()) unique.putIfAbsent(p.getExternalId(), p);

            List<Paper> pool = new ArrayList<>(unique.values());
            List<Paper> result = rerankWithLucene(pool, query, perSource);

            log.info("SearchIndex 통합 결과: {}건 (후보: {}건)", result.size(), pool.size());
            return result;
        });
    }

    // 검색어를 전부 포함한 논문에 주는 보너스. BM25 점수보다 훨씬 크게 잡아
    // "전부 맞춘 논문 → 일부만 맞춘 논문" 순으로 묶이게 한다. 각 묶음 안의 순서는 BM25가 정한다.
    private static final double FULL_MATCH_BONUS = 1000.0;

    private List<String> searchTerms(String query) {
        return List.of(query.trim().toLowerCase().split("[\\s-]+"))
                .stream()
                .filter(term -> !term.isBlank())
                .toList();
    }

    /** 단어가 2개 이상일 때만 의미가 있다. 제목+초록에 모든 단어가 들어간 경우에만 준다. */
    private double fullMatchBonus(Paper paper, List<String> terms) {
        if (terms.size() < 2) return 0;
        String haystack = ((paper.getTitle() != null ? paper.getTitle() : "") + " "
                + (paper.getAbstractText() != null ? paper.getAbstractText() : "")).toLowerCase();
        return terms.stream().allMatch(haystack::contains) ? FULL_MATCH_BONUS : 0;
    }

    // ── Lucene 인메모리 BM25 재정렬 ──────────────────────────
    // 소스별 결과를 한데 모은 뒤 앱에서 다시 순위를 매길 때도 쓰인다.
    public List<Paper> rerank(List<Paper> papers, String query, int limit) {
        return rerankWithLucene(papers, query, limit);
    }

    private List<Paper> rerankWithLucene(List<Paper> papers, String query, int limit) {
        if (papers.isEmpty()) return papers;

        try (StandardAnalyzer analyzer = new StandardAnalyzer();
             ByteBuffersDirectory directory = new ByteBuffersDirectory()) {

            // 인덱스 구축 — title에 3배 가중치
            IndexWriterConfig config = new IndexWriterConfig(analyzer);
            try (IndexWriter writer = new IndexWriter(directory, config)) {
                for (Paper p : papers) {
                    Document doc = new Document();
                    doc.add(new StringField("id", p.getExternalId(), Field.Store.YES));
                    // title을 3번 추가해서 BM25에서 높은 가중치 부여
                    String title = p.getTitle() != null ? p.getTitle() : "";
                    doc.add(new TextField("title", title, Field.Store.NO));
                    doc.add(new TextField("title", title, Field.Store.NO));
                    doc.add(new TextField("title", title, Field.Store.NO));
                    doc.add(new TextField("abstract", p.getAbstractText() != null ? p.getAbstractText() : "", Field.Store.NO));
                    writer.addDocument(doc);
                }
            }

            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                IndexSearcher searcher = new IndexSearcher(reader);

                MultiFieldQueryParser parser = new MultiFieldQueryParser(
                        new String[]{"title", "abstract"}, analyzer
                );
                // 항상 OR로 묶는다. AND로 묶으면 단어를 하나라도 놓친 논문이 전부 탈락해
                // 흔한 두 단어 검색에서도 후보 30건 중 1건만 남는 일이 생긴다.
                // 몇 개를 맞췄는지는 BM25 점수에 반영되므로 많이 맞춘 논문이 자연히 위로 온다.
                parser.setDefaultOperator(QueryParser.Operator.OR);

                Query luceneQuery = parser.parse(QueryParser.escape(query));
                TopDocs topDocs = searcher.search(luceneQuery, papers.size());

                // externalId → Paper 맵
                Map<String, Paper> paperMap = papers.stream()
                        .collect(Collectors.toMap(Paper::getExternalId, p -> p));

                // Lucene 점수 + citation/recency + 전부 매칭 보너스로 최종 점수 계산
                List<String> terms = searchTerms(query);
                Map<String, Double> scores = new HashMap<>();
                for (ScoreDoc sd : topDocs.scoreDocs) {
                    String id = searcher.storedFields().document(sd.doc).get("id");
                    Paper p = paperMap.get(id);
                    if (p == null) continue;

                    double citationBonus = Math.log1p(Optional.ofNullable(p.getCitationCount()).orElse(0)) * 0.1;
                    double recencyBonus  = p.getYear() != null ? Math.max(0, p.getYear() - 2020) * 0.05 : 0;
                    scores.put(id, (double) sd.score + citationBonus + recencyBonus + fullMatchBonus(p, terms));
                }

                // Lucene에 매칭된 논문 먼저, 점수 내림차순
                List<Paper> ranked = papers.stream()
                        .filter(p -> scores.containsKey(p.getExternalId()))
                        .sorted(Comparator.comparingDouble(p -> -scores.get(p.getExternalId())))
                        .collect(Collectors.toList());

                // 미매칭 논문도 뒤에 붙인다. 페이지를 넘겨 볼 수 있으니 버리는 것보다 낫다.
                Set<String> matched = scores.keySet();
                papers.stream()
                        .filter(p -> !matched.contains(p.getExternalId()))
                        .forEach(ranked::add);

                log.info("Lucene 재정렬: {}건 매칭 / 전체 {}건", topDocs.scoreDocs.length, papers.size());
                return ranked.stream().limit(limit).collect(Collectors.toList());
            }

        } catch (Exception e) {
            log.warn("Lucene 재정렬 실패, 원본 순서 유지: {}", e.getMessage());
            return papers.stream().limit(limit).collect(Collectors.toList());
        }
    }
}
