-- pgvector 확장 활성화
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ── 사용자 ────────────────────────────────────────────────
CREATE TABLE users (
  id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  email        TEXT UNIQUE NOT NULL,
  created_at   TIMESTAMPTZ DEFAULT NOW()
);

-- ── 논문 ──────────────────────────────────────────────────
CREATE TABLE papers (
  id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  external_id     TEXT UNIQUE NOT NULL,   -- s2:, oa:, arxiv:, core:, pmc: 접두사
  source          TEXT NOT NULL,          -- 'semantic_scholar' | 'openalex' | 'arxiv' | 'core' | 'pmc'
  title           TEXT NOT NULL,
  authors         TEXT[],
  abstract        TEXT,
  full_text_url   TEXT,                   -- 원문 PDF/HTML URL
  published_at    DATE,
  citation_count  INTEGER DEFAULT 0,      -- Semantic Scholar / OpenAlex 인용 수
  year            INTEGER,
  doi             TEXT,
  embedding       vector(768),            -- nomic-embed-text
  created_at      TIMESTAMPTZ DEFAULT NOW()
);

-- ── 요약 ──────────────────────────────────────────────────
CREATE TABLE summaries (
  id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  paper_id    UUID NOT NULL REFERENCES papers(id) ON DELETE CASCADE,
  content     TEXT NOT NULL,              -- Ollama 생성 요약본
  created_at  TIMESTAMPTZ DEFAULT NOW(),
  UNIQUE(paper_id)
);

-- ── Notion 메모 캐시 ───────────────────────────────────────
CREATE TABLE notion_pages (
  id               UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  notion_page_id   TEXT UNIQUE NOT NULL,
  title            TEXT,
  content          TEXT,
  embedding        vector(768),
  cached_at        TIMESTAMPTZ DEFAULT NOW()
);

-- ── 검색 이력 ─────────────────────────────────────────────
CREATE TABLE search_history (
  id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  query       TEXT NOT NULL,
  embedding   vector(768),
  created_at  TIMESTAMPTZ DEFAULT NOW()
);

-- ── Thought Graph 엣지 ────────────────────────────────────
-- source/target은 papers.id 또는 notion_pages.id
CREATE TABLE thought_edges (
  id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  source_id     UUID NOT NULL,
  source_type   TEXT NOT NULL,   -- 'paper' | 'notion'
  target_id     UUID NOT NULL,
  target_type   TEXT NOT NULL,   -- 'paper' | 'notion'
  similarity    FLOAT NOT NULL,
  edge_type     TEXT NOT NULL,   -- 'semantic' | 'saved' | 'search_flow'
  created_at    TIMESTAMPTZ DEFAULT NOW()
);

-- ── 저장된 논문 (사용자가 저장 버튼 누른 것) ───────────────
CREATE TABLE saved_papers (
  id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  paper_id            UUID NOT NULL REFERENCES papers(id) ON DELETE CASCADE,
  summary_id          UUID REFERENCES summaries(id),
  notion_page_id      TEXT,               -- 저장된 Notion 페이지 ID
  linked_notion_ids   TEXT[],             -- 연결된 Notion 메모 IDs
  created_at          TIMESTAMPTZ DEFAULT NOW()
);

-- ── 인덱스 ────────────────────────────────────────────────
-- hnsw: ivfflat은 lists(100)보다 행이 적으면 빈 구역을 뒤져 0건을 돌려준다. hnsw는 학습 단계가 없어 소량에서도 정확하다.
CREATE INDEX ON papers USING hnsw (embedding vector_cosine_ops);
CREATE INDEX ON notion_pages USING hnsw (embedding vector_cosine_ops);
CREATE INDEX ON thought_edges (source_id);
CREATE INDEX ON thought_edges (target_id);
CREATE INDEX ON papers (source);
CREATE INDEX ON papers (external_id);

-- 기본 사용자 (로컬 단독 사용)
INSERT INTO users (email) VALUES ('local@pkm.dev');
