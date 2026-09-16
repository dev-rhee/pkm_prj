'use client'

import { useState } from 'react'
import PaperCard from '@/components/PaperCard'
import { recommendFromNotion, type NotionPage, type Paper } from '@/lib/api'

interface Props {
  notionPages: NotionPage[]
  indexPages: NotionPage[]
  onSaved?: (result: Record<string, unknown>) => void
}

export default function NotionPanel({ notionPages, indexPages, onSaved }: Props) {
  const [selected, setSelected]     = useState<NotionPage | null>(null)
  const [papers, setPapers]         = useState<Paper[]>([])
  const [loading, setLoading]       = useState(false)
  const [searched, setSearched]     = useState(false)

  async function handleSelect(page: NotionPage) {
    setSelected(page)
    setPapers([])
    setSearched(false)
    setLoading(true)
    try {
      // 내용은 선택 시점에 Notion에서 가져옴 (캐시 우선)
      const results = await recommendFromNotion(page.id, page.title, '')
      setPapers(results)
      setSearched(true)
    } catch {
      setSearched(true)
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="notion-shell">
      {/* 메모 목록 */}
      <aside className="memo-sidebar">
        <h2 className="sidebar-title mono">내 Notion 메모</h2>
        {notionPages.length === 0 ? (
          <p className="muted" style={{ fontSize: 13, padding: '12px 0' }}>
            메모 없음 — Notion integration 확인
          </p>
        ) : (
          <ul className="memo-list">
            {notionPages.map(page => (
              <li key={page.id}>
                <button
                  className={`memo-btn ${selected?.id === page.id ? 'active' : ''}`}
                  onClick={() => handleSelect(page)}
                >
                  <span className="memo-dot">◦</span>
                  <span className="memo-title">{page.title}</span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </aside>

      {/* 추천 결과 */}
      <section className="recommend-area">
        {!selected && (
          <div className="empty-state">
            <p className="serif empty-title">메모를 선택하면</p>
            <p className="muted">관련 논문을 추천해드려요</p>
          </div>
        )}

        {selected && (
          <>
            <div className="recommend-header">
              <h2 className="serif page-title">
                <span className="muted" style={{ fontSize: 16 }}>관련 논문 — </span>
                {selected.title}
              </h2>
            </div>

            {loading && (
              <p className="status muted mono">
                <span className="pulse">●</span>&nbsp; 관련 논문 검색 중…
              </p>
            )}

            {!loading && searched && papers.length === 0 && (
              <p className="status muted mono">관련 논문을 찾지 못했어요</p>
            )}

            <div className="paper-list">
              {papers.map((paper, i) => (
                <PaperCard
                  key={paper.externalId}
                  paper={paper}
                  notionPages={notionPages}
                  indexPages={indexPages}
                  index={i}
                  onSaved={onSaved}
                />
              ))}
            </div>
          </>
        )}
      </section>

      <style jsx>{`
        .notion-shell {
          display: flex;
          gap: 32px;
          max-width: 1100px;
          margin: 0 auto;
        }

        /* 메모 사이드바 */
        .memo-sidebar {
          width: 220px;
          flex-shrink: 0;
        }
        .sidebar-title {
          font-size: 11px;
          letter-spacing: 0.12em;
          color: var(--muted);
          text-transform: uppercase;
          margin-bottom: 14px;
        }
        .memo-list {
          list-style: none;
          display: flex;
          flex-direction: column;
          gap: 2px;
        }
        .memo-btn {
          width: 100%;
          display: flex;
          align-items: flex-start;
          gap: 8px;
          padding: 8px 10px;
          background: transparent;
          border: 1px solid transparent;
          border-radius: 7px;
          color: var(--muted);
          font-family: var(--font-sans);
          font-size: 13px;
          cursor: pointer;
          text-align: left;
          transition: all 0.15s;
          line-height: 1.4;
        }
        .memo-btn:hover {
          background: var(--bg3);
          color: var(--text);
          border-color: var(--border);
        }
        .memo-btn.active {
          background: var(--bg3);
          color: var(--accent);
          border-color: var(--border);
        }
        .memo-dot {
          margin-top: 2px;
          flex-shrink: 0;
          color: var(--accent);
        }
        .memo-title { flex: 1; word-break: break-all; }

        /* 추천 영역 */
        .recommend-area { flex: 1; min-width: 0; }

        .empty-state {
          padding: 80px 0;
          text-align: center;
        }
        .empty-title {
          font-size: 24px;
          font-style: italic;
          margin-bottom: 8px;
        }

        .recommend-header { margin-bottom: 24px; }
        .page-title {
          font-size: 24px;
          font-weight: 400;
          line-height: 1.4;
        }

        .status {
          font-size: 13px;
          padding: 16px 0;
          letter-spacing: 0.05em;
        }

        .paper-list {
          display: flex;
          flex-direction: column;
          gap: 14px;
        }
      `}</style>
    </div>
  )
}
