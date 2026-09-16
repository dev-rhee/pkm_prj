import type { Metadata } from 'next'
import './globals.css'

export const metadata: Metadata = {
  title: 'PKM — 개인 지식 관리',
  description: '논문 검색 · AI 요약 · Notion 연동',
}

export default function RootLayout({
  children,
}: {
  children: React.ReactNode
}) {
  return (
    <html lang="ko">
      <body>{children}</body>
    </html>
  )
}
