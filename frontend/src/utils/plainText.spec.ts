import { describe, expect, it } from 'vitest'
import { stripMarkdown } from './plainText'

describe('stripMarkdown', () => {
  it('空字符串直接返回空串', () => {
    expect(stripMarkdown('')).toBe('')
  })

  it('剥离围栏代码块并保留上下文', () => {
    const md = [
      '下列代码执行后的输出是？',
      '```cpp',
      'int a = 1;',
      'cout << a;',
      '```',
      '请选择正确选项。',
    ].join('\n')

    expect(stripMarkdown(md)).toBe('下列代码执行后的输出是？ 请选择正确选项。')

    const tilde = '前文\n~~~sql\nSELECT * FROM emp;\n~~~\n后文'
    expect(stripMarkdown(tilde)).toBe('前文 后文')
  })

  it('剥离行内代码反引号并保护内部下划线与美元符号', () => {
    const md = '某主机 IP 为 `192.168.1.130`，变量 `user_name` 与 `max_val` 读取 `$PATH`。'
    expect(stripMarkdown(md)).toBe(
      '某主机 IP 为 192.168.1.130，变量 user_name 与 max_val 读取 $PATH。',
    )
  })

  it('剥离 Markdown 链接与图片语法并保留可读文字', () => {
    const md = '参考 [RFC 793](https://example.com/rfc(1)) 与图 ![TCP 状态机](https://a.test/fsm.png) ![](empty.png)'
    expect(stripMarkdown(md)).toBe('参考 RFC 793 与图 TCP 状态机')
  })

  it('剥离 LaTeX 定界符 $ 与 $$ 并保留表达式正文与转义美元符', () => {
    const md = '时间复杂度为 $O(n \\log n)$，售价 \\$10，公式：\n$$\n\\frac{n(n+1)}{2}\n$$'
    expect(stripMarkdown(md)).toBe('时间复杂度为 O(n \\log n)，售价 $10，公式： \\frac{n(n+1)}{2}')
  })

  it('剥离标题、加粗、斜体、删除线、引用与列表语法标记并折叠空白', () => {
    const md = [
      '## 计算机网络（C# 版本）',
      '> 传输层提供 **端到端可靠传输** 与 *流量控制* 以及 ~~旧协议~~',
      '- 第一项 __重要__',
      '1. 第二项 ***核心***',
      '',
      '   多余   空白\t换行',
    ].join('\n')

    expect(stripMarkdown(md)).toBe(
      '计算机网络（C# 版本） 传输层提供 端到端可靠传输 与 流量控制 以及 旧协议 第一项 重要 第二项 核心 多余 空白 换行',
    )
  })

  it('支持按 maxLength 截断并追加省略号 …', () => {
    expect(stripMarkdown('**简短标题**', 20)).toBe('简短标题')
    expect(stripMarkdown('这是一段非常长的**客观题题干**用于测试截断效果', 10)).toBe(
      '这是一段非常长的客…',
    )
    expect(stripMarkdown('hello world test', 6)).toBe('hello…')
    expect(stripMarkdown('hello world', 1)).toBe('…')
    expect(stripMarkdown('hello world', 0)).toBe('')
  })
})
