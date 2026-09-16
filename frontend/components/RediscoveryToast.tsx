'use client'

import { useEffect, useRef } from 'react'

export interface Discovery {
  id: string
  type: 'paper' | 'notion'
  title: string
  source?: string
  externalId?: string
  notionId?: string
  similarity: number
  message: string
}

interface Props {
  discoveries: Discovery[]
  onClose: (id: string) => void
  onViewGraph: () => void
}

const TYPE_ICON: Record<string, string> = {
  arxiv:  '◎',
  core:   '◎',
  pmc:    '◎',
  notion: '◈',
}

const SIM_LABEL = (sim: number) => {
  if (sim >= 0.85) return { text: '강한 연결', color: '#7c6aff' }
  if (sim >= 0.75) return { text: '관련 발견', color: '#4fc8a0' }
  return              { text: '숨겨진 연결', color: '#f5a623' }
}

export default function RediscoveryToast({ discoveries, onClose, onViewGraph }: Props) {
  const timerRef = useRef<Record<string, ReturnType<typeof setTimeout>>>({})

  // 각 토스트 8초 뒤 자동 닫기
  useEffect(() => {
    discoveries.forEach(d => {
      if (timerRef.current[d.id]) return
      timerRef.current[d.id] = setTimeout(() => onClose(d.id), 8000)
    })
    return () => {
      Object.values(timerRef.current).forEach(clearTimeout)
    }
  }, [discoveries, onClose])

  if (discoveries.length === 0) return null

  return (
    <div className="toast-stack">
      {discoveries.map((d, i) => {
        const sim    = SIM_LABEL(d.similarity)
        const icon   = d.type === 'notion' ? '◈' : (TYPE_ICON[d.source ?? ''] ?? '◎')
        const pct    = Math.round(d.similarity * 100)

        return (
          <div
            key={d.id}
            className="toast fade-up"
            style={{ animationDelay: `${i * 80}ms` }}
          >
            {/* 왼쪽 강조 바 */}
            <div className="toast-bar" style={{ background: sim.color }} />

            {/* 본문 */}
            <div className="toast-body">
              <div className="toast-header">
                <span className="toast-eyebrow mono" style={{ color: sim.color }}>
                  ✦ 재발견
                </span>
                <span className="toast-sim mono">{pct}% 유사</span>
              </div>

              <div className="toast-content">
                <span className="toast-icon muted">{icon}</span>
                <div className="toast-text">
                  <p className="toast-msg">{d.message}</p>
                  <p className="toast-title muted">{d.title}</p>
                </div>
              </div>

              <div className="toast-actions">
                <button
                  className="toast-btn-primary mono"
                  onClick={() => { onViewGraph(); onClose(d.id) }}
                >
                  그래프에서 보기
                </button>
                <button
                  className="toast-btn-ghost mono"
                  onClick={() => onClose(d.id)}
                >
                  닫기
                </button>
              </div>
            </div>

            {/* 진행바 (8초) */}
            <div className="toast-progress">
              <div
                className="toast-progress-bar"
                style={{ background: sim.color }}
              />
            </div>
          </div>
        )
      })}

      <style jsx>{`
        .toast-stack {
          position: fixed;
          bottom: 24px;
          right: 24px;
          display: flex;
          flex-direction: column;
          gap: 10px;
          z-index: 100;
          max-width: 340px;
          width: 100%;
        }

        .toast {
          background: var(--bg2);
          border: 1px solid var(--border);
          border-radius: 10px;
          overflow: hidden;
          display: flex;
          position: relative;
          box-shadow: 0 4px 24px rgba(0,0,0,0.4);
        }

        .toast-bar {
          width: 3px;
          flex-shrink: 0;
        }

        .toast-body {
          flex: 1;
          padding: 12px 14px 10px;
          display: flex;
          flex-direction: column;
          gap: 7px;
          min-width: 0;
        }

        .toast-header {
          display: flex;
          align-items: center;
          justify-content: space-between;
        }
        .toast-eyebrow {
          font-size: 10px;
          letter-spacing: 0.1em;
          font-weight: 500;
        }
        .toast-sim {
          font-size: 10px;
          color: var(--muted);
        }

        .toast-content {
          display: flex;
          align-items: flex-start;
          gap: 8px;
        }
        .toast-icon {
          font-size: 14px;
          flex-shrink: 0;
          margin-top: 1px;
        }
        .toast-text { flex: 1; min-width: 0; }
        .toast-msg {
          font-size: 13px;
          color: var(--text);
          line-height: 1.4;
          font-weight: 500;
        }
        .toast-title {
          font-size: 11px;
          margin-top: 3px;
          white-space: nowrap;
          overflow: hidden;
          text-overflow: ellipsis;
          font-family: var(--font-mono);
        }

        .toast-actions {
          display: flex;
          gap: 6px;
        }
        .toast-btn-primary {
          font-size: 11px;
          padding: 4px 10px;
          background: var(--accent);
          border: none;
          border-radius: 5px;
          color: #fff;
          cursor: pointer;
          transition: opacity 0.15s;
        }
        .toast-btn-primary:hover { opacity: 0.85; }
        .toast-btn-ghost {
          font-size: 11px;
          padding: 4px 10px;
          background: transparent;
          border: 1px solid var(--border);
          border-radius: 5px;
          color: var(--muted);
          cursor: pointer;
          transition: all 0.15s;
        }
        .toast-btn-ghost:hover { color: var(--text); }

        /* 진행바 */
        .toast-progress {
          position: absolute;
          bottom: 0;
          left: 3px;
          right: 0;
          height: 2px;
          background: var(--border);
        }
        .toast-progress-bar {
          height: 100%;
          width: 100%;
          opacity: 0.5;
          animation: shrink 8s linear forwards;
          transform-origin: left;
        }
        @keyframes shrink {
          from { transform: scaleX(1); }
          to   { transform: scaleX(0); }
        }
      `}</style>
    </div>
  )
}
