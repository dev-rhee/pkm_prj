'use client'

import { useState } from 'react'
import { streamSummary, savePaper, type Paper, type NotionPage } from '@/lib/api'

interface Props {
  paper: Paper
  notionPages: NotionPage[]
  indexPages: NotionPage[]
  index: number
  onSaved?: (result: Record<string, unknown>) => void
}

const SOURCE_LABEL: Record<string, string> = {
  arxiv:            'arXiv',
  core:             'CORE',
  pmc:              'PMC',
  semantic_scholar: 'S2',
  openalex:         'OpenAlex',
}

export default function PaperCard({ paper, notionPages, indexPages, index, onSaved }: Props) {
  const [summary, setSummary]         = useState<string | null>(null)
  const [streaming, setStreaming]     = useState(false)
  const [saving, setSaving]           = useState(false)
  const [saved, setSaved]             = useState(false)
  const [showLink, setShowLink]       = useState(false)
  const [selectedMemos, setSelectedMemos] = useState<string[]>([])
  const [selectedIndex, setSelectedIndex] = useState('')
  const [error, setError]             = useState('')

  // ── 요약 보기 클릭 ────────────────────────────────────
  async function handleSummary() {
    if (summary || streaming) return
    setStreaming(true)
    setSummary('')
    try {
      await streamSummary(
        paper.externalId,
        paper.title,
        paper.abstractText ?? '',
        (token, mode) => setSummary(prev => mode === 'replace' ? token : (prev ?? '') + token),
        (full)  => { setSummary(full); setStreaming(false) }
      )
    } catch {
      setError('요약 생성 실패')
      setStreaming(false)
    }
  }

  // ── 저장 클릭 ─────────────────────────────────────────
  async function handleSave() {
    setSaving(true)
    setError('')
    try {
      // 요약 없으면 먼저 생성
      let finalSummary = summary
      if (!finalSummary) {
        await new Promise<void>((resolve) => {
          streamSummary(
            paper.externalId,
            paper.title,
            paper.abstractText ?? '',
            (token, mode) => setSummary(prev => mode === 'replace' ? token : (prev ?? '') + token),
            (full)  => { finalSummary = full; setSummary(full); resolve() }
          )
        })
      }

      const result = await savePaper(paper.externalId, {
        title:           paper.title,
        summary:         finalSummary!,
        fullTextUrl:     paper.fullTextUrl,
        source:          paper.source,
        externalId:      paper.externalId,
        tags:            [],
        linkedNotionIds: selectedMemos,
        indexNotionIds:  selectedIndex ? [selectedIndex] : [],
      })
      setSaved(true)
      onSaved?.(result)
    } catch {
      setError('저장 실패. 다시 시도해주세요.')
    } finally {
      setSaving(false)
    }
  }

  function toggleMemo(id: string) {
    setSelectedMemos(prev =>
      prev.includes(id) ? prev.filter(m => m !== id) : [...prev, id]
    )
  }

  return (
    <article
      className="paper-card fade-up"
      style={{ animationDelay: `${index * 60}ms` }}
    >
      {/* 헤더 */}
      <div className="card-header">
        <span className={`source-badge source-${paper.source}`}>
          {SOURCE_LABEL[paper.source] ?? paper.source}
        </span>
        {paper.year && (
          <span className="meta-chip mono">{paper.year}</span>
        )}
        {paper.citationCount != null && paper.citationCount > 0 && (
          <span className="meta-chip mono">인용 {paper.citationCount.toLocaleString()}</span>
        )}
        <a
          href={paper.fullTextUrl}
          target="_blank"
          rel="noopener noreferrer"
          className="full-text-link mono"
        >
          원문 ↗
        </a>
      </div>

      {/* 제목 */}
      <h2 className="card-title serif">{paper.title}</h2>

      {/* 저자 */}
      {paper.authors?.length > 0 && (
        <p className="card-authors muted mono">
          {paper.authors.slice(0, 3).join(', ')}
          {paper.authors.length > 3 && ' 외'}
        </p>
      )}

      {/* 초록 미리보기 */}
      {paper.abstractText && (
        <p className="card-abstract muted">
          {paper.abstractText.slice(0, 200)}
          {paper.abstractText.length > 200 && '…'}
        </p>
      )}

      {/* 요약 영역 */}
      {summary !== null && (
        <div className="summary-box">
          <span className="summary-label mono">AI 요약</span>
          <p className="summary-text">
            {summary}
            {streaming && <span className="cursor pulse">▋</span>}
          </p>
        </div>
      )}

      {error && <p className="error-msg">{error}</p>}

      {/* 액션 버튼 */}
      <div className="card-actions">
        {/* 요약 보기 */}
        {!saved && (
          <button
            className="btn btn-secondary"
            onClick={handleSummary}
            disabled={streaming || summary !== null}
          >
            {streaming ? (
              <><span className="pulse">●</span> 요약 중…</>
            ) : summary !== null ? (
              '요약 완료'
            ) : (
              '요약 보기'
            )}
          </button>
        )}

        {/* 색인 선택 — Notion DB의 '색인' relation에 들어간다 */}
        {!saved && indexPages.length > 0 && (
          <select
            className="index-select mono"
            value={selectedIndex}
            onChange={e => setSelectedIndex(e.target.value)}
            aria-label="색인 선택"
          >
            <option value="">색인 없음</option>
            {indexPages.map(page => (
              <option key={page.id} value={page.id}>{page.title}</option>
            ))}
          </select>
        )}

        {/* Notion 메모 연결 선택 */}
        {!saved && notionPages.length > 0 && (
          <button
            className="btn btn-ghost"
            onClick={() => setShowLink(v => !v)}
          >
            {selectedMemos.length > 0
              ? `메모 ${selectedMemos.length}개 선택됨`
              : '메모 연결'}
          </button>
        )}

        {/* 저장 */}
        {!saved ? (
          <button
            className="btn btn-primary"
            onClick={handleSave}
            disabled={saving}
          >
            {saving ? '저장 중…' : 'Notion에 저장'}
          </button>
        ) : (
          <span className="saved-badge">✓ 저장됨</span>
        )}
      </div>

      {/* 메모 선택 드롭다운 */}
      {showLink && !saved && (
        <div className="memo-list fade-up">
          <p className="memo-list-label muted mono">연결할 Notion 메모 선택</p>
          {notionPages.map(page => (
            <label key={page.id} className="memo-item">
              <input
                type="checkbox"
                checked={selectedMemos.includes(page.id)}
                onChange={() => toggleMemo(page.id)}
              />
              <span>{page.title}</span>
            </label>
          ))}
        </div>
      )}

      <style jsx>{`
        .paper-card {
          background: var(--bg2);
          border: 1px solid var(--border);
          border-radius: var(--radius);
          padding: 20px 24px;
          display: flex;
          flex-direction: column;
          gap: 10px;
          transition: border-color 0.2s;
        }
        .paper-card:hover { border-color: #44445a; }

        .card-header {
          display: flex;
          align-items: center;
          gap: 10px;
        }
        .source-badge {
          font-family: var(--font-mono);
          font-size: 11px;
          padding: 2px 8px;
          border-radius: 4px;
          font-weight: 500;
          letter-spacing: 0.05em;
        }
        .source-arxiv            { background: #1a1a3a; color: #7c9fff; border: 1px solid #2a2a5a; }
        .source-core             { background: #1a2a1a; color: #6fcf6f; border: 1px solid #2a4a2a; }
        .source-pmc              { background: #2a1a1a; color: #ff9f7c; border: 1px solid #4a2a2a; }
        .source-semantic_scholar { background: #1a2030; color: #9fc4ff; border: 1px solid #2a3050; }
        .source-openalex         { background: #201a30; color: #c49fff; border: 1px solid #342a50; }

        .meta-chip {
          font-size: 11px;
          color: var(--muted);
          padding: 2px 6px;
          background: var(--bg3);
          border: 1px solid var(--border);
          border-radius: 4px;
        }

        .full-text-link {
          font-size: 12px;
          color: var(--accent2);
          margin-left: auto;
        }
        .full-text-link:hover { text-decoration: underline; }

        .card-title {
          font-size: 17px;
          font-weight: 400;
          line-height: 1.4;
          color: var(--text);
        }
        .card-authors { font-size: 12px; }
        .card-abstract { font-size: 13px; line-height: 1.6; }

        .summary-box {
          background: var(--bg3);
          border-left: 2px solid var(--accent);
          border-radius: 0 6px 6px 0;
          padding: 12px 16px;
          margin-top: 4px;
        }
        .summary-label {
          font-size: 10px;
          color: var(--accent);
          letter-spacing: 0.1em;
          text-transform: uppercase;
          display: block;
          margin-bottom: 6px;
        }
        .summary-text {
          font-size: 14px;
          line-height: 1.7;
          color: var(--text);
        }
        .cursor { margin-left: 2px; }

        .error-msg { font-size: 12px; color: var(--danger); }

        .card-actions {
          display: flex;
          align-items: center;
          gap: 8px;
          flex-wrap: wrap;
          margin-top: 4px;
        }

        .btn {
          font-family: var(--font-mono);
          font-size: 12px;
          padding: 7px 14px;
          border-radius: 6px;
          border: none;
          cursor: pointer;
          transition: opacity 0.15s, transform 0.1s;
          white-space: nowrap;
        }
        .btn:disabled { opacity: 0.45; cursor: not-allowed; }
        .btn:not(:disabled):active { transform: scale(0.97); }

        .btn-primary {
          background: var(--accent);
          color: #fff;
        }
        .btn-primary:not(:disabled):hover { opacity: 0.85; }

        .btn-secondary {
          background: var(--bg3);
          color: var(--text);
          border: 1px solid var(--border);
        }
        .btn-secondary:not(:disabled):hover { border-color: var(--accent); color: var(--accent); }

        .index-select {
          font-size: 12px;
          padding: 7px 10px;
          border-radius: 6px;
          background: var(--bg3);
          color: var(--text);
          border: 1px solid var(--border);
          cursor: pointer;
          max-width: 220px;
        }
        .index-select:hover { border-color: var(--accent); }

        .btn-ghost {
          background: transparent;
          color: var(--muted);
          border: 1px solid var(--border);
        }
        .btn-ghost:hover { color: var(--text); border-color: #44445a; }

        .saved-badge {
          font-family: var(--font-mono);
          font-size: 12px;
          color: var(--accent2);
        }

        .memo-list {
          background: var(--bg3);
          border: 1px solid var(--border);
          border-radius: 8px;
          padding: 12px 14px;
          display: flex;
          flex-direction: column;
          gap: 6px;
          max-height: 200px;
          overflow-y: auto;
        }
        .memo-list-label {
          font-size: 11px;
          letter-spacing: 0.08em;
          margin-bottom: 4px;
        }
        .memo-item {
          display: flex;
          align-items: center;
          gap: 8px;
          font-size: 13px;
          cursor: pointer;
          padding: 3px 0;
        }
        .memo-item input { accent-color: var(--accent); cursor: pointer; }
        .memo-item:hover span { color: var(--accent); }
      `}</style>
    </article>
  )
}
