from fastapi import FastAPI, HTTPException
from fastapi.responses import StreamingResponse
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
import httpx
import asyncpg
import redis.asyncio as aioredis
import json
import os
import hashlib
from typing import AsyncGenerator
import re

app = FastAPI(title="PKM AI Service")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

OLLAMA_BASE_URL = os.getenv("OLLAMA_BASE_URL", "http://ollama:11434")
SUMMARY_MODEL   = os.getenv("SUMMARY_MODEL", "exaone3.5:7.8b")
EMBED_MODEL     = os.getenv("EMBED_MODEL", "paraphrase-multilingual")
DATABASE_URL    = os.getenv("DATABASE_URL", "postgresql://pkm:pkm1234@postgres:5432/pkm")
REDIS_URL       = os.getenv("REDIS_URL", "redis://redis:6379")

# 모델을 VRAM에 상주시킨다. 7.8B는 5GB대라 내려갔다 올라오는 데만 5초가 걸려,
# 한동안 요약을 안 하다 누르면 체감이 두 배로 느려진다. -1은 무기한 유지.
KEEP_ALIVE = -1

# ── DB / Redis 연결 ────────────────────────────────────────
async def get_db():
    return await asyncpg.connect(DATABASE_URL)

async def get_redis():
    return await aioredis.from_url(REDIS_URL, decode_responses=True)


# ════════════════════════════════════════════════════════════
# 1. 요약 생성 — SSE 스트리밍
# ════════════════════════════════════════════════════════════
class SummarizeRequest(BaseModel):
    paper_id: str
    title: str
    abstract: str

async def ollama_stream(client: httpx.AsyncClient, prompt: str, num_predict: int):
    async with client.stream(
        "POST",
        f"{OLLAMA_BASE_URL}/api/generate",
        json={
            "model": SUMMARY_MODEL,
            "prompt": prompt,
            "stream": True,
            "options": {
                "temperature": 0.0,
                "top_p": 0.7,
                "num_predict": num_predict,
                "num_ctx": 4096,
            },
            "keep_alive": KEEP_ALIVE,
        },
    ) as resp:
        resp.raise_for_status()
        async for line in resp.aiter_lines():
            if line and (token := json.loads(line).get("response")):
                yield token


def clean_stream_token(token: str) -> str:
    # 스트리밍 경로는 normalize_korean_text를 통과하지 않으므로 여기서 걸러야 한다.
    # 토큰 경계에서 쪼개져도 안전하도록 문자 단위로만 지운다. (별표는 평문 렌더라 그대로 보임)
    return re.sub(r"[\u0400-\u04FF*]+", "", token)


async def stream_summary(title: str, abstract: str) -> AsyncGenerator[str, None]:
    async with httpx.AsyncClient(timeout=120) as client:
        draft = ""
        async for token in ollama_stream(client, build_summary_prompt(title, abstract), 800):
            draft += token
            visible = clean_stream_token(token)
            if visible:
                yield f"data: {json.dumps({'token': visible})}\n\n"

        # 영어가 섞였을 때만 다듬어 한 번에 교체한다. 토큰마다 replace를 보내면
        # 전문이 매번 실려 나가 이벤트가 급격히 커진다.
        if has_mixed_english(draft):
            yield f"data: {json.dumps({'replace': await ollama_generate(client, build_rewrite_prompt(draft), 800)})}\n\n"

    yield "data: [DONE]\n\n"


async def ollama_generate(client: httpx.AsyncClient, prompt: str, num_predict: int) -> str:
    resp = await client.post(
        f"{OLLAMA_BASE_URL}/api/generate",
        json={
            "model": SUMMARY_MODEL,
            "prompt": prompt,
            "stream": False,
            "options": {
                "temperature": 0.0,
                "top_p": 0.7,
                "num_predict": num_predict,
                "num_ctx": 4096,
            },
            "keep_alive": KEEP_ALIVE,
        },
    )
    resp.raise_for_status()
    return normalize_korean_text(resp.json().get("response", ""))


def build_summary_prompt(title: str, abstract: str) -> str:
    return f"""
당신은 영어 학술 논문을 한국어 독자가 이해하기 쉽게 풀어 설명하는 과학 해설자입니다.

아래 제목과 초록만 근거로 삼아 논문의 핵심을 자연스러운 한국어로 요약하세요.
단어 단위로 직역하지 말고, 논문의 문제의식, 방법, 결과, 의미를 먼저 이해한 뒤 한국어 문장으로 다시 설명하세요.

출력 형식:

[무엇을 연구했나]
- 논문이 다루는 문제나 질문을 2~3문장으로 설명하세요.

[어떻게 연구했나]
- 연구 대상, 자료, 방법, 실험 또는 분석 방식을 2~3문장으로 설명하세요.
- 초록에 방법이 명확히 없으면 억지로 만들지 말고 “초록에는 구체적인 방법이 자세히 설명되어 있지 않습니다.”라고 쓰세요.

[무엇을 발견했나]
- 주요 결과를 2~3문장으로 설명하세요.
- 수치, 조건, 비교 대상이 초록에 있으면 유지하세요.

[왜 중요한가]
- 이 결과가 갖는 의미나 활용 가능성을 1~2문장으로 설명하세요.
- 초록에 없는 사회적 의미나 응용 가능성을 지어내지 마세요.

작성 규칙:
- 반드시 한국어 문어체로 작성하세요.
- 영어 표현을 그대로 음역하지 말고 문맥에 맞게 풀어 쓰세요.
- 단, 널리 쓰이는 표준 약어, 고유명사, 모델명, 기법명은 필요하면 유지하세요. 예: DNA, MRI, GPT, Ising model, CRISPR
- 새로운 한국어 용어를 임의로 만들지 마세요. 애매한 개념은 쉬운 말로 풀어 설명하세요.
- 한 문장은 가능하면 60자 안팎으로 짧게 쓰세요.
- 번역투 표현을 피하세요. 예: “~에 의해 수행되었다”, “~을 보여주었습니다”를 남발하지 마세요.
- 논문에 없는 연구 방법, 결과, 원인, 의의를 추가하지 마세요.
- 의미가 불확실한 부분은 단정하지 말고 “초록상으로는”, “저자들은 ~라고 해석합니다”처럼 표현하세요.
- 마지막 문장이 끊기거나 어색한 상태로 끝나지 않게 하세요.

제목:
{title}

초록:
{abstract}

한국어 요약:
""".strip()


def build_rewrite_prompt(draft: str) -> str:
    return f"""
아래 요약문을 의미는 유지하되 자연스러운 한국어 문어체로 다시 쓰세요.

규칙:
- 영어 단어와 영어 구를 한국어로 풀어 쓰세요.
- 대괄호로 묶인 항목 제목은 글자 그대로 두고, 항목의 개수와 순서도 바꾸지 마세요.
- 대문자 약어는 필요한 경우만 남기세요.
- 깨진 외국어 조각, 번역투, 어색한 조사를 제거하세요.
- 새 사실을 추가하지 마세요.

요약문:
{draft}

다듬은 한국어 요약:
""".strip()


def has_mixed_english(text: str) -> bool:
    words = re.findall(r"\b[A-Za-z][A-Za-z\-]*\b", text)
    suspicious = []
    for word in words:
        if word.isupper() and len(word) >= 2:
            continue
        if any(ch.isupper() for ch in word) and "-" not in word:
            continue
        suspicious.append(word)

    return len(suspicious) >= 2 or any("-" in word for word in suspicious)


def normalize_korean_text(text: str) -> str:
    normalized = re.sub(r"[\u0400-\u04FF]+", "", text)
    normalized = re.sub(r"\*\*", "", normalized)   # 화면은 평문 렌더라 마크다운 강조가 그대로 보임
    normalized = re.sub(r"[ \t]{2,}", " ", normalized)
    normalized = re.sub(r" +([.,\]\)])", r"\1", normalized)
    normalized = re.sub(r"\n{3,}", "\n\n", normalized)
    return normalized.strip()


@app.post("/summarize/stream")
async def summarize_stream(req: SummarizeRequest):
    """요약 보기 클릭 시 호출 — SSE 스트리밍"""
    return StreamingResponse(
        stream_summary(req.title, req.abstract),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "X-Accel-Buffering": "no",
        },
    )


# ════════════════════════════════════════════════════════════
# 2. 임베딩 생성
# ════════════════════════════════════════════════════════════
class EmbedRequest(BaseModel):
    text: str

@app.post("/embed")
async def embed(req: EmbedRequest):
    """텍스트 → 768차원 벡터"""
    async with httpx.AsyncClient(timeout=60) as client:
        return {"embedding": await embed_text(client, req.text)}


# paraphrase-multilingual은 512 토큰이 상한이다(Modelfile 기본값은 128이라 num_ctx로 올려야 한다).
# 한국어 ~600자가 안전선이라, 긴 글은 조각내어 각각 임베딩한 뒤 평균을 낸다.
EMBED_NUM_CTX   = 512
EMBED_CHUNK_LEN = 600


async def embed_text(client: httpx.AsyncClient, text: str) -> list[float]:
    chunks = [text[i:i + EMBED_CHUNK_LEN] for i in range(0, max(len(text), 1), EMBED_CHUNK_LEN)]
    vectors = []
    for chunk in chunks:
        resp = await client.post(
            f"{OLLAMA_BASE_URL}/api/embeddings",
            json={"model": EMBED_MODEL, "prompt": chunk, "options": {"num_ctx": EMBED_NUM_CTX}},
        )
        resp.raise_for_status()
        vectors.append(resp.json()["embedding"])
    if len(vectors) == 1:
        return vectors[0]
    dim = len(vectors[0])
    mean = [sum(v[i] for v in vectors) / len(vectors) for i in range(dim)]
    norm = sum(x * x for x in mean) ** 0.5 or 1.0
    return [x / norm for x in mean]


# ════════════════════════════════════════════════════════════
# 3. 논문 ↔ Notion 메모 유사도 검색
# ════════════════════════════════════════════════════════════
class SimilarRequest(BaseModel):
    embedding: list[float]
    top_k: int = 5
    target: str = "notion"  # 'notion' | 'papers'
    similarity_threshold: float = 0.6
    use_mmr: bool = True


def _parse_vector(vec_str: str) -> list[float]:
    return [float(x) for x in vec_str.strip("[]").split(",")]


def _cosine_sim(a: list[float], b: list[float]) -> float:
    dot = sum(x * y for x, y in zip(a, b))
    na = sum(x * x for x in a) ** 0.5
    nb = sum(x * x for x in b) ** 0.5
    return dot / (na * nb) if na * nb > 0 else 0.0


def apply_mmr(candidates: list[dict], k: int, lambda_: float = 0.7) -> list[dict]:
    """Maximal Marginal Relevance — relevance와 diversity 균형 선택
    lambda_ 높을수록 relevance 우선, 낮을수록 다양성 우선
    """
    if not candidates:
        return []

    selected = []
    selected_embeddings = []
    remaining = list(candidates)

    while len(selected) < k and remaining:
        if not selected_embeddings:
            best = max(remaining, key=lambda c: c["similarity"])
        else:
            def mmr_score(c):
                sim_q = c["similarity"]
                emb = _parse_vector(c["emb"])
                max_sim_sel = max(_cosine_sim(emb, s) for s in selected_embeddings)
                return lambda_ * sim_q - (1 - lambda_) * max_sim_sel

            best = max(remaining, key=mmr_score)

        selected.append(best)
        selected_embeddings.append(_parse_vector(best["emb"]))
        remaining.remove(best)

    return selected


@app.post("/similar")
async def find_similar(req: SimilarRequest):
    """임베딩 기준 유사 항목 검색 — threshold 필터 + MMR 다양성 적용"""
    db = await get_db()
    try:
        vec_str = "[" + ",".join(map(str, req.embedding)) + "]"
        fetch_limit = req.top_k * 4

        if req.target == "notion":
            rows = await db.fetch(
                """
                SELECT id, notion_page_id, title,
                       embedding::text AS emb,
                       1 - (embedding <=> $1::vector) AS similarity
                FROM notion_pages
                WHERE embedding IS NOT NULL
                  AND 1 - (embedding <=> $1::vector) > $3
                ORDER BY embedding <=> $1::vector
                LIMIT $2
                """,
                vec_str, fetch_limit, req.similarity_threshold,
            )
        else:
            rows = await db.fetch(
                """
                SELECT id, external_id, title, source, full_text_url,
                       embedding::text AS emb,
                       1 - (embedding <=> $1::vector) AS similarity
                FROM papers
                WHERE embedding IS NOT NULL
                  AND 1 - (embedding <=> $1::vector) > $3
                ORDER BY embedding <=> $1::vector
                LIMIT $2
                """,
                vec_str, fetch_limit, req.similarity_threshold,
            )

        candidates = [dict(r) for r in rows]

        if req.use_mmr and len(candidates) >= 2:
            candidates = apply_mmr(candidates, k=req.top_k)
        else:
            candidates = candidates[:req.top_k]

        for c in candidates:
            c.pop("emb", None)

        return {"results": candidates}
    finally:
        await db.close()


# ════════════════════════════════════════════════════════════
# 4. Notion 메모 임베딩 캐시 (Redis TTL 1시간)
# ════════════════════════════════════════════════════════════
class NotionEmbedRequest(BaseModel):
    notion_page_id: str
    title: str
    content: str

@app.post("/notion/embed")
async def notion_embed(req: NotionEmbedRequest):
    """Notion 메모 임베딩 — Redis 캐시 우선"""
    redis = await get_redis()
   # 내용 기반 cache key
    content_hash = hashlib.md5(
        f"{req.title}:{req.content}".encode()
    ).hexdigest()

    # 모델명을 키에 넣는다. 안 넣으면 임베딩 모델을 바꿔도 옛 모델의 벡터가 캐시에서 나온다.
    cache_key = f"notion_embed:{EMBED_MODEL}:{content_hash}"

    # Redis cache 조회
    cached = await redis.get(cache_key)

    if cached:
        return {
            "embedding": json.loads(cached),
            "cached": True
        }

    # title을 3번 반복해서 embedding에서 주제가 희석되지 않도록
    MAX_LEN = 1000
    content = req.content[:MAX_LEN]
    text = f"{req.title}\n{req.title}\n{req.title}\n{content}"

    # Embedding 생성
    async with httpx.AsyncClient(timeout=60) as client:
        embedding = await embed_text(client, text)

    # Redis 저장
    await redis.setex(
        cache_key,
        3600,
        json.dumps(embedding)
    )

    # PostgreSQL 저장
    db = await get_db()

    try:
        await db.execute(
            """
            INSERT INTO notion_pages
            (
                notion_page_id,
                title,
                content,
                embedding,
                cached_at
            )
            VALUES
            (
                $1,
                $2,
                $3,
                $4::vector,
                NOW()
            )

            ON CONFLICT (notion_page_id)

            DO UPDATE SET
                title = EXCLUDED.title,
                content = EXCLUDED.content,
                embedding = EXCLUDED.embedding,
                cached_at = NOW()
            """,
            req.notion_page_id,
            req.title,
            req.content,
            "[" + ",".join(map(str, embedding)) + "]",
        )

    finally:
        await db.close()

    return {
        "embedding": embedding,
        "cached": False
    }

# ════════════════════════════════════════════════════════════
# 5. Thought Edge 저장
# ════════════════════════════════════════════════════════════
class EdgeRequest(BaseModel):
    source_id: str
    source_type: str   # 'paper' | 'notion'
    target_id: str
    target_type: str
    similarity: float
    edge_type: str     # 'semantic' | 'saved' | 'search_flow'
    target_title: str | None = None   # notion 쪽이 아직 notion_pages에 없을 때 만들 stub의 제목


async def resolve_notion_node(db, notion_id: str, title: str | None) -> str:
    """Notion 페이지 ID든 notion_pages.id든 받아서 notion_pages.id로 통일한다.

    thought_edges는 notion_pages.id를 가리켜야 그래프에서 노드를 찾을 수 있다.
    그런데 메모 연결로 들어오는 값은 Notion 페이지 ID라서, 임베딩된 적 없는 메모는
    notion_pages에 행이 없고 그래프가 'node not found'로 죽는다. 없으면 stub 행을 만든다.
    제목이 넘어오면 기존 값보다 우선한다 — Notion에서 이름을 바꾸면 그래프에도 반영되게.
    나중에 /notion/embed가 돌면 임베딩이 채워진다."""
    row = await db.fetchrow("SELECT id FROM notion_pages WHERE id::text = $1", notion_id)
    if row:
        return str(row["id"])
    compact = notion_id.replace("-", "")
    row = await db.fetchrow(
        "SELECT id FROM notion_pages WHERE replace(notion_page_id, '-', '') = $1", compact)
    if row:
        return str(row["id"])
    row = await db.fetchrow(
        """
        INSERT INTO notion_pages (notion_page_id, title)
        VALUES ($1, $2)
        ON CONFLICT (notion_page_id) DO UPDATE SET title = COALESCE(EXCLUDED.title, notion_pages.title)
        RETURNING id
        """,
        notion_id, title,
    )
    return str(row["id"])


@app.post("/edges")
async def create_edge(req: EdgeRequest):
    """Thought Graph 엣지 추가"""
    db = await get_db()
    try:
        source_id = req.source_id
        target_id = req.target_id
        if req.source_type == "notion":
            source_id = await resolve_notion_node(db, source_id, None)
        if req.target_type == "notion":
            target_id = await resolve_notion_node(db, target_id, req.target_title)

        await db.execute(
            """
            INSERT INTO thought_edges
              (source_id, source_type, target_id, target_type, similarity, edge_type)
            VALUES ($1, $2, $3, $4, $5, $6)
            ON CONFLICT DO NOTHING
            """,
            source_id, req.source_type,
            target_id, req.target_type,
            req.similarity, req.edge_type,
        )
        return {"ok": True}
    finally:
        await db.close()

@app.get("/edges/{node_id}")
async def get_edges(node_id: str):
    """특정 노드의 모든 연결 엣지"""
    db = await get_db()
    try:
        rows = await db.fetch(
            """
            SELECT * FROM thought_edges
            WHERE source_id = $1 OR target_id = $1
            ORDER BY similarity DESC
            """,
            node_id,
        )
        return {"edges": [dict(r) for r in rows]}
    finally:
        await db.close()


@app.get("/health")
async def health():
    return {"status": "ok"}
# ── main.py 하단에 추가할 Graph 엔드포인트 ──────────────────

# ════════════════════════════════════════════════════════════
# 6. Graph 전체 조회
# ════════════════════════════════════════════════════════════

def _label(title) -> str:
    # 메모 연결로 만들어진 stub 행은 제목이 비어 있을 수 있다.
    text = title or "(제목 미상)"
    return text[:60] + ("…" if len(text) > 60 else "")


def _paper_links(row) -> dict:
    """논문 노드에서 이동할 곳 — 저장된 Notion 페이지가 우선, 없으면 원문 URL·DOI"""
    return {
        "notionPageId": row["notion_page_id"],
        "fullTextUrl":  row["full_text_url"],
        "doi":          row["doi"],
    }


@app.get("/graph")
async def get_graph(limit: int = 50):
    """D3.js용 nodes + edges 반환"""
    db = await get_db()
    try:
        # 논문 노드
        paper_rows = await db.fetch(
            """
            SELECT p.id, p.title, p.source, p.external_id,
                   p.full_text_url, p.doi,
                   COUNT(sp.id) > 0 AS is_saved,
                   -- 같은 논문을 여러 번 저장했을 수 있으니 가장 최근 Notion 페이지 하나만
                   (SELECT notion_page_id FROM saved_papers
                    WHERE paper_id = p.id AND notion_page_id IS NOT NULL
                    ORDER BY created_at DESC LIMIT 1) AS notion_page_id
            FROM papers p
            LEFT JOIN saved_papers sp ON sp.paper_id = p.id
            GROUP BY p.id
            ORDER BY p.created_at DESC
            LIMIT $1
            """,
            limit,
        )

        # Notion 노드
        notion_rows = await db.fetch(
            """
            SELECT n.id, n.notion_page_id, n.title FROM notion_pages n
            WHERE NOT EXISTS (
              SELECT 1 FROM saved_papers sp
              WHERE replace(sp.notion_page_id, '-', '') = replace(n.notion_page_id, '-', '')
            )
            LIMIT $1
            """,
            limit,
        )

        # 엣지
        edge_rows = await db.fetch(
            """
            SELECT source_id, source_type, target_id, target_type,
                   similarity, edge_type
            FROM thought_edges
            ORDER BY similarity DESC
            LIMIT 200
            """,
        )

        # 색인 노드: 'index' 엣지의 대상. 일반 메모와 구분해 허브로 그린다.
        # LIMIT에 걸려 notion_rows에 빠졌을 수 있으므로 따로 가져와 합친다.
        index_ids = {str(r["target_id"]) for r in edge_rows if r["edge_type"] == "index"}
        index_rows = await db.fetch(
            "SELECT id, notion_page_id, title FROM notion_pages WHERE id::text = ANY($1::text[])",
            list(index_ids),
        ) if index_ids else []

        # 각 논문이 어떤 색인에 속하는지 — 툴팁·라벨용
        index_title = {str(r["id"]): (r["title"] or "(제목 미상)").strip() for r in index_rows}
        paper_indexes: dict[str, list[str]] = {}
        for r in edge_rows:
            if r["edge_type"] == "index":
                paper_indexes.setdefault(str(r["source_id"]), []).append(
                    index_title.get(str(r["target_id"]), "(제목 미상)"))

        nodes = []
        for r in paper_rows:
            nodes.append({
                "id":       str(r["id"]),
                "label":    _label(r["title"]),
                "type":     "paper",
                "source":   r["source"],
                "saved":    r["is_saved"],
                "indexes":  paper_indexes.get(str(r["id"]), []),
                **_paper_links(r),
            })
        seen = set()
        for r in index_rows:
            seen.add(str(r["id"]))
            nodes.append({
                "id":       str(r["id"]),
                "label":    _label(r["title"]),
                "type":     "index",
                "notionId": r["notion_page_id"],
            })
        for r in notion_rows:
            if str(r["id"]) in seen:
                continue
            nodes.append({
                "id":       str(r["id"]),
                "label":    _label(r["title"]),
                "type":     "notion",
                "notionId": r["notion_page_id"],
            })

        edges = [
            {
                "source":    str(r["source_id"]),
                "target":    str(r["target_id"]),
                "similarity": float(r["similarity"]),
                "edgeType":  r["edge_type"],
            }
            for r in edge_rows
        ]

        return {"nodes": nodes, "edges": edges}
    finally:
        await db.close()


@app.get("/graph/node/{node_id}")
async def get_node_graph(node_id: str):
    """특정 노드 중심 서브그래프"""
    db = await get_db()
    try:
        edge_rows = await db.fetch(
            """
            SELECT source_id, source_type, target_id, target_type,
                   similarity, edge_type
            FROM thought_edges
            WHERE source_id = $1 OR target_id = $1
            ORDER BY similarity DESC
            """,
            node_id,
        )

        # 연결된 노드 IDs 수집
        neighbor_ids = set()
        neighbor_ids.add(node_id)
        for r in edge_rows:
            neighbor_ids.add(str(r["source_id"]))
            neighbor_ids.add(str(r["target_id"]))

        index_ids = {str(r["target_id"]) for r in edge_rows if r["edge_type"] == "index"}

        # 노드 상세 조회
        nodes = []
        for nid in neighbor_ids:
            paper = await db.fetchrow(
                """
                SELECT p.id, p.title, p.source, p.full_text_url, p.doi,
                       (SELECT notion_page_id FROM saved_papers
                        WHERE paper_id = p.id AND notion_page_id IS NOT NULL
                        ORDER BY created_at DESC LIMIT 1) AS notion_page_id
                FROM papers p WHERE p.id = $1
                """,
                nid,
            )
            if paper:
                nodes.append({
                    "id": str(paper["id"]), "label": _label(paper["title"]),
                    "type": "paper", "source": paper["source"],
                    **_paper_links(paper),
                })
                continue
            notion = await db.fetchrow(
                "SELECT id, title FROM notion_pages WHERE id = $1", nid
            )
            if notion:
                nodes.append({
                    "id": str(notion["id"]), "label": _label(notion["title"]),
                    "type": "index" if nid in index_ids else "notion",
                })

        edges = [
            {
                "source":    str(r["source_id"]),
                "target":    str(r["target_id"]),
                "similarity": float(r["similarity"]),
                "edgeType":  r["edge_type"],
            }
            for r in edge_rows
        ]

        return {"nodes": nodes, "edges": edges}
    finally:
        await db.close()


# ════════════════════════════════════════════════════════════
# 7. 재발견 (Rediscovery)
#
# 트리거: 저장 직후 호출
# 로직:
#   - 방금 저장한 논문 임베딩 기준
#   - 기간 제한 없이 전체 노드(논문/메모) 중 유사도 높은 것 탐색
#   - 기존 엣지가 없는 새 연결만 반환 ("예상 밖의 연결")
# ════════════════════════════════════════════════════════════

class RediscoverRequest(BaseModel):
    paper_id: str        # 방금 저장한 논문 DB UUID
    embedding: list[float]
    top_k: int = 5
    similarity_threshold: float = 0.6    # 이 이상만 의미있는 연결로 판단 (paraphrase-multilingual 기준)

@app.post("/rediscover")
async def rediscover(req: RediscoverRequest):
    """
    저장 직후 호출 — 내용(임베딩)이 비슷한 노드와 연결
    기간 제한 없이 전체를 대상으로 하고, 이미 엣지가 있는 것은 제외한다
    """
    db = await get_db()
    try:
        vec_str = "[" + ",".join(map(str, req.embedding)) + "]"

        # 내용이 비슷한 논문 (이미 연결된 것 제외)
        old_papers = await db.fetch(
            f"""
            SELECT p.id, p.title, p.source, p.external_id,
                   1 - (p.embedding <=> $1::vector) AS similarity
            FROM papers p
            WHERE p.embedding IS NOT NULL
              AND p.id != $2::uuid
              AND NOT EXISTS (
                SELECT 1 FROM thought_edges e
                WHERE (e.source_id = $2::uuid AND e.target_id = p.id)
                   OR (e.source_id = p.id     AND e.target_id = $2::uuid)
              )
            ORDER BY p.embedding <=> $1::vector
            LIMIT $3
            """,
            vec_str, req.paper_id, req.top_k,
        )

        # 내용이 비슷한 Notion 메모 (이미 연결된 것 제외)
        old_notions = await db.fetch(
            f"""
            SELECT n.id, n.title, n.notion_page_id,
                   1 - (n.embedding <=> $1::vector) AS similarity
            FROM notion_pages n
            WHERE n.embedding IS NOT NULL
              -- 저장된 논문의 Notion 페이지는 메모가 아니다. 논문이 자기 사본과 이어지는 걸 막는다.
              AND NOT EXISTS (
                SELECT 1 FROM saved_papers sp
                WHERE replace(sp.notion_page_id, '-', '') = replace(n.notion_page_id, '-', '')
              )
              AND NOT EXISTS (
                SELECT 1 FROM thought_edges e
                WHERE (e.source_id = $2::uuid AND e.target_id = n.id)
                   OR (e.source_id = n.id     AND e.target_id = $2::uuid)
              )
            ORDER BY n.embedding <=> $1::vector
            LIMIT $3
            """,
            vec_str, req.paper_id, req.top_k,
        )

        discoveries = []

        for r in old_papers:
            sim = float(r["similarity"])
            if sim < req.similarity_threshold:
                continue
            discoveries.append({
                "id":         str(r["id"]),
                "type":       "paper",
                "title":      r["title"],
                "source":     r["source"],
                "externalId": r["external_id"],
                "similarity": round(sim, 3),
                "message":    _discovery_message(sim, "paper", r["title"]),
            })

        for r in old_notions:
            sim = float(r["similarity"])
            if sim < req.similarity_threshold:
                continue
            discoveries.append({
                "id":         str(r["id"]),
                "type":       "notion",
                "title":      r["title"],
                "notionId":   r["notion_page_id"],
                "similarity": round(sim, 3),
                "message":    _discovery_message(sim, "notion", r["title"]),
            })

        # 유사도 내림차순 정렬
        discoveries.sort(key=lambda x: x["similarity"], reverse=True)

        # 새 연결 엣지로 자동 등록
        for d in discoveries:
            await db.execute(
                """
                INSERT INTO thought_edges
                  (source_id, source_type, target_id, target_type, similarity, edge_type)
                VALUES ($1, 'paper', $2, $3, $4, 'semantic')
                ON CONFLICT DO NOTHING
                """,
                req.paper_id, d["id"], d["type"], d["similarity"],
            )

        return {
            "discoveries": discoveries,
            "count":       len(discoveries),
        }
    finally:
        await db.close()


def _discovery_message(sim: float, node_type: str, title: str) -> str:
    label = "메모" if node_type == "notion" else "논문"
    short = title[:30] + ("…" if len(title) > 30 else "")
    if sim >= 0.85:
        return f"강한 연결 발견 — '{short}' ({label})"
    elif sim >= 0.75:
        return f"관련 {label} 재발견 — '{short}'"
    else:
        return f"숨겨진 연결 — '{short}' ({label})"


# ════════════════════════════════════════════════════════════
# 8. 검색어 영어 변환 — 논문 검색 API 호출 전 전처리
#
# Semantic Scholar / OpenAlex / arXiv 등은 영어 쿼리에서만 정상 동작하므로,
# 한글이 섞인 검색어를 백엔드가 이 엔드포인트로 보내 영어 키워드로 바꾼다.
# ════════════════════════════════════════════════════════════
class TranslateQueryRequest(BaseModel):
    query: str

@app.post("/translate/query")
async def translate_query(req: TranslateQueryRequest):
    """검색어를 영어 학술 검색 키워드로 변환"""
    async with httpx.AsyncClient(timeout=30) as client:
        raw = await ollama_generate(client, build_translate_query_prompt(req.query), 60)
    return {"translated": clean_translated_query(raw)}


def build_translate_query_prompt(query: str) -> str:
    return f"""
Translate the following academic search query into English search terms.

Rules:
- Translate ONLY the concepts that appear in the query.
- Do NOT add synonyms, related terms, or any word that is not in the query.
- Drop particles and filler words such as 관계, 영향, 연구, 사례 (relationship, effect, study, case).
- Output at most 3 words on one line — no quotes, no explanation, no punctuation.

Examples:
Query: 폭력
English: violence
Query: 청소년 우울증과 스마트폰 사용의 관계
English: adolescent depression smartphone

Query: {query}

English:
""".strip()


# 한국어 문장을 번역하면 따라 붙지만 검색에는 방해가 되는 군더더기.
# 검색어가 전부 AND로 묶이므로 이런 단어 하나가 결과를 0건으로 만든다.
FILLER_TERMS = {
    "relationship", "relationships", "correlation", "correlations",
    "association", "associations", "impact", "impacts", "effect", "effects",
    "influence", "role", "study", "studies", "research", "analysis",
    "review", "case", "cases", "use", "usage",
}


def clean_translated_query(text: str) -> str:
    first_line = text.strip().splitlines()[0] if text.strip() else ""
    words = first_line.strip(" \"'.,").split()
    kept = [w for w in words if w.lower().strip(".,") not in FILLER_TERMS]
    return " ".join(kept or words)
