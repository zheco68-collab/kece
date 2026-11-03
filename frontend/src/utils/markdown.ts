import DOMPurify from 'dompurify'
import { marked } from 'marked'
import markedKatex from 'marked-katex-extension'
import 'katex/dist/katex.min.css'

marked.use(markedKatex({ throwOnError: false, nonStandard: true }))
marked.setOptions({ gfm: true, breaks: true })

/** 将 Markdown 渲染为 HTML。 */
export function renderMarkdown(md: string): string {
  if (!md) return ''
  // KaTeX 输出包含 MathML 和用于绘制根号等符号的 SVG。
  return DOMPurify.sanitize(marked.parse(md, { async: false }), {
    USE_PROFILES: { html: true, mathMl: true, svg: true },
  })
}
