# PKM — 로컬 개인 지식 관리 시스템

논문을 검색해서 로컬 LLM으로 요약하고, 요약본과 논문URL을 Notion에 저장하고, 서로 이어서 보는 개인용 도구.

## 왜 만들었나

평소 논문을 찾아 읽는 과정이 늘 흩어져 있었습니다. 여러사이트를 돌아다니며 논문을 찾고, 초록을 보며 논문을 파악하고, 노션에 저장하는
이 흐름을 하나로 묶고 싶어서 만든 개인용 도구입니다. 

## 무엇을 하나

- **검색** — 키워드 하나로 arXiv, CORE, PubMed Central, Semantic Scholar, OpenAlex를 한 번에 찾습니다. 무료로 원문을 볼 수 있는 논문만 보여줍니다.
- **요약** — 로컬 LLM(Ollama)이 한국어로 요약합니다. 무엇을·어떻게 연구했고, 무엇을 발견했고, 왜 중요한지 네 항목으로 정리합니다.
- **저장** — 요약과 원문 링크를 Notion 데이터베이스에 넣고, 색인으로 분류합니다.
- **연결** — 저장한 논문과 메모를 그래프로 봅니다. 직접 이어둔 것, 내용이 비슷한 것, 같은 색인에 속한 것을 구분해서 보여줍니다.

요약과 임베딩은 Ollama를 활용해 모두 로컬 환경에서 처리하고, 검색과 Notion 연동에는 무료 API만 사용합니다.

## 어떻게 동작하나

### 검색 결과 정렬

다섯 곳에서 모은 논문을 앱이 한 번에 다시 줄 세웁니다.

**기본 점수 — BM25**
검색어가 제목과 초록에 얼마나 잘 맞는지. 드문 단어일수록, 자주 나올수록, 짧은 글에 있을수록 점수가 높습니다. 제목은 초록보다 세 배 무겁게 봅니다.

**여기에 세 가지를 더합니다**

| 보너스 | 의도 |
|---|---|
| 전부 매칭 | 검색어를 **모두** 포함한 논문을 무조건 위로. "전부 맞춘 것 → 일부만 맞춘 것 → 안 맞은 것" 순으로 묶이고, 각 묶음 안은 BM25 순 |
| 인용수 | 많이 인용된 논문을 살짝 앞에 (로그 스케일이라 인용 1,000회도 +0.7 정도) |
| 최신성 | 동점이면 최근 논문을 앞에 |

검색 시점에는 키워드만 보고 의미(임베딩)는 저장한 뒤 논문끼리 잇는 데만 씁니다.

## 스택

| 역할 | 기술 |
|---|---|
| API Gateway | Spring Boot 3.3 (Java 21) |
| AI 서비스 | FastAPI + Python 3.11 |
| LLM | Ollama — `exaone3.5:7.8b` (요약·검색어 번역) |
| 임베딩 | Ollama — `paraphrase-multilingual` (한국어·영어 모두 지원, 768차원) |
| 논문 검색 | Semantic Scholar · OpenAlex · arXiv · CORE · PubMed Central (전부 무료) |
| DB | PostgreSQL 16 + pgvector (hnsw 인덱스) |
| 캐시 | Redis 7 |
| 프론트엔드 | Next.js 14 |


## API 엔드포인트

### 논문 검색
```
GET /api/papers/search?q=attention+mechanism&perSource=50
→ 소스별 최대 50건을 모아 재랭킹한 목록 (최대 300건)
```

### 요약 보기 (SSE 스트리밍)
```
POST /api/papers/{externalId}/summarize
Body: { "title": "...", "abstract": "..." }
→ text/event-stream, 토큰 단위 {"token": "..."}, 끝은 [DONE]
```

### 저장
```
POST /api/papers/{externalId}/save
Body: {
  "title": "...",
  "summary": "이미 생성된 요약본",        ← Ollama 재호출 없음
  "fullTextUrl": "...",
  "source": "arxiv",
  "externalId": "arxiv:1234.5678",
  "tags": [],
  "linkedNotionIds": ["메모 페이지 ID"],   ← 직접 연결
  "indexNotionIds":  ["색인 페이지 ID"]    ← 색인
}
→ Notion DB에 페이지 생성 + 임베딩 저장 + 그래프 엣지 + 내용이 비슷한 논문·메모 재발견
```

### Notion 메모 목록
```
GET /api/papers/notion/pages     → 리소스 DB 안의 페이지 (메모 연결용)
GET /api/papers/notion/index     → 색인 DB 항목 (색인 선택용)
```

### Notion 메모 → 논문 추천
```
POST /api/papers/notion/recommend
Body: { "notionPageId": "...", "title": "메모 제목", "content": "" }
→ content 를 비워 보내면 백엔드가 Notion 에서 본문을 읽어 임베딩합니다
```

### 그래프
```
GET /api/graph                    → 전체 노드·엣지 (논문 / 메모 / 색인)
GET /api/graph/node/{nodeId}      → 특정 노드 중심 서브그래프
```

## 흐름 A — 검색 → 저장

1. 키워드 검색 → 정렬된 논문 목록 (10건씩 페이지)
2. 요약 보기 → 한국어 요약이 실시간으로 스트리밍
3. (선택) 색인 고르기, 연결할 메모 체크
4. Notion에 저장 → DB에 페이지 생성, 그래프에 노드·엣지 추가

## 흐름 B — Notion 메모 → 추천 → 저장

1. 메모 목록에서 하나 선택
2. 메모 본문을 임베딩해 비슷한 논문 추천 (DB에 저장된 것 + 외부 검색)
3. 원하는 논문 요약 보기 → 저장 (흐름 A와 동일)

