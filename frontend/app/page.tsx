'use client'

import { useState, useEffect, useRef } from 'react'
import PaperCard from '@/components/PaperCard'
import NotionPanel from '@/components/NotionPanel'
import GraphPanel from '@/components/GraphPanel'
import { searchPapers, listNotionPages, listIndexPages, type Paper, type NotionPage } from '@/lib/api'
import RediscoveryToast from '@/components/RediscoveryToast'
import { useRediscovery } from '@/lib/useRediscovery'

type Tab = 'search' | 'notion' | 'graph'

const PER_PAGE = 10

export default function Home() {
  const [tab, setTab]                 = useState<Tab>('search')
  const [query, setQuery]             = useState('')
  const [papers, setPapers]           = useState<Paper[]>([])
  const [notionPages, setNotionPages] = useState<NotionPage[]>([])
  const [indexPages, setIndexPages]   = useState<NotionPage[]>([])
  const [loading, setLoading]         = useState(false)
  const [searched, setSearched]       = useState(false)
  const [page, setPage]               = useState(1)
  const inputRef                      = useRef<HTMLInputElement>(null)
  const { queue, handleSaveResult, dismiss } = useRediscovery()

  const totalPages = Math.ceil(papers.length / PER_PAGE)

  // Notion 메모·색인 목록은 앱 로드 시 한 번만
  useEffect(() => {
    listNotionPages().then(setNotionPages)
    listIndexPages().then(setIndexPages)
  }, [])

  async function handleSearch(e: React.FormEvent) {
    e.preventDefault()
    if (!query.trim()) return
    setLoading(true)
    setPapers([])
    setSearched(false)
    setPage(1)
    try {
      const results = await searchPapers(query.trim())
      setPapers(results)
      setSearched(true)
    } catch {
      setSearched(true)
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="shell">
      {/* 사이드바 */}
      <nav className="sidebar">
        <div className="logo mono">PKM</div>
        <div className="nav-tabs">
          <button
            className={`nav-tab ${tab === 'search' ? 'active' : ''}`}
            onClick={() => setTab('search')}
          >
            <span className="nav-icon">◎</span>
            <span>논문 검색</span>
          </button>
          <button
            className={`nav-tab ${tab === 'notion' ? 'active' : ''}`}
            onClick={() => setTab('notion')}
          >
            <span className="nav-icon">◈</span>
            <span>Notion 메모</span>
            {notionPages.length > 0 && (
              <span className="badge">{notionPages.length}</span>
            )}
          </button>
          <button
            className={`nav-tab ${tab === 'graph' ? 'active' : ''}`}
            onClick={() => setTab('graph')}
          >
            <span className="nav-icon">◉</span>
            <span>Thought Graph</span>
          </button>
        </div>
        <div className="sidebar-footer muted mono">
          arXiv · CORE · PMC
        </div>
      </nav>

      {/* 메인 영역 */}
      <main className="main">

        {/* ── 검색 탭 ── */}
        {tab === 'search' && (
          <div className="tab-content">
            <div className="search-area">
              <h1 className="serif page-title">논문 탐색</h1>
              <p className="muted page-sub">
                무료 full-text 논문만 검색 · arXiv · CORE · PubMed Central
              </p>

              <form className="search-form" onSubmit={handleSearch}>
                <input
                  ref={inputRef}
                  className="search-input mono"
                  placeholder="키워드 입력 (예: attention mechanism, ADHD hyperfocus)"
                  value={query}
                  onChange={e => setQuery(e.target.value)}
                  disabled={loading}
                />
                <button className="search-btn" type="submit" disabled={loading}>
                  {loading ? <span className="pulse">●</span> : '→'}
                </button>
              </form>
            </div>

            {/* 결과 */}
            <div className="results">
              {loading && (
                <div className="status-msg muted mono">
                  <span className="pulse">●</span>&nbsp; 검색 중…
                </div>
              )}

              {!loading && searched && papers.length === 0 && (
                <div className="status-msg muted mono">검색 결과 없음</div>
              )}

              {papers.slice((page - 1) * PER_PAGE, page * PER_PAGE).map((paper, i) => (
                <PaperCard
                  key={paper.externalId}
                  paper={paper}
                  notionPages={notionPages}
                  indexPages={indexPages}
                  index={i}
                  onSaved={handleSaveResult}
                />
              ))}

              {totalPages > 1 && (
                <nav className="pager mono">
                  <button
                    className="pager-btn"
                    onClick={() => setPage(p => Math.max(1, p - 1))}
                    disabled={page === 1}
                  >
                    ← 이전
                  </button>
                  <span className="pager-status">
                    {page} / {totalPages}
                    <span className="muted"> · 전체 {papers.length}건</span>
                  </span>
                  <button
                    className="pager-btn"
                    onClick={() => setPage(p => Math.min(totalPages, p + 1))}
                    disabled={page === totalPages}
                  >
                    다음 →
                  </button>
                </nav>
              )}
            </div>
          </div>
        )}

        {/* ── Notion 탭 ── */}
        {tab === 'notion' && (
          <NotionPanel notionPages={notionPages} indexPages={indexPages} onSaved={handleSaveResult} />
        )}

        {/* ── Graph 탭 ── */}
        {tab === 'graph' && (
          <div className="tab-content tab-graph">
            <GraphPanel />
          </div>
        )}
      </main>

      {/* ── 재발견 토스트 ── */}
      <RediscoveryToast
        discoveries={queue}
        onClose={dismiss}
        onViewGraph={() => setTab('graph')}
      />

      <style jsx>{`
        .shell {
          display: flex;
          min-height: 100vh;
        }

        /* 페이지네이션 */
        .pager {
          display: flex;
          align-items: center;
          justify-content: center;
          gap: 16px;
          padding: 8px 0 24px;
          font-size: 12px;
        }
        .pager-btn {
          font-family: var(--font-mono);
          font-size: 12px;
          padding: 7px 14px;
          border-radius: 6px;
          background: var(--bg2);
          color: var(--text);
          border: 1px solid var(--border);
          cursor: pointer;
          transition: border-color 0.15s, opacity 0.15s;
        }
        .pager-btn:not(:disabled):hover { border-color: var(--accent); color: var(--accent); }
        .pager-btn:disabled { opacity: 0.35; cursor: not-allowed; }
        .pager-status { color: var(--text); }

        /* 사이드바 */
        .sidebar {
          width: 200px;
          min-height: 100vh;
          background: var(--bg2);
          border-right: 1px solid var(--border);
          display: flex;
          flex-direction: column;
          padding: 24px 0;
          position: sticky;
          top: 0;
          height: 100vh;
        }
        .logo {
          font-size: 18px;
          font-weight: 500;
          letter-spacing: 0.15em;
          color: var(--accent);
          padding: 0 20px 24px;
          border-bottom: 1px solid var(--border);
        }
        .nav-tabs {
          flex: 1;
          padding: 16px 10px;
          display: flex;
          flex-direction: column;
          gap: 4px;
        }
        .nav-tab {
          display: flex;
          align-items: center;
          gap: 10px;
          padding: 9px 12px;
          border-radius: 7px;
          border: none;
          background: transparent;
          color: var(--muted);
          font-family: var(--font-sans);
          font-size: 13px;
          cursor: pointer;
          transition: all 0.15s;
          text-align: left;
        }
        .nav-tab:hover { background: var(--bg3); color: var(--text); }
        .nav-tab.active {
          background: var(--bg3);
          color: var(--accent);
          border: 1px solid var(--border);
        }
        .nav-icon { font-size: 14px; }
        .badge {
          margin-left: auto;
          font-family: var(--font-mono);
          font-size: 10px;
          background: var(--bg);
          border: 1px solid var(--border);
          border-radius: 10px;
          padding: 1px 6px;
          color: var(--muted);
        }
        .sidebar-footer {
          padding: 16px 20px 0;
          font-size: 10px;
          letter-spacing: 0.08em;
          border-top: 1px solid var(--border);
          line-height: 1.8;
        }

        /* 메인 */
        .main {
          flex: 1;
          min-width: 0;
          padding: 40px;
        }
        .tab-content {
          max-width: 780px;
          margin: 0 auto;
        }

        /* 검색 영역 */
        .search-area { margin-bottom: 36px; }
        .page-title {
          font-size: 32px;
          font-weight: 400;
          margin-bottom: 8px;
          font-style: italic;
        }
        .page-sub { font-size: 13px; margin-bottom: 24px; }

        .search-form {
          display: flex;
          gap: 0;
          border: 1px solid var(--border);
          border-radius: var(--radius);
          overflow: hidden;
          transition: border-color 0.2s;
        }
        .search-form:focus-within { border-color: var(--accent); }

        .search-input {
          flex: 1;
          background: var(--bg2);
          border: none;
          outline: none;
          padding: 13px 18px;
          color: var(--text);
          font-size: 14px;
        }
        .search-input::placeholder { color: var(--muted); }
        .search-input:disabled { opacity: 0.5; }

        .search-btn {
          background: var(--accent);
          border: none;
          padding: 0 22px;
          color: #fff;
          font-size: 20px;
          cursor: pointer;
          transition: opacity 0.15s;
        }
        .search-btn:hover:not(:disabled) { opacity: 0.85; }
        .search-btn:disabled { opacity: 0.4; cursor: not-allowed; }

        /* 결과 */
        .results {
          display: flex;
          flex-direction: column;
          gap: 14px;
        }
        .status-msg {
          font-size: 13px;
          padding: 20px 0;
          letter-spacing: 0.05em;
        }
      `}</style>
    </div>
  )
}
// tab-graph 스타일 추가는 globals.css에서 처리
