'use client'

import { useEffect, useRef, useState, useCallback } from 'react'
import * as d3 from 'd3'

interface GraphNode extends d3.SimulationNodeDatum {
  id: string
  label: string
  type: 'paper' | 'notion' | 'index'
  source?: 'arxiv' | 'core' | 'pmc'
  saved?: boolean
  notionId?: string
  indexes?: string[]   // 논문이 속한 색인 이름들
}

interface GraphEdge extends d3.SimulationLinkDatum<GraphNode> {
  source: string | GraphNode
  target: string | GraphNode
  similarity: number
  edgeType: EdgeType
}

// saved    = 저장할 때 사용자가 직접 고른 메모 연결
// semantic = 요약 임베딩이 비슷해서 자동으로 이어진 연결
// index    = 저장할 때 고른 색인 (논문 → 색인 허브)
type EdgeType = 'saved' | 'semantic' | 'index'

interface GraphData {
  nodes: GraphNode[]
  edges: GraphEdge[]
}

interface Props {
  data: GraphData
  onNodeClick?: (node: GraphNode) => void
}

const NODE_COLOR: Record<string, string> = {
  arxiv:  '#7c9fff',
  core:   '#6fcf6f',
  pmc:    '#ff9f7c',
  notion: '#b47cff',
  index:  '#f2b544',
}

const EDGE_COLOR: Record<EdgeType, string> = {
  saved:    '#7c6aff',
  semantic: '#44445a',
  index:    '#f2b544',
}

const EDGE_LABEL: Record<EdgeType, string> = {
  saved:    '직접 연결',
  semantic: '내용 연결',
  index:    '색인',
}

export default function ThoughtGraph({ data, onNodeClick }: Props) {
  const svgRef   = useRef<SVGSVGElement>(null)
  const wrapRef  = useRef<HTMLDivElement>(null)
  const simRef   = useRef<d3.Simulation<GraphNode, GraphEdge> | null>(null)

  // 범례에서 켜고 끄는 연결 종류
  const [visibleTypes, setVisibleTypes] = useState<Record<EdgeType, boolean>>({
    saved: true,
    semantic: true,
    index: true,
  })
  const toggleType = (type: EdgeType) =>
    setVisibleTypes(prev => ({ ...prev, [type]: !prev[type] }))

  const [tooltip, setTooltip] = useState<{
    x: number; y: number; node: GraphNode
  } | null>(null)

  const draw = useCallback(() => {
    const wrap = wrapRef.current
    const svg  = svgRef.current
    if (!wrap || !svg || data.nodes.length === 0) return

    const W = wrap.clientWidth
    const H = wrap.clientHeight

    d3.select(svg).selectAll('*').remove()

    const root = d3.select(svg)
      .attr('width', W)
      .attr('height', H)

    // ── zoom/pan ──────────────────────────────────────
    const g = root.append('g')
    root.call(
      d3.zoom<SVGSVGElement, unknown>()
        .scaleExtent([0.2, 4])
        .on('zoom', (e) => g.attr('transform', e.transform))
    )

    // ── arrow marker ─────────────────────────────────
    root.append('defs')
      .append('marker')
      .attr('id', 'arrowhead')
      .attr('viewBox', '0 0 10 10')
      .attr('refX', 18).attr('refY', 5)
      .attr('markerWidth', 5).attr('markerHeight', 5)
      .attr('orient', 'auto-start-reverse')
      .append('path')
      .attr('d', 'M2 1L8 5L2 9')
      .attr('fill', 'none')
      .attr('stroke', '#44445a')
      .attr('stroke-width', 1.5)
      .attr('stroke-linecap', 'round')

    // ── 시뮬레이션 ────────────────────────────────────
    const nodes: GraphNode[] = data.nodes.map(n => ({ ...n }))

    // 한쪽 끝이 노드 목록에 없는 엣지는 버린다. d3.forceLink는 이 경우 예외를 던져
    // 화면 전체가 죽는다 (노드 목록은 최근 N건으로 잘려 있어 오래된 엣지가 걸릴 수 있다).
    const known = new Set(nodes.map(n => n.id))
    const edges: GraphEdge[] = data.edges
      .filter(e => known.has(e.source as string) && known.has(e.target as string))
      .filter(e => visibleTypes[e.edgeType])
      .map(e => ({ ...e }))

    const sim = d3.forceSimulation<GraphNode>(nodes)
      .force('link', d3.forceLink<GraphNode, GraphEdge>(edges)
        .id(d => d.id)
        .distance(d => 120 - (d.similarity ?? 0.5) * 60)
        .strength(0.4)
      )
      .force('charge', d3.forceManyBody().strength(-280))
      .force('center', d3.forceCenter(W / 2, H / 2))
      .force('collision', d3.forceCollide(28))

    simRef.current = sim

    // ── 엣지 ─────────────────────────────────────────
    const link = g.append('g').selectAll<SVGLineElement, GraphEdge>('line')
      .data(edges)
      .join('line')
      .attr('stroke', d => EDGE_COLOR[d.edgeType] ?? '#333')
      .attr('stroke-width', d => Math.max(0.5, (d.similarity ?? 0.5) * 2.5))
      .attr('stroke-opacity', d => 0.3 + (d.similarity ?? 0.5) * 0.5)
      .attr('marker-end', d =>
        d.edgeType === 'saved' ? 'url(#arrowhead)' : null
      )

    // ── 노드 그룹 ─────────────────────────────────────
    const node = g.append('g').selectAll<SVGGElement, GraphNode>('g')
      .data(nodes)
      .join('g')
      .attr('cursor', 'pointer')
      .call(
        d3.drag<SVGGElement, GraphNode>()
          .on('start', (e, d) => {
            if (!e.active) sim.alphaTarget(0.3).restart()
            d.fx = d.x; d.fy = d.y
          })
          .on('drag', (e, d) => { d.fx = e.x; d.fy = e.y })
          .on('end', (e, d) => {
            if (!e.active) sim.alphaTarget(0)
            d.fx = null; d.fy = null
          })
      )
      .on('click', (e, d) => {
        e.stopPropagation()
        onNodeClick?.(d)
        setTooltip({ x: e.offsetX, y: e.offsetY, node: d })
      })

    // 노드 원
    node.append('circle')
      .attr('r', d => d.type === 'index' ? 14 : d.type === 'notion' ? 10 : 8)
      .attr('fill', d => {
        if (d.type === 'index')  return NODE_COLOR.index
        if (d.type === 'notion') return NODE_COLOR.notion
        return NODE_COLOR[d.source ?? ''] ?? '#888'
      })
      .attr('fill-opacity', 0.85)
      .attr('stroke', d => d.saved ? '#fff' : 'transparent')
      .attr('stroke-width', d => d.saved ? 2 : 0)

    // 저장된 논문 — 외곽 링
    node.filter(d => !!d.saved)
      .append('circle')
      .attr('r', 13)
      .attr('fill', 'none')
      .attr('stroke', '#7c6aff')
      .attr('stroke-width', 1)
      .attr('stroke-opacity', 0.6)
      .attr('stroke-dasharray', '3 2')

    // 노드 레이블
    node.append('text')
      .text(d => d.label.length > 28 ? d.label.slice(0, 28) + '…' : d.label)
      .attr('x', 0)
      .attr('y', d => d.type === 'index' ? 27 : d.type === 'notion' ? 22 : 19)
      .attr('text-anchor', 'middle')
      .attr('font-size', d => d.type === 'index' ? '11px' : '10px')
      .attr('font-weight', d => d.type === 'index' ? 600 : 400)
      .attr('font-family', 'DM Mono, monospace')
      .attr('fill', d => d.type === 'index' ? NODE_COLOR.index : '#72728a')
      .attr('pointer-events', 'none')

    // ── 틱 업데이트 ───────────────────────────────────
    sim.on('tick', () => {
      link
        .attr('x1', d => (d.source as GraphNode).x ?? 0)
        .attr('y1', d => (d.source as GraphNode).y ?? 0)
        .attr('x2', d => (d.target as GraphNode).x ?? 0)
        .attr('y2', d => (d.target as GraphNode).y ?? 0)

      node.attr('transform', d => `translate(${d.x ?? 0},${d.y ?? 0})`)
    })

    // 배경 클릭 → 툴팁 닫기
    root.on('click', () => setTooltip(null))
  }, [data, onNodeClick, visibleTypes])

  useEffect(() => {
    draw()
    const ro = new ResizeObserver(draw)
    if (wrapRef.current) ro.observe(wrapRef.current)
    return () => {
      ro.disconnect()
      simRef.current?.stop()
    }
  }, [draw])

  const nodeCount   = data.nodes.length
  const edgeCount   = data.edges.length
  const notionCount = data.nodes.filter(n => n.type === 'notion').length
  const paperCount  = data.nodes.filter(n => n.type === 'paper').length
  const indexCount  = data.nodes.filter(n => n.type === 'index').length

  return (
    <div className="graph-wrap" ref={wrapRef}>
      {/* 캔버스 */}
      <svg ref={svgRef} className="graph-svg" />

      {/* 빈 상태 */}
      {nodeCount === 0 && (
        <div className="graph-empty">
          <p className="serif" style={{ fontSize: 22, fontStyle: 'italic' }}>
            아직 연결이 없어요
          </p>
          <p className="muted" style={{ fontSize: 13, marginTop: 8 }}>
            논문을 저장하면 Thought Graph가 만들어져요
          </p>
        </div>
      )}

      {/* 범례 */}
      {nodeCount > 0 && (
        <div className="legend">
          <div className="legend-row">
            <span className="dot" style={{ background: '#7c9fff' }} />
            <span>arXiv</span>
          </div>
          <div className="legend-row">
            <span className="dot" style={{ background: '#6fcf6f' }} />
            <span>CORE</span>
          </div>
          <div className="legend-row">
            <span className="dot" style={{ background: '#ff9f7c' }} />
            <span>PMC</span>
          </div>
          <div className="legend-row">
            <span className="dot" style={{ background: '#b47cff' }} />
            <span>Notion 메모</span>
          </div>
          <div className="legend-row">
            <span className="dot" style={{ background: '#f2b544' }} />
            <span>색인</span>
          </div>
          <div className="legend-divider" />
          {(Object.keys(EDGE_LABEL) as EdgeType[]).map(type => (
            <label key={type} className={`legend-row legend-toggle ${visibleTypes[type] ? '' : 'off'}`}>
              <input
                type="checkbox"
                checked={visibleTypes[type]}
                onChange={() => toggleType(type)}
              />
              <span className="line-sample" style={{ background: EDGE_COLOR[type] }} />
              <span>{EDGE_LABEL[type]}</span>
            </label>
          ))}
        </div>
      )}

      {/* 통계 */}
      {nodeCount > 0 && (
        <div className="stats mono">
          <span>{paperCount} 논문</span>
          <span className="sep">·</span>
          <span>{notionCount} 메모</span>
          <span className="sep">·</span>
          <span>{indexCount} 색인</span>
          <span className="sep">·</span>
          <span>{edgeCount} 연결</span>
        </div>
      )}

      {/* 노드 툴팁 */}
      {tooltip && (
        <div
          className="node-tooltip fade-up"
          style={{ left: tooltip.x + 12, top: tooltip.y - 10 }}
        >
          <div className="tt-type mono">
            {tooltip.node.type === 'index'  ? '◆ 색인'
           : tooltip.node.type === 'notion' ? '◈ Notion'
           : `◎ ${tooltip.node.source?.toUpperCase()}`}
          </div>
          <div className="tt-title">{tooltip.node.label}</div>
          {tooltip.node.type === 'paper' && (tooltip.node.indexes?.length ?? 0) > 0 && (
            <div className="tt-index mono">색인: {tooltip.node.indexes!.join(', ')}</div>
          )}
          {tooltip.node.type === 'paper' && (
            <button
              className="tt-btn"
              onClick={() => {
                setTooltip(null)
              }}
            >
              논문 보기 →
            </button>
          )}
        </div>
      )}

      <style jsx>{`
        .graph-wrap {
          position: relative;
          width: 100%;
          height: 100%;
          background: var(--bg);
          border-radius: var(--radius);
          overflow: hidden;
        }
        .graph-svg {
          display: block;
          width: 100%;
          height: 100%;
        }
        .graph-empty {
          position: absolute;
          inset: 0;
          display: flex;
          flex-direction: column;
          align-items: center;
          justify-content: center;
          pointer-events: none;
        }

        /* 범례 */
        .legend {
          position: absolute;
          bottom: 20px;
          left: 20px;
          background: var(--bg2);
          border: 1px solid var(--border);
          border-radius: 8px;
          padding: 12px 14px;
          display: flex;
          flex-direction: column;
          gap: 6px;
          font-size: 11px;
          font-family: var(--font-mono);
          color: var(--muted);
        }
        .legend-row {
          display: flex;
          align-items: center;
          gap: 8px;
        }
        .dot {
          width: 8px; height: 8px;
          border-radius: 50%;
          flex-shrink: 0;
        }
        .line-sample {
          width: 14px; height: 2px;
          border-radius: 1px;
          flex-shrink: 0;
        }
        .legend-toggle { cursor: pointer; user-select: none; }
        .legend-toggle input { accent-color: var(--accent); cursor: pointer; margin: 0; }
        .legend-toggle.off { opacity: 0.45; }
        .legend-toggle:hover span:last-child { color: var(--accent); }

        .legend-divider {
          height: 1px;
          background: var(--border);
          margin: 2px 0;
        }

        /* 통계 */
        .stats {
          position: absolute;
          top: 16px;
          left: 20px;
          font-size: 11px;
          color: var(--muted);
          display: flex;
          gap: 6px;
          letter-spacing: 0.05em;
        }
        .sep { opacity: 0.4; }

        /* 툴팁 */
        .node-tooltip {
          position: absolute;
          background: var(--bg2);
          border: 1px solid var(--border);
          border-radius: 8px;
          padding: 10px 14px;
          max-width: 240px;
          pointer-events: auto;
          z-index: 10;
        }
        .tt-type {
          font-size: 10px;
          color: var(--accent);
          letter-spacing: 0.1em;
          margin-bottom: 4px;
        }
        .tt-index {
          font-size: 10px;
          color: #f2b544;
          margin: -4px 0 8px;
        }
        .tt-title {
          font-size: 12px;
          color: var(--text);
          line-height: 1.4;
          margin-bottom: 8px;
        }
        .tt-btn {
          font-family: var(--font-mono);
          font-size: 11px;
          background: transparent;
          border: 1px solid var(--border);
          border-radius: 5px;
          color: var(--accent);
          padding: 4px 10px;
          cursor: pointer;
        }
        .tt-btn:hover { background: var(--bg3); }
      `}</style>
    </div>
  )
}
