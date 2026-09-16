'use client'

import { useState, useEffect } from 'react'
import ThoughtGraph from '@/components/ThoughtGraph'

interface GraphNode {
  id: string
  label: string
  type: 'paper' | 'notion'
  source?: string
  saved?: boolean
}

interface GraphEdge {
  source: string
  target: string
  similarity: number
  edgeType: string
}

interface GraphData {
  nodes: GraphNode[]
  edges: GraphEdge[]
}

const BASE = process.env.NEXT_PUBLIC_API_URL ?? 'http://localhost:8080'

export default function GraphPanel() {
  const [data, setData]           = useState<GraphData>({ nodes: [], edges: [] })
  const [loading, setLoading]     = useState(true)
  const [selected, setSelected]   = useState<GraphNode | null>(null)
  const [filter, setFilter]       = useState<'all' | 'paper' | 'notion'>('all')

  useEffect(() => {
    fetchGraph()
  }, [])

  async function fetchGraph() {
    setLoading(true)
    try {
      const res = await fetch(`${BASE}/api/graph?limit=100`)
      const json = await res.json()
      setData(json)
    } catch {
      setData({ nodes: [], edges: [] })
    } finally {
      setLoading(false)
    }
  }

  // 필터 적용
  const filtered: GraphData = (() => {
    if (filter === 'all') return data
    const nodeIds = new Set(
      data.nodes.filter(n => n.type === filter).map(n => n.id)
    )
    return {
      nodes: data.nodes.filter(n => n.type === filter),
      edges: data.edges.filter(
        e => nodeIds.has(e.source as string) && nodeIds.has(e.target as string)
      ),
    }
  })()

  const savedCount  = data.nodes.filter(n => n.saved).length
  const notionCount = data.nodes.filter(n => n.type === 'notion').length
  const edgeCount   = data.edges.length

  return (
    <div className="graph-page">
      {/* 헤더 바 */}
      <div className="graph-toolbar">
        <div className="toolbar-left">
          <h2 className="serif toolbar-title">Thought Graph</h2>
          <div className="toolbar-stats mono">
            <span>{savedCount} 저장</span>
            <span className="sep">·</span>
            <span>{notionCount} 메모</span>
            <span className="sep">·</span>
            <span>{edgeCount} 연결</span>
          </div>
        </div>

        <div className="toolbar-right">
          {/* 필터 */}
          <div className="filter-group">
            {(['all', 'paper', 'notion'] as const).map(f => (
              <button
                key={f}
                className={`filter-btn mono ${filter === f ? 'active' : ''}`}
                onClick={() => setFilter(f)}
              >
                {f === 'all' ? '전체' : f === 'paper' ? '논문' : '메모'}
              </button>
            ))}
          </div>

          {/* 새로고침 */}
          <button className="refresh-btn mono" onClick={fetchGraph}>
            {loading ? <span className="pulse">●</span> : '↺'}
          </button>
        </div>
      </div>

      {/* 그래프 캔버스 */}
      <div className="graph-canvas">
        {loading ? (
          <div className="loading-state muted mono">
            <span className="pulse">●</span>&nbsp; 그래프 로딩 중…
          </div>
        ) : (
          <ThoughtGraph
            data={filtered}
            onNodeClick={setSelected}
          />
        )}
      </div>

      {/* 선택 노드 패널 */}
      {selected && (
        <div className="detail-panel fade-up">
          <div className="detail-header">
            <span className="detail-type mono">
              {selected.type === 'notion' ? '◈ Notion 메모' : `◎ ${selected.source?.toUpperCase()}`}
            </span>
            <button className="close-btn" onClick={() => setSelected(null)}>✕</button>
          </div>
          <p className="detail-title serif">{selected.label}</p>
          {selected.saved && (
            <span className="saved-tag mono">✓ Notion에 저장됨</span>
          )}

          {/* 이 노드의 연결 목록 */}
          <div className="connections">
            <p className="connections-label mono">연결된 노드</p>
            {data.edges
              .filter(e => e.source === selected.id || e.target === selected.id)
              .slice(0, 8)
              .map((e, i) => {
                const otherId = e.source === selected.id ? e.target : e.source
                const other = data.nodes.find(n => n.id === otherId)
                if (!other) return null
                return (
                  <div key={i} className="conn-item" onClick={() => setSelected(other)}>
                    <span
                      className="conn-dot"
                      style={{ background: other.type === 'notion' ? '#b47cff' : '#7c9fff' }}
                    />
                    <span className="conn-label">{other.label}</span>
                    <span className="conn-sim mono">
                      {(e.similarity * 100).toFixed(0)}%
                    </span>
                  </div>
                )
              })}
          </div>
        </div>
      )}

      <style jsx>{`
        .graph-page {
          display: flex;
          flex-direction: column;
          height: calc(100vh - 80px);
          gap: 0;
        }

        /* 툴바 */
        .graph-toolbar {
          display: flex;
          align-items: center;
          justify-content: space-between;
          padding: 0 0 16px;
          border-bottom: 1px solid var(--border);
          margin-bottom: 16px;
        }
        .toolbar-left { display: flex; align-items: center; gap: 16px; }
        .toolbar-title {
          font-size: 22px;
          font-weight: 400;
          font-style: italic;
        }
        .toolbar-stats {
          font-size: 12px;
          color: var(--muted);
          display: flex;
          gap: 6px;
        }
        .sep { opacity: 0.4; }

        .toolbar-right { display: flex; align-items: center; gap: 10px; }

        .filter-group {
          display: flex;
          border: 1px solid var(--border);
          border-radius: 7px;
          overflow: hidden;
        }
        .filter-btn {
          font-size: 11px;
          padding: 6px 12px;
          background: transparent;
          border: none;
          color: var(--muted);
          cursor: pointer;
          transition: all 0.15s;
          border-right: 1px solid var(--border);
        }
        .filter-btn:last-child { border-right: none; }
        .filter-btn:hover { background: var(--bg3); color: var(--text); }
        .filter-btn.active { background: var(--bg3); color: var(--accent); }

        .refresh-btn {
          background: var(--bg2);
          border: 1px solid var(--border);
          border-radius: 7px;
          color: var(--muted);
          font-size: 16px;
          width: 32px;
          height: 32px;
          cursor: pointer;
          transition: all 0.15s;
        }
        .refresh-btn:hover { color: var(--text); border-color: #44445a; }

        /* 캔버스 */
        .graph-canvas {
          flex: 1;
          border: 1px solid var(--border);
          border-radius: var(--radius);
          overflow: hidden;
          position: relative;
        }
        .loading-state {
          position: absolute;
          inset: 0;
          display: flex;
          align-items: center;
          justify-content: center;
          font-size: 13px;
          letter-spacing: 0.05em;
        }

        /* 디테일 패널 */
        .detail-panel {
          position: absolute;
          right: 40px;
          top: 180px;
          width: 280px;
          background: var(--bg2);
          border: 1px solid var(--border);
          border-radius: var(--radius);
          padding: 16px 18px;
          z-index: 20;
        }
        .detail-header {
          display: flex;
          align-items: center;
          justify-content: space-between;
          margin-bottom: 10px;
        }
        .detail-type {
          font-size: 10px;
          color: var(--accent);
          letter-spacing: 0.1em;
        }
        .close-btn {
          background: transparent;
          border: none;
          color: var(--muted);
          cursor: pointer;
          font-size: 13px;
          padding: 2px 4px;
        }
        .close-btn:hover { color: var(--text); }

        .detail-title {
          font-size: 15px;
          line-height: 1.4;
          margin-bottom: 8px;
          font-style: italic;
        }
        .saved-tag {
          font-size: 10px;
          color: var(--accent2);
          letter-spacing: 0.08em;
          display: block;
          margin-bottom: 12px;
        }

        .connections { margin-top: 12px; }
        .connections-label {
          font-size: 10px;
          color: var(--muted);
          letter-spacing: 0.1em;
          text-transform: uppercase;
          margin-bottom: 8px;
        }
        .conn-item {
          display: flex;
          align-items: center;
          gap: 8px;
          padding: 6px 0;
          border-bottom: 1px solid var(--border);
          cursor: pointer;
        }
        .conn-item:last-child { border-bottom: none; }
        .conn-item:hover .conn-label { color: var(--accent); }
        .conn-dot {
          width: 6px; height: 6px;
          border-radius: 50%;
          flex-shrink: 0;
        }
        .conn-label {
          flex: 1;
          font-size: 12px;
          color: var(--text);
          white-space: nowrap;
          overflow: hidden;
          text-overflow: ellipsis;
        }
        .conn-sim {
          font-size: 10px;
          color: var(--muted);
          flex-shrink: 0;
        }
      `}</style>
    </div>
  )
}
