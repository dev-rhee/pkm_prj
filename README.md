# PKM — 로컬 개인 지식 관리 시스템

논문을 검색하고, 로컬 LLM으로 요약하고, Notion에 저장하고, 서로 이어서 보는 개인용 도구. 유료 API 없음.

## 왜 만들었나

평소 논문을 찾아 읽는 과정이 늘 흩어져 있었습니다. 검색은 여러 사이트를 돌며, 내용 파악은 초록을 읽어가며, 저장은 Notion에 따로.
이 흐름을 하나로 묶고 싶어서 만든 개인용 도구입니다.

## 무엇을 하나

- **검색** — 키워드 하나로 arXiv, CORE, PubMed Central, Semantic Scholar, OpenAlex를 한 번에 찾습니다. 무료로 원문을 볼 수 있는 논문만 보여줍니다.
- **요약** — 로컬 LLM(Ollama)이 한국어로 요약합니다. 무엇을·어떻게 연구했고, 무엇을 발견했고, 왜 중요한지 네 항목으로 정리합니다.
- **저장** — 요약과 원문 링크를 Notion 데이터베이스에 넣고, 색인으로 분류합니다.
- **연결** — 저장한 논문과 메모를 그래프로 봅니다. 직접 이어둔 것, 내용이 비슷한 것, 같은 색인에 속한 것을 구분해서 보여줍니다.

요약과 임베딩은 전부 내 컴퓨터에서 돌고, 검색과 Notion은 무료 API만 씁니다.

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

검색어와 안 맞는 논문도 버리지 않고 맨 뒤에 둡니다. 10건씩 페이지로 넘겨 봅니다.

검색 시점에는 키워드만 봅니다. 의미(임베딩)는 저장한 뒤 논문끼리 잇는 데만 씁니다.

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

## 시작하기

### 1. Ollama 설치 (호스트)

Ollama는 컨테이너가 아니라 **호스트에 직접** 설치합니다. GPU를 쓰기 위해서입니다 (Windows Docker Desktop은 NVIDIA만 GPU를 넘겨주고, AMD는 네이티브로만 됩니다).

```bash
ollama pull exaone3.5:7.8b
ollama pull paraphrase-multilingual
```

컨테이너에서 접근하려면 Ollama가 모든 인터페이스에 열려 있어야 합니다. Windows라면 사용자 환경변수로 등록:

```bash
setx OLLAMA_HOST "0.0.0.0:11434"
```

등록 후 Ollama를 재시작합니다. 같은 네트워크의 다른 기기에서도 접근 가능해지니 필요하면 방화벽에서 11434를 막습니다.

### 2. 환경변수 설정

```bash
cp .env.example .env
```

`.env`에서 채울 값:

| 변수 | 필수 | 설명 |
|---|---|---|
| `NOTION_API_KEY` | ○ | Integration Token |
| `NOTION_RESOURCE_DB_ID` | ○ | 논문 요약을 넣을 Notion 데이터베이스 ID |
| `NOTION_PARENT_PAGE_ID` | | DB를 안 쓸 때 폴백으로 쓰는 부모 페이지 |
| `SEMANTIC_SCHOLAR_API_KEY` | | 없으면 공용 쿼터라 429가 잦음 |
| `CORE_API_KEY` | | full-text 논문 검색 |

Notion 준비:
1. https://www.notion.so/my-integrations 에서 New integration 생성, Secret 복사
2. 논문을 넣을 **데이터베이스**를 열고 우상단 ··· → 연결 → 방금 만든 integration 추가
3. 그 DB의 ID를 `NOTION_RESOURCE_DB_ID`에 입력. **연결된 데이터베이스 보기(linked view)가 아니라 원본 DB**여야 합니다. API로는 뷰에 쓸 수 없습니다

DB에 아래 속성이 있으면 저장할 때 자동으로 채웁니다 (없어도 저장은 됩니다):

| 속성 | 타입 | 채우는 값 |
|---|---|---|
| 제목 (아무 이름) | title | 논문 제목 |
| `유형` | select | `리소스` |
| `색인` | relation | 저장 시 고른 색인 |

### 3. 실행

```bash
docker compose up -d
```

### 4. 확인

```bash
# 서비스 상태
docker compose ps

# AI 서비스
curl http://localhost:8000/health

# 백엔드 (Notion 연결까지 확인)
curl http://localhost:8080/api/papers/notion/index

# Ollama — size_vram 이 0 보다 크면 GPU 사용 중
curl http://localhost:11434/api/ps
```

브라우저: http://localhost:3000

### 5. 로그 보기

```bash
docker compose logs -f ai-service    # FastAPI
docker compose logs -f backend       # Spring Boot
docker compose logs -f frontend      # Next.js
```

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

## 그래프의 연결 종류

| 종류 | 만들어지는 때 | 그래프 표시 |
|---|---|---|
| 직접 연결 | 저장할 때 "메모 연결"에서 체크 | 보라 화살표 |
| 내용 연결 | 저장 직후, 요약 임베딩의 코사인 유사도 ≥ 0.6인 논문·메모 | 회색 선 |
| 색인 | 저장할 때 고른 색인 | 금색 허브 노드 |

범례에서 종류별로 켜고 끌 수 있습니다.

## 중단/재시작

```bash
docker compose down        # 중단 (데이터 보존)
docker compose down -v     # 중단 + 데이터 삭제
docker compose up -d       # 재시작
```

`.env`를 바꿨으면 `docker compose up -d`로 컨테이너를 재생성해야 반영됩니다. `restart`로는 안 됩니다.

## GPU

호스트 Ollama가 GPU를 잡으면 그대로 씁니다. 확인:

```bash
curl http://localhost:11434/api/ps   # size_vram > 0 이면 GPU
```

- **AMD (Windows)**: 네이티브 Ollama가 ROCm으로 동작합니다. Docker로는 안 됩니다.
- **NVIDIA**: 네이티브도 되고, 컨테이너 Ollama를 쓰려면 `docker compose --profile docker-ollama up -d` 후 `docker-compose.yml`의 `ollama` 서비스에 `deploy.resources.reservations.devices`(driver: nvidia)를 추가합니다.
