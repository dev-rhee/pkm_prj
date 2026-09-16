import { useState, useCallback } from 'react'
import type { Discovery } from '@/components/RediscoveryToast'

interface RediscoverResult {
  discoveries: Discovery[]
  count: number
}

export function useRediscovery() {
  const [queue, setQueue] = useState<Discovery[]>([])

  // 저장 응답에서 재발견 결과 처리
  const handleSaveResult = useCallback((saveResult: Record<string, unknown>) => {
    const raw = saveResult?.rediscoveries as Record<string, unknown> | undefined
    if (!raw) return

    const result = raw as RediscoverResult
    if (!result.discoveries?.length) return

    setQueue(prev => {
      // 중복 제거 후 새 것 앞에 추가
      const existingIds = new Set(prev.map(d => d.id))
      const fresh = result.discoveries.filter(d => !existingIds.has(d.id))
      return [...fresh, ...prev].slice(0, 5) // 최대 5개
    })
  }, [])

  const dismiss = useCallback((id: string) => {
    setQueue(prev => prev.filter(d => d.id !== id))
  }, [])

  const dismissAll = useCallback(() => setQueue([]), [])

  return { queue, handleSaveResult, dismiss, dismissAll }
}
