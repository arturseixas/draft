import type { JSONContent } from '@tiptap/react'
import { filmTypes, videoTypes, plainText, type DraftDocument } from '../types'
import { newId } from './id'

function inline(node: JSONContent): string {
  if (node.type === 'hardBreak') return '  \n'
  if (node.type !== 'text') return (node.content ?? []).map(inline).join('')
  let value = (node.text ?? '').replace(/([\\`*_[\]<>])/g, '\\$1')
  for (const mark of node.marks ?? []) {
    if (mark.type === 'bold') value = `**${value}**`
    if (mark.type === 'italic') value = `*${value}*`
    if (mark.type === 'strike') value = `~~${value}~~`
    if (mark.type === 'highlight') value = `<mark>${value}</mark>`
    if (mark.type === 'code') {
      const raw = node.text ?? ''
      const delimiter = '`'.repeat(
        Math.max(0, ...(raw.match(/`+/g) ?? []).map((run) => run.length)) + 1,
      )
      value = `${delimiter} ${raw} ${delimiter}`
    }
    if (mark.type === 'link')
      value = `[${value}](${String(mark.attrs?.href ?? '').replace(/\)/g, '%29')})`
  }
  return value
}
export function markdownNode(node: JSONContent): string {
  const children = () => (node.content ?? []).map(markdownNode).join('\n\n')
  switch (node.type) {
    case 'doc':
      return children()
    case 'heading':
      return `${'#'.repeat(node.attrs?.level ?? 2)} ${inline(node)}`
    case 'horizontalRule':
      return '---'
    case 'blockquote':
      return children()
        .split('\n')
        .map((line) => `> ${line}`)
        .join('\n')
    case 'callout':
      return `> [!NOTE]\n> ${inline(node)}`
    case 'codeBlock': {
      const value = plainText(node)
      const fence = '`'.repeat(
        Math.max(2, ...(value.match(/`+/g) ?? []).map((run) => run.length)) + 1,
      )
      return `${fence}${node.attrs?.language ?? ''}\n${value}\n${fence}`
    }
    case 'bulletList':
    case 'orderedList':
    case 'taskList':
      return (node.content ?? [])
        .map((item, i) => {
          const prefix =
            node.type === 'orderedList'
              ? `${(node.attrs?.start ?? 1) + i}. `
              : node.type === 'taskList'
                ? `- [${item.attrs?.checked ? 'x' : ' '}] `
                : '- '
          return prefix + markdownNode(item).replace(/\n/g, '\n' + ' '.repeat(prefix.length))
        })
        .join('\n')
    case 'listItem':
    case 'taskItem':
      return children()
    case 'scriptBlock': {
      const kind = node.attrs?.kind
      const value = inline(node)
      if (kind === 'scene' || kind === 'character' || kind === 'transition')
        return `**${value.toLocaleUpperCase('pt-BR')}**`
      if (kind === 'parenthetical') return `*${value}*`
      if (kind === 'note') return `> Nota: ${value}`
      const label = videoTypes.find((type) => type.id === kind)?.label
      return label ? `**${label}**\n\n${value}` : value
    }
    default:
      return inline(node)
  }
}
function wrap(value: string, width: number, indent = 0) {
  const lines: string[] = []
  for (const paragraph of value.split('\n')) {
    let line = ''
    for (const word of paragraph.split(/\s+/)) {
      if (line && line.length + word.length + 1 > width) {
        lines.push(' '.repeat(indent) + line)
        line = word
      } else line += (line ? ' ' : '') + word
    }
    lines.push(' '.repeat(indent) + line)
  }
  return lines.join('\n')
}
function txtNode(node: JSONContent): string {
  if (node.type === 'taskList')
    return (node.content ?? [])
      .map((item) => `[${item.attrs?.checked ? 'x' : ' '}] ${plainText(item)}`)
      .join('\n')
  if (node.type === 'bulletList' || node.type === 'orderedList')
    return (node.content ?? [])
      .map((item, i) => `${node.type === 'bulletList' ? '•' : `${i + 1}.`} ${plainText(item)}`)
      .join('\n')
  if (node.type === 'horizontalRule') return '──────────────────────────────'
  if (node.type !== 'scriptBlock') return plainText(node)
  const value = plainText(node)
  const kind = node.attrs?.kind
  const label = videoTypes.find((type) => type.id === kind)?.label
  if (label) return `${label.toLocaleUpperCase('pt-BR')}\n${value}`
  if (kind === 'character') return wrap(value.toLocaleUpperCase('pt-BR'), 30, 24)
  if (kind === 'dialogue') return wrap(value, 38, 12)
  if (kind === 'parenthetical') return wrap(value, 28, 18)
  if (kind === 'transition') return wrap(value.toLocaleUpperCase('pt-BR'), 30, 30)
  if (kind === 'note') return `[NOTA: ${value}]`
  return wrap(kind === 'scene' ? value.toLocaleUpperCase('pt-BR') : value, 64)
}
export function downloadFile(filename: string, content: string, type: string) {
  const url = URL.createObjectURL(new Blob([content], { type }))
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  link.remove()
  setTimeout(() => URL.revokeObjectURL(url), 10000)
}
export function exportDocument(document: DraftDocument, format: 'md' | 'txt') {
  const filename = document.title.replace(/[<>:"/\\|?*\u0000-\u001F]/g, '').trim() || 'Sem título'
  const content =
    format === 'md'
      ? `# ${document.title}\n\n${markdownNode(document.content)}\n`
      : `${document.title}\n${'='.repeat(Math.min(document.title.length, 64))}\n\n${(document.content.content ?? []).map(txtNode).join('\n\n')}\n`
  downloadFile(
    `${filename}.${format}`,
    content,
    format === 'md' ? 'text/markdown;charset=utf-8' : 'text/plain;charset=utf-8',
  )
}
export function printDocument(title: string) {
  const previous = document.title
  document.title = title
  window.addEventListener(
    'afterprint',
    () => {
      document.title = previous
    },
    { once: true },
  )
  window.print()
}
const validTypes = new Set([
  'doc',
  'paragraph',
  'text',
  'heading',
  'bulletList',
  'orderedList',
  'listItem',
  'taskList',
  'taskItem',
  'blockquote',
  'codeBlock',
  'hardBreak',
  'horizontalRule',
  'callout',
  'scriptBlock',
])
function validNode(node: unknown, depth = 0): node is JSONContent {
  if (!node || typeof node !== 'object' || depth > 30) return false
  const n = node as JSONContent
  if (!n.type || !validTypes.has(n.type)) return false
  if (n.text !== undefined && typeof n.text !== 'string') return false
  if (
    n.content !== undefined &&
    (!Array.isArray(n.content) || !n.content.every((child) => validNode(child, depth + 1)))
  )
    return false
  if (
    n.type === 'scriptBlock' &&
    ![...filmTypes, ...videoTypes].some((type) => type.id === n.attrs?.kind)
  )
    return false
  if (n.type === 'heading' && ![1, 2, 3].includes(n.attrs?.level)) return false
  if (n.type === 'text' && !n.text) return false
  if (
    n.marks &&
    (!Array.isArray(n.marks) ||
      n.marks.some(
        (mark) =>
          !['bold', 'italic', 'strike', 'underline', 'code', 'highlight', 'link'].includes(
            mark.type,
          ),
      ))
  )
    return false
  if (
    n.marks?.some(
      (mark) =>
        mark.type === 'link' &&
        (typeof mark.attrs?.href !== 'string' || !/^(https?:\/\/|mailto:)/i.test(mark.attrs.href)),
    )
  )
    return false
  const blockTypes = new Set([
    'paragraph',
    'heading',
    'bulletList',
    'orderedList',
    'taskList',
    'blockquote',
    'codeBlock',
    'horizontalRule',
    'callout',
    'scriptBlock',
  ])
  const children = n.content ?? []
  if (
    ['doc', 'blockquote', 'listItem', 'taskItem'].includes(n.type) &&
    children.some((child) => !blockTypes.has(child.type!))
  )
    return false
  if (
    ['paragraph', 'heading', 'codeBlock', 'callout', 'scriptBlock'].includes(n.type) &&
    children.some((child) => child.type !== 'text' && child.type !== 'hardBreak')
  )
    return false
  if (
    ['bulletList', 'orderedList', 'taskList'].includes(n.type) &&
    (!children.length ||
      children.some((child) => child.type !== (n.type === 'taskList' ? 'taskItem' : 'listItem')))
  )
    return false
  if (['listItem', 'taskItem'].includes(n.type) && children[0]?.type !== 'paragraph') return false
  if (['text', 'hardBreak', 'horizontalRule'].includes(n.type) && children.length) return false
  return true
}
export function parseBackup(value: string): DraftDocument[] {
  const data = JSON.parse(value)
  if (!data || data.version !== 1 || !Array.isArray(data.documents))
    throw new Error('Este arquivo não é um backup válido do Draft.')
  if (
    !data.documents.every(
      (d: DraftDocument) =>
        d &&
        typeof d.title === 'string' &&
        d.title.length <= 250 &&
        ['free', 'film', 'video'].includes(d.mode) &&
        validNode(d.content) &&
        d.content.type === 'doc',
    )
  )
    throw new Error('O backup contém documentos inválidos.')
  return data.documents.map((d: DraftDocument) => ({
    id: newId(),
    title: d.title,
    mode: d.mode,
    content: d.content,
    favorite: Boolean(d.favorite),
    createdAt:
      typeof d.createdAt === 'number' &&
      Number.isFinite(d.createdAt) &&
      Math.abs(d.createdAt) <= 8.64e15
        ? d.createdAt
        : Date.now(),
    updatedAt: Date.now(),
  }))
}
