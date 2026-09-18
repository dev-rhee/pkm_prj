const BASE = process.env.NEXT_PUBLIC_API_URL ?? 'http://localhost:8080'

export interface Paper {
  externalId: string
  source: 'arxiv' | 'core' | 'pmc' | 'semantic_scholar' | 'openalex'
  title: string
  authors: string[]
  abstractText: string
  fullTextUrl: string
  publishedAt?: string
  citationCount?: number
  year?: number
  doi?: string
}

export interface NotionPage {
  id: string
  title: string
  indexIds?: string[]   // 메모가 속한 색인 페이지 ID들 (색인 페이지 자신은 빈 배열)
}

export interface SavePayload {
  title: string
  summary: string
  fullTextUrl: string
  source: string
  externalId: string
  tags: string[]
  linkedNotionIds: string[]
  indexNotionIds: string[]
}

// ── 논문 검색 ─────────────────────────────────────────────
export async function searchPapers(query: string, perSource = 50): Promise<Paper[]> {
  const res = await fetch(
    `${BASE}/api/papers/search?q=${encodeURIComponent(query)}&perSource=${perSource}`
  )
  if (!res.ok) throw new Error('검색 실패')
  return res.json()
}

// ── 요약 SSE 스트리밍 ──────────────────────────────────────
// onToken: 토큰마다 호출, onDone: 완료 시 전체 요약 반환
export async function streamSummary(
  externalId: string,
  title: string,
  abstract: string,
  onToken: (token: string, mode?: 'append' | 'replace') => void,
  onDone: (full: string) => void
) {
  const res = await fetch(`${BASE}/api/papers/${encodeURIComponent(externalId)}/summarize`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title, abstract }),
  })

  const reader = res.body!.getReader()
  const decoder = new TextDecoder()
  let full = ''
  let buffer = ''

  while (true) {
    const { done, value } = await reader.read()
    if (done) break

    // stream: true — 멀티바이트 문자가 청크 경계에서 쪼개져도 깨지지 않게 한다
    buffer += decoder.decode(value, { stream: true })

    // 마지막 조각은 줄 중간에서 잘렸을 수 있으므로 버퍼에 남겨 다음 청크와 이어 붙인다
    const lines = buffer.split('\n')
    buffer = lines.pop() ?? ''

    for (const line of lines) {
      if (!line.startsWith('data:')) continue
      const raw = line.slice(5).trim()
      if (!raw) continue
      if (raw === '[DONE]') { onDone(full); return }
      try {
        const { token, replace } = JSON.parse(raw)
        if (replace) {
          full = replace
          onToken(replace, 'replace')
          continue
        }
        if (token) { full += token; onToken(token, 'append') }
      } catch {}
    }
  }
  onDone(full)
}

// ── 저장 ──────────────────────────────────────────────────
export async function savePaper(externalId: string, payload: SavePayload) {
  const res = await fetch(`${BASE}/api/papers/${encodeURIComponent(externalId)}/save`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  if (!res.ok) throw new Error('저장 실패')
  return res.json()
}

// ── Notion 메모 목록 ───────────────────────────────────────
export async function listNotionPages(): Promise<NotionPage[]> {
  const res = await fetch(`${BASE}/api/papers/notion/pages`)
  if (!res.ok) return []
  return res.json()
}

// ── Notion 색인 목록 (저장 시 선택용) ─────────────────────
export async function listIndexPages(): Promise<NotionPage[]> {
  const res = await fetch(`${BASE}/api/papers/notion/index`)
  if (!res.ok) return []
  return res.json()
}

// ── Notion 메모 → 논문 추천 ───────────────────────────────
export async function recommendFromNotion(
  notionPageId: string,
  title: string,
  content: string
): Promise<Paper[]> {
  const res = await fetch(`${BASE}/api/papers/notion/recommend`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ notionPageId, title, content }),
  })
  if (!res.ok) return []
  return res.json()
}
