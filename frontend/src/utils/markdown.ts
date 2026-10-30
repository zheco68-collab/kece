import { marked } from 'marked'
import markedKatex from 'marked-katex-extension'
import 'katex/dist/katex.min.css'

marked.use(markedKatex({ throwOnError: false, nonStandard: true }))
marked.setOptions({ gfm: true, breaks: true })

/** 极简 XSS 清洗：移除脚本与内联事件，供可信度较低的 Markdown 内容使用。 */
function sanitize(html: string): string {
  return html
    .replace(/<script[\s\S]*?<\/script>/gi, '')
    .replace(/\son\w+=(?:"[^"]*"|'[^']*'|[^\s>]+)/gi, '')
    .replace(/javascript:/gi, '')
}

/** 将 Markdown 渲染为 HTML。 */
export function renderMarkdown(md: string): string {
  if (!md) return ''
  return sanitize(marked.parse(md) as string)
}
