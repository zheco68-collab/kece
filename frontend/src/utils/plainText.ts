/**
 * 将包含 Markdown 与 LaTeX 语法的文本剥离为单行纯文本摘要。
 * 纯 TypeScript 轻量实现，不依赖 marked / katex / prismjs 等重型库。
 */
export function stripMarkdown(md: string, maxLength?: number): string {
  if (!md) return ''

  let text = md.replace(/\r\n?/g, '\n')

  // 1. 剥离围栏代码块（``` / ~~~ 多行块、单行块及未闭合尾部块）
  text = text
    .replace(/(?:^|\n)[ \t]*(`{3,}|~{3,})[^\n]*\n(?:[\s\S]*?\n)?[ \t]*\1[ \t]*(?=\n|$)/g, '\n')
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/~~~[\s\S]*?~~~/g, ' ')
    .replace(/^[ \t]*(?:`{3,}|~{3,})[\s\S]*$/m, ' ')

  // 2. 暂存行内代码内容并移除反引号，防止内部下划线/星号/美元符被误当成格式标记
  const inlineCodes: string[] = []
  text = text
    .replace(/(`+)(.+?)\1/g, (_match, _ticks: string, code: string) => {
      const token = `\uE000CODE:${inlineCodes.length}\uE001`
      inlineCodes.push(code)
      return token
    })
    .replace(/`/g, '')

  // 3. 暂存转义的美元符 \$，避免干扰公式定界符解析
  text = text.replace(/\\\$/g, '\uE000DOLLAR\uE001')

  // 4. 剥离 Markdown 图片与链接语法，保留可读文本 / alt 描述
  text = text
    .replace(/!\[([^\]]*)\]\((?:[^()\n]+|\([^()\n]*\))*\)/g, '$1')
    .replace(/!\[([^\]]*)\]\[[^\]]*\]/g, '$1')
    .replace(/\[([^\]]+)\]\((?:[^()\n]+|\([^()\n]*\))*\)/g, '$1')
    .replace(/\[([^\]]+)\]\[[^\]]*\]/g, '$1')
    .replace(/^[ \t]*\[[^\]]+\]:[ \t]+\S.*$/gm, '')
    .replace(/<(https?:\/\/[^\s>]+)>/gi, '$1')

  // 5. 剥离 LaTeX 公式定界符（$$ ... $$ 与 $ ... $），保留公式正文
  text = text
    .replace(/\$\$([\s\S]*?)\$\$/g, '$1')
    .replace(/\$([^$\n]+?)\$/g, '$1')
    .replace(/\$\$/g, '')

  // 6. 剥离块级语法标记（分隔线、标题、引用、任务/无序/有序列表）
  text = text
    .replace(/^[ \t]*([-*_])(?:[ \t]*\1){2,}[ \t]*$/gm, ' ')
    .replace(/^[ \t]{0,3}#{1,6}[ \t]+(.*?)(?:[ \t]+#+[ \t]*)?$/gm, '$1')
    .replace(/^[ \t]{0,3}(?:>[ \t]?)+/gm, '')
    .replace(/^[ \t]*[-*+][ \t]+\[[ xX]\][ \t]+/gm, '')
    .replace(/^[ \t]*[-*+][ \t]+/gm, '')
    .replace(/^[ \t]*\d+[.)][ \t]+/gm, '')

  // 7. 剥离行内加粗、斜体与删除线标记（避免误伤 snake_case 与乘法运算符）
  text = text
    .replace(/\*\*\*((?:\*(?!\*)|[^*\n])+?)\*\*\*/g, '$1')
    .replace(/___((?:_(?!_)|[^_\n])+?)___/g, '$1')
    .replace(/\*\*((?:\*(?!\*)|[^*\n])+?)\*\*/g, '$1')
    .replace(/__((?:_(?!_)|[^_\n])+?)__/g, '$1')
    .replace(/~~([^~\n]+?)~~/g, '$1')
    .replace(/\*(\S(?:[^*\n]*?\S)?)\*/g, '$1')
    .replace(/(?<![A-Za-z0-9])_(\S(?:[^_\n]*?\S)?)_(?![A-Za-z0-9])/g, '$1')

  // 8. 还原常见 Markdown 反斜杠转义（保留 LaTeX 命令如 \frac、\sum）
  text = text.replace(/\\([`*_{}[\]()#+\-.!>~|])/g, '$1')

  // 9. 恢复行内代码与转义美元符，并折叠连续空白为单空格
  text = text
    .replace(/\uE000CODE:(\d+)\uE001/g, (_match, index: string) => inlineCodes[Number(index)] ?? '')
    .replace(/\uE000DOLLAR\uE001/g, '$')

  const normalized = text.replace(/\s+/g, ' ').trim()

  if (maxLength === undefined || !Number.isFinite(maxLength)) {
    return normalized
  }
  if (maxLength <= 0) {
    return ''
  }
  if (normalized.length <= maxLength) {
    return normalized
  }
  if (maxLength === 1) {
    return '…'
  }
  return `${normalized.slice(0, maxLength - 1).trimEnd()}…`
}
