import { describe, expect, it } from 'vitest'
import { renderMarkdown } from './markdown'

function parseMarkdown(markdown: string) {
  const template = document.createElement('template')
  template.innerHTML = renderMarkdown(markdown)
  return template.content
}

describe('renderMarkdown', () => {
  it('removes event handlers and encoded JavaScript URLs', () => {
    const output = parseMarkdown(
      '<img src="/example.png" onerror = "alert(1)">\n\n' +
        '<a href="java&#x09;script&#58;alert(1)">unsafe</a>\n\n' +
        '<iframe srcdoc="<script>alert(1)</script>"></iframe>\n\n' +
        '[safe](https://example.com/)',
    )

    expect(output.querySelector('img')?.getAttribute('src')).toBe('/example.png')
    expect(output.querySelector('img')?.hasAttribute('onerror')).toBe(false)
    const [unsafe, safe] = output.querySelectorAll('a')
    expect(unsafe?.hasAttribute('href')).toBe(false)
    expect(safe?.getAttribute('href')).toBe('https://example.com/')
    expect(output.querySelector('iframe, script')).toBeNull()
  })

  it('preserves Markdown formatting and KaTeX math markup', () => {
    const output = parseMarkdown('**题目**：$\\sqrt{x^2 + 1}$')

    expect(output.querySelector('strong')?.textContent).toBe('题目')
    expect(output.querySelector('.katex .katex-mathml math')).not.toBeNull()
    expect(output.querySelector('.katex .katex-html svg path')).not.toBeNull()
  })
})
