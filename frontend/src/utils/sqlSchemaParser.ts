/**
 * 轻量级纯前端 SQL DDL 与 INSERT 语法解析器
 * 零第三方依赖，专用于将 MySQL 数据库题目的表结构与测试点初始数据转换为人机友好的结构化表格对象。
 */

export interface ColumnDefinition {
  name: string
  type: string
  isPrimaryKey: boolean
  isNullable: boolean
  extra: string
  comment?: string
}

export interface ParsedTableSchema {
  tableName: string
  columns: ColumnDefinition[]
  rawSql: string
}

export interface ParsedSeedTable {
  tableName: string
  columns: string[]
  rows: string[][]
  rawSql: string
}

/**
 * 清洗标识符（如去除反引号与多余空格）
 */
function cleanIdentifier(id: string): string {
  return id.trim().replace(/^[`'"]+|[`'"]+$/g, '')
}

/**
 * 分割顶层逗号（忽略括号内的逗号，如 DECIMAL(10,2)）
 */
function splitTopLevelCommas(str: string): string[] {
  const parts: string[] = []
  let current = ''
  let depth = 0
  let inQuote: string | null = null

  for (let i = 0; i < str.length; i++) {
    const char = str[i]
    if (inQuote) {
      current += char
      if (char === inQuote && str[i - 1] !== '\\') {
        inQuote = null
      }
    } else if (char === "'" || char === '"' || char === '`') {
      inQuote = char
      current += char
    } else if (char === '(') {
      depth++
      current += char
    } else if (char === ')') {
      depth = Math.max(0, depth - 1)
      current += char
    } else if (char === ',' && depth === 0) {
      if (current.trim()) {
        parts.push(current.trim())
      }
      current = ''
    } else {
      current += char
    }
  }

  if (current.trim()) {
    parts.push(current.trim())
  }
  return parts
}

/**
 * 解析 CREATE TABLE 语句（支持多张表）
 */
export function parseSchemaSql(schemaSql: string): ParsedTableSchema[] {
  if (!schemaSql || !schemaSql.trim()) return []

  // 1. 清理 SQL 单行与多行注释
  let cleaned = schemaSql.replace(/\/\*[\s\S]*?\*\//g, '')
  cleaned = cleaned.replace(/--.*$/gm, '')

  const tables: ParsedTableSchema[] = []
  // 匹配 CREATE TABLE [IF NOT EXISTS] tableName (
  const createRegex = /CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?([`\w]+)\s*\(/gi
  let match: RegExpExecArray | null

  while ((match = createRegex.exec(cleaned)) !== null) {
    const tableName = cleanIdentifier(match[1])
    const startIdx = match.index + match[0].length
    let depth = 1
    let inQuote: string | null = null
    let endIdx = -1

    // 基于括号深度精确找到该表的闭合括号，无惧任何复杂的表级选项或引擎参数
    for (let i = startIdx; i < cleaned.length; i++) {
      const ch = cleaned[i]
      if (inQuote) {
        if (ch === inQuote && cleaned[i - 1] !== '\\') {
          inQuote = null
        }
      } else if (ch === "'" || ch === '"' || ch === '`') {
        inQuote = ch
      } else if (ch === '(') {
        depth++
      } else if (ch === ')') {
        depth--
        if (depth === 0) {
          endIdx = i
          break
        }
      }
    }

    if (endIdx === -1) continue

    const body = cleaned.substring(startIdx, endIdx)
    let semiIdx = cleaned.indexOf(';', endIdx)
    if (semiIdx === -1) semiIdx = cleaned.length
    const rawSql = cleaned.substring(match.index, semiIdx + 1).trim()
    createRegex.lastIndex = semiIdx + 1

    const defs = splitTopLevelCommas(body)
    const columns: ColumnDefinition[] = []
    const pkColumns = new Set<string>()

    // 第一遍提取表级 PRIMARY KEY (col1, col2)
    for (const def of defs) {
      const line = def.trim()
      const pkMatch = line.match(/^PRIMARY\s+KEY\s*\((.*?)\)/i)
      if (pkMatch) {
        const cols = pkMatch[1].split(',').map((c) => cleanIdentifier(c))
        cols.forEach((c) => pkColumns.add(c))
      }
    }

    // 第二遍解析各个字段定义
    for (const def of defs) {
      const line = def.trim()
      // 跳过纯表级约束（PRIMARY KEY、KEY、INDEX、CONSTRAINT、UNIQUE KEY、FOREIGN KEY 等）
      if (
        /^(?:PRIMARY\s+KEY|KEY|INDEX|CONSTRAINT|UNIQUE\s+KEY|FOREIGN\s+KEY)\b/i.test(
          line,
        )
      ) {
        continue
      }

      // 匹配字段名与类型：colName TYPE [attributes...]
      const colMatch = line.match(/^([`\w]+)\s+([A-Za-z]+(?:\s*\([^)]+\))?)(.*)$/)
      if (!colMatch) continue

      const colName = cleanIdentifier(colMatch[1])
      const colType = colMatch[2].trim().toUpperCase()
      const rest = colMatch[3].trim()

      const isInlinePk = /PRIMARY\s+KEY/i.test(rest)
      const isPk = isInlinePk || pkColumns.has(colName)
      const isNotNull = /NOT\s+NULL/i.test(rest) || isPk
      const isAutoIncrement = /AUTO_INCREMENT/i.test(rest)

      // 提取额外约束或默认值
      const extras: string[] = []
      if (isPk) extras.push('PRIMARY KEY')
      if (isAutoIncrement) extras.push('AUTO_INCREMENT')
      if (isNotNull && !isPk) extras.push('NOT NULL')

      const defaultMatch = rest.match(/DEFAULT\s+([^, ]+)/i)
      if (defaultMatch) {
        extras.push(`DEFAULT ${defaultMatch[1]}`)
      }

      const commentMatch = rest.match(/COMMENT\s+['"](.*?)['"]/i)
      const comment = commentMatch ? commentMatch[1] : undefined

      columns.push({
        name: colName,
        type: colType,
        isPrimaryKey: isPk,
        isNullable: !isNotNull,
        extra: extras.join(' '),
        comment,
      })
    }

    tables.push({
      tableName,
      columns,
      rawSql,
    })
  }

  return tables
}

/**
 * 分割 INSERT VALUES 元组 (val1, val2), (val3, val4)
 */
function parseTuples(valuesBody: string): string[][] {
  const rows: string[][] = []
  let depth = 0
  let inQuote: string | null = null
  let currentTuple = ''

  for (let i = 0; i < valuesBody.length; i++) {
    const char = valuesBody[i]
    if (inQuote) {
      currentTuple += char
      if (char === inQuote && valuesBody[i - 1] !== '\\') {
        inQuote = null
      }
    } else if (char === "'" || char === '"') {
      inQuote = char
      currentTuple += char
    } else if (char === '(') {
      if (depth === 0) {
        currentTuple = ''
      } else {
        currentTuple += char
      }
      depth++
    } else if (char === ')') {
      depth = Math.max(0, depth - 1)
      if (depth === 0) {
        // 当前元组结束，解析元组内的值
        const cells = splitTopLevelCommas(currentTuple).map((cell) => {
          const trimmed = cell.trim()
          if (/^NULL$/i.test(trimmed)) return 'NULL'
          // 去除包裹的引号
          if (
            (trimmed.startsWith("'") && trimmed.endsWith("'")) ||
            (trimmed.startsWith('"') && trimmed.endsWith('"'))
          ) {
            return trimmed.slice(1, -1).replace(/\\'/g, "'").replace(/\\"/g, '"')
          }
          return trimmed
        })
        rows.push(cells)
        currentTuple = ''
      } else {
        currentTuple += char
      }
    } else if (depth > 0) {
      currentTuple += char
    }
  }

  return rows
}

/**
 * 解析 INSERT INTO 语句，生成清晰的数据表格
 */
export function parseSeedSql(
  seedSql: string,
  schemas: ParsedTableSchema[] = [],
): ParsedSeedTable[] {
  if (!seedSql || !seedSql.trim()) return []

  // 清理 SQL 注释
  let cleaned = seedSql.replace(/\/\*[\s\S]*?\*\//g, '')
  cleaned = cleaned.replace(/--.*$/gm, '')

  const result: ParsedSeedTable[] = []
  // 匹配 INSERT INTO tableName [(col1, col2)] VALUES (...);
  const insertRegex =
    /INSERT\s+INTO\s+([`\w]+)(?:\s*\(([^)]+)\))?\s+VALUES\s*([\s\S]*?)(?:;|$)/gi

  let match: RegExpExecArray | null
  while ((match = insertRegex.exec(cleaned)) !== null) {
    const tableName = cleanIdentifier(match[1])
    const explicitCols = match[2]
      ? match[2].split(',').map((c) => cleanIdentifier(c))
      : null
    const valuesPart = match[3]

    let columns: string[] = []
    if (explicitCols && explicitCols.length > 0) {
      columns = explicitCols
    } else {
      // 从对应的 Schema 寻找列名
      const targetTable = schemas.find(
        (t) => t.tableName.toLowerCase() === tableName.toLowerCase(),
      )
      if (targetTable && targetTable.columns.length > 0) {
        columns = targetTable.columns.map((c) => c.name)
      }
    }

    const rows = parseTuples(valuesPart)

    // 如果还没有表头（未匹配到 Schema 且未显式指定），按 col_1, col_2 垫底
    if (columns.length === 0 && rows.length > 0) {
      columns = rows[0].map((_, idx) => `col_${idx + 1}`)
    }

    // 若同一张表已存在（例如分多次 INSERT 写入同一张表），直接追加数据行
    const existing = result.find(
      (r) => r.tableName.toLowerCase() === tableName.toLowerCase(),
    )
    if (existing) {
      existing.rows.push(...rows)
      if (existing.columns.length === 0 && columns.length > 0) {
        existing.columns = columns
      }
    } else {
      result.push({
        tableName,
        columns,
        rows,
        rawSql: match[0].trim(),
      })
    }
  }

  return result
}

/**
 * 出题辅助：一键将 DDL 和 Seed 生成 LeetCode 风格的标准 Markdown 题面模板
 */
export function generateMarkdownTemplate(
  schemaSql: string,
  seedSql: string = '',
  title: string = '',
  expectedOutput: string = '',
): string {
  const schemas = parseSchemaSql(schemaSql)
  const seeds = parseSeedSql(seedSql, schemas)

  let md = `## 题目要求\n\n`
  if (title.trim()) {
    md += `编写 SQL，完成针对【${title.trim()}】的相关查询或数据操作。\n\n`
  } else {
    md += `请编写 SQL，按要求完成数据查询或操作。\n\n`
  }

  // 1. 表结构描述
  md += `## 数据库表结构\n\n`
  if (schemas.length > 0) {
    for (const table of schemas) {
      md += `### \`${table.tableName}\` 表：\n\n`
      md += `| 字段名 | 类型 | 说明 |\n`
      md += `| :--- | :--- | :--- |\n`
      for (const col of table.columns) {
        const extraNote = col.isPrimaryKey ? '主键' : col.comment || '-'
        md += `| \`${col.name}\` | \`${col.type}\` | ${extraNote} |\n`
      }
      md += `\n`
    }
  }

  // 2. 样例输入
  md += `## 样例输入与期望输出\n\n`
  if (seeds.length > 0) {
    for (const seed of seeds) {
      md += `**输入：**\n\n\`${seed.tableName}\` 表：\n\n`
      if (seed.columns.length > 0) {
        md += `| ` + seed.columns.join(' | ') + ` |\n`
        md += `| ` + seed.columns.map(() => ':---').join(' | ') + ` |\n`
        for (const row of seed.rows) {
          md += `| ` + row.join(' | ') + ` |\n`
        }
        md += `\n`
      }
    }
  }

  // 3. 期望输出与解释模板
  md += `**期望输出：**\n\n`
  const parsedExp = parseExpectedOutput(expectedOutput)
  if (parsedExp && parsedExp.columns.length > 0) {
    md += `| ` + parsedExp.columns.map((c) => c.name).join(' | ') + ` |\n`
    md += `| ` + parsedExp.columns.map(() => ':---').join(' | ') + ` |\n`
    for (const row of parsedExp.rows) {
      md += `| ` + row.join(' | ') + ` |\n`
    }
    md += `\n`
  } else {
    md += `| id | name |\n`
    md += `| :--- | :--- |\n`
    md += `| 1 | Alice |\n`
    md += `| 2 | Bob |\n\n`
  }

  md += `**解释：**\n\n`
  md += `满足条件的记录结果如上表所示。\n`

  return md
}

export interface ParsedResultSet {
  columns: Array<{ name: string; type?: string }>
  rows: string[][]
}

function isJsonRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function parseJsonColumn(column: unknown): { name: string; type?: string } {
  if (typeof column === 'string') return { name: column }
  if (!isJsonRecord(column)) return { name: '' }
  return {
    name: typeof column.name === 'string' ? column.name : '',
    type: typeof column.type === 'string' ? column.type : undefined,
  }
}

/**
 * 解析各种格式的期望输出结果集（支持 JSON 结果集、Markdown 管道表格、制表符表格）
 */
export function parseExpectedOutput(raw?: string | null): ParsedResultSet | null {
  if (!raw || !raw.trim()) return null
  const trimmed = raw.trim()

  // 1. JSON 格式: {"columns":[{"name":"id","type":"INT"}],"rows":[["1"]]}
  try {
    const obj: unknown = JSON.parse(trimmed)
    if (isJsonRecord(obj) && Array.isArray(obj.columns) && Array.isArray(obj.rows)) {
      return {
        columns: obj.columns.map(parseJsonColumn),
        rows: obj.rows.filter(Array.isArray).map((row) => row.map((cell) => String(cell))),
      }
    }
  } catch {
    /* 不是 JSON，继续尝试表格解析 */
  }

  // 2. Markdown 管道表格:
  // | id | name |
  // | :--- | :--- |
  // | 1  | Alice |
  if (trimmed.includes('|')) {
    const lines = trimmed
      .split('\n')
      .map((l) => l.trim())
      .filter((l) => l.length > 0 && !/^\|?\s*[-:+| ]+\s*\|?$/.test(l))
    if (lines.length >= 1) {
      const parseCells = (line: string) => {
        const cells = line.split('|').map((s) => s.trim())
        if (line.startsWith('|')) cells.shift()
        if (line.endsWith('|')) cells.pop()
        return cells
      }

      const headers = parseCells(lines[0])
      const rows = lines.slice(1).map(parseCells)
      if (headers.length > 0) {
        return {
          columns: headers.map((h) => ({ name: h })),
          rows,
        }
      }
    }
  }

  return null
}

