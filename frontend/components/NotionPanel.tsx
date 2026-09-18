'use client'

import { useMemo, useState } from 'react'
import PaperCard from '@/components/PaperCard'
import { recommendFromNotion, type NotionPage, type Paper } from '@/lib/api'

// Notion ID는 하이픈 유무가 섞여 들어오므로 비교할 땐 떼고 본다
const norm = (id: string) => id.replace(/-/g, '')
const NO_INDEX = '__none__'

interface Folder {
  id: string
  title: string
  memos: NotionPage[]
}

// 메모를 색인 폴더별로 묶는다. 색인이 없거나 목록에 없는 색인만 가진 메모는 '색인 없음'으로.
function groupByIndex(notionPages: NotionPage[], indexPages: NotionPage[]): Folder[] {
  const folders: Folder[] = indexPages.map(p => ({ id: p.id, title: p.title, memos: [] }))
  const byId = new Map(folders.map(f => [norm(f.id), f]))
  const none: Folder = { id: NO_INDEX, title: '색인 없음', memos: [] }

  for (const memo of notionPages) {
    const matched = (memo.indexIds ?? []).map(norm).map(id => byId.get(id)).filter(Boolean) as Folder[]
    if (matched.length === 0) none.memos.push(memo)
    matched.forEach(f => f.memos.push(memo))
  }
  return none.memos.length > 0 ? [...folders, none] : folders
}

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
  const [openIds, setOpenIds]       = useState<Set<string>>(new Set())

  const folders = useMemo(() => groupByIndex(notionPages, indexPages), [notionPages, indexPages])

  function toggleFolder(id: string) {
    setOpenIds(prev => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

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
          <ul className="folder-list">
            {folders.map(folder => {
              const open = openIds.has(folder.id)
              return (
                <li key={folder.id}>
                  <button
                    className={`folder-btn ${open ? 'open' : ''}`}
                    onClick={() => toggleFolder(folder.id)}
                    aria-expanded={open}
                  >
                    <span className="folder-chevron mono">{open ? '▾' : '▸'}</span>
                    <span className="folder-title">{folder.title}</span>
                    <span className="folder-count mono">{folder.memos.length}</span>
                  </button>

                  {open && (
                    folder.memos.length === 0 ? (
                      <p className="folder-empty muted">메모 없음</p>
                    ) : (
                      <ul className="memo-list">
                        {folder.memos.map(page => (
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
                    )
                  )}
                </li>
              )
            })}
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
        .folder-list {
          list-style: none;
          display: flex;
          flex-direction: column;
          gap: 4px;
        }
        .folder-btn {
          width: 100%;
          display: flex;
          align-items: center;
          gap: 8px;
          padding: 7px 8px;
          background: transparent;
          border: 1px solid transparent;
          border-radius: 7px;
          color: var(--text);
          font-family: var(--font-sans);
          font-size: 13px;
          font-weight: 500;
          cursor: pointer;
          text-align: left;
          transition: all 0.15s;
        }
        .folder-btn:hover { background: var(--bg3); border-color: var(--border); }
        .folder-btn.open  { color: var(--accent); }
        .folder-chevron {
          width: 10px;
          flex-shrink: 0;
          font-size: 11px;
          color: var(--muted);
        }
        .folder-title { flex: 1; word-break: break-all; }
        .folder-count {
          font-size: 10px;
          color: var(--muted);
          background: var(--bg3);
          border-radius: 10px;
          padding: 1px 7px;
        }
        .folder-empty {
          font-size: 12px;
          padding: 6px 0 8px 28px;
        }

        .memo-list {
          list-style: none;
          display: flex;
          flex-direction: column;
          gap: 2px;
          margin: 2px 0 8px 14px;
          padding-left: 6px;
          border-left: 1px solid var(--border);
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
