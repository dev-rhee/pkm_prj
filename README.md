# PKM — 로컬 개인 지식 관리 시스템

완전 무료 · 완전 로컬 · 유료 API 없음

## 스택

| 역할 | 기술 |
|---|---|
| API Gateway | Spring Boot 3 (Java 21) |
| AI 서비스 | FastAPI + Python 3.11 |
| LLM / 임베딩 | Ollama (llama3.2:3b + nomic-embed-text) |
| 논문 검색 | arXiv · CORE · PubMed Central (무료) |
| DB | PostgreSQL 16 + pgvector |
| 캐시 | Redis 7 |
| 프론트엔드 | Next.js (별도 구성) |

## 시작하기

### 1. 환경변수 설정

```bash
cp .env.example .env
# .env 열어서 NOTION_API_KEY 입력
```

Notion Integration Token 발급:
1. https://www.notion.so/my-integrations 접속
2. New integration 생성
3. 생성된 Secret 복사 → .env에 붙여넣기
4. Notion에서 공유할 페이지에 integration 연결 (페이지 우상단 ··· → Connect to)

### 2. 실행

```bash
docker compose up -d
```

처음 실행 시 Ollama 모델 다운로드 (~2GB, 5~10분 소요)

### 3. 확인

```bash
# 서비스 상태
docker compose ps

# AI 서비스 헬스체크
curl http://localhost:8000/health

# Spring Boot 헬스체크
curl http://localhost:8080/actuator/health

# Ollama 모델 확인
docker exec pkm-ollama ollama list
```

### 4. 로그 보기

```bash
docker compose logs -f ai-service    # FastAPI
docker compose logs -f backend       # Spring Boot
docker compose logs -f ollama-setup  # 모델 설치 진행상황
```

## API 엔드포인트

### 논문 검색
```
GET /api/papers/search?q=attention+mechanism&perSource=5
```

### 요약 보기 (SSE 스트리밍)
```
POST /api/papers/{externalId}/summarize
Body: { "title": "...", "abstract": "..." }
→ text/event-stream 으로 토큰 단위 응답
```

### 저장 (요약본 기준)
```
POST /api/papers/{externalId}/save
Body: {
  "title": "...",
  "summary": "이미 생성된 요약본",   ← Ollama 재호출 없음
  "fullTextUrl": "...",
  "source": "arxiv",
  "tags": ["AI", "NLP"],
  "linkedNotionIds": ["notion-page-id-1"]
}
```

### Notion 메모 목록
```
GET /api/papers/notion/pages
```

### Notion 메모 → 논문 추천
```
POST /api/papers/notion/recommend
Body: {
  "notionPageId": "...",
  "title": "내 메모 제목",
  "content": "메모 내용"
}
```

## 흐름 A — 검색 → 저장

1. `GET /search?q=키워드` → 논문 목록
2. 원문 링크 클릭 → 브라우저에서 직접 읽기
3. `POST /summarize` 클릭 → SSE로 요약 스트리밍
4. `POST /save` 클릭 → 요약본 그대로 Notion 저장 + Graph 업데이트

## 흐름 B — Notion 메모 → 추천 → (선택적) 저장

1. `GET /notion/pages` → 내 메모 목록
2. 메모 선택 → `POST /notion/recommend` → 관련 논문 5개
3. 원하는 논문 요약 보기 (선택)
4. 저장 버튼 → 흐름 A의 저장과 동일

## 중단/재시작

```bash
docker compose down        # 중단 (데이터 보존)
docker compose down -v     # 중단 + 데이터 삭제
docker compose up -d       # 재시작
```

## GPU 사용 (선택, Ollama 속도 향상)

`docker-compose.yml`에서 ollama 서비스의 deploy 주석 해제:

```yaml
deploy:
  resources:
    reservations:
      devices:
        - driver: nvidia
          count: 1
          capabilities: [gpu]
```
