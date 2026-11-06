import DOMPurify from 'dompurify'
import { marked } from 'marked'
import markedKatex from 'marked-katex-extension'
import Prism from 'prismjs'
import 'prismjs/components/prism-clike'
import 'prismjs/components/prism-javascript'
import 'prismjs/components/prism-typescript'
import 'prismjs/components/prism-python'
import 'prismjs/components/prism-c'
import 'prismjs/components/prism-cpp'
import 'prismjs/components/prism-java'
import 'prismjs/components/prism-sql'
import 'katex/dist/katex.min.css'
import '@/assets/markdown.css'

marked.use(markedKatex({ throwOnError: false, nonStandard: true }))
marked.setOptions({ gfm: true, breaks: true })

const CODE_LANGUAGE_ALIASES: Record<string, string> = {
  c: 'c',
  cpp: 'cpp',
  'c++': 'cpp',
  java: 'java',
  js: 'javascript',
  javascript: 'javascript',
  ts: 'typescript',
  typescript: 'typescript',
  py: 'python',
  python: 'python',
  sql: 'sql',
  mysql: 'sql',
}

function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, (character) => {
    const entities: Record<string, string> = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }
    return entities[character] ?? character
  })
}

/** 将 fenced code block 交给 Prism 渲染，未知语言保留为安全的纯文本代码块。 */
marked.use({
  renderer: {
    code({ text, lang }) {
      const rawLanguage = (lang ?? '').trim().toLowerCase()
      const language = CODE_LANGUAGE_ALIASES[rawLanguage]
      const highlighted = language && Prism.languages[language]
        ? Prism.highlight(text, Prism.languages[language], language)
        : escapeHtml(text)
      const className = language ? ` language-${language}` : ''
      const label = rawLanguage ? `<span class="code-language">${escapeHtml(rawLanguage)}</span>` : ''
      return `<pre class="code-block"><code class="code-content${className}">${highlighted}</code>${label}</pre>`
    },
  },
})

/** 将 Markdown 渲染为 HTML。 */
export function renderMarkdown(md: string): string {
  if (!md) return ''
  // KaTeX 输出包含 MathML 和用于绘制根号等符号的 SVG。
  return DOMPurify.sanitize(marked.parse(md, { async: false }), {
    USE_PROFILES: { html: true, mathMl: true, svg: true },
  })
}
