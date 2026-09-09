import type { JSONContent } from '@tiptap/react'

export type Mode = 'free' | 'film' | 'video'
export interface DraftDocument {
  id: string
  title: string
  mode: Mode
  createdAt: number
  updatedAt: number
  favorite: boolean
  content: JSONContent
}
export const modes: Record<Mode, { label: string; short: string; description: string }> = {
  free: {
    label: 'Escrita livre',
    short: 'Livre',
    description: 'Ideias, notas e histórias sem formato definido.',
  },
  film: {
    label: 'Roteiro de filme',
    short: 'Filme',
    description: 'Da primeira cena ao último diálogo.',
  },
  video: {
    label: 'Roteiro de vídeo',
    short: 'Vídeo',
    description: 'Narração, imagens e som no mesmo lugar.',
  },
}
export const filmTypes = [
  { id: 'scene', label: 'Cabeçalho de cena', hint: 'INT. / EXT.' },
  { id: 'action', label: 'Ação', hint: 'O que acontece' },
  { id: 'character', label: 'Personagem', hint: 'Quem fala' },
  { id: 'dialogue', label: 'Diálogo', hint: 'O que é dito' },
  { id: 'parenthetical', label: 'Parêntese', hint: 'Intenção da fala' },
  { id: 'transition', label: 'Transição', hint: 'CORTE PARA:' },
  { id: 'note', label: 'Nota', hint: 'Anotação de roteiro' },
]
export const videoTypes = [
  { id: 'narration', label: 'Fala / narração', hint: 'A voz da história' },
  { id: 'broll', label: 'Imagem / B-roll', hint: 'O que vemos' },
  { id: 'screen', label: 'Texto na tela', hint: 'Palavras em cena' },
  { id: 'music', label: 'Música', hint: 'A trilha' },
  { id: 'sound', label: 'Efeito sonoro', hint: 'O som do momento' },
  { id: 'editing', label: 'Observação de edição', hint: 'Ritmo e montagem' },
]
export function plainText(node: JSONContent): string {
  if (node.type === 'text') return node.text ?? ''
  if (node.type === 'hardBreak') return '\n'
  return (node.content ?? [])
    .map(plainText)
    .join(
      ['doc', 'bulletList', 'orderedList', 'taskList', 'blockquote'].includes(node.type ?? '')
        ? '\n'
        : '',
    )
}
export function wordCount(content: JSONContent) {
  const text = plainText(content)
  return {
    words: text.trim() ? text.trim().split(/\s+/u).length : 0,
    characters: [...text.replace(/\n/g, '')].length,
  }
}
export function emptyContent(mode: Mode): JSONContent {
  return {
    type: 'doc',
    content: [
      {
        type: mode === 'free' ? 'paragraph' : 'scriptBlock',
        ...(mode !== 'free' ? { attrs: { kind: mode === 'film' ? 'scene' : 'narration' } } : {}),
      },
    ],
  }
}
export function normalize(text: string) {
  return text
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
}
export function convertContent(content: JSONContent, mode: Mode): JSONContent {
  const map = (node: JSONContent): JSONContent => {
    const next = { ...node, ...(node.content ? { content: node.content.map(map) } : {}) }
    if (node.type === 'scriptBlock') {
      if (mode === 'free') return { ...next, type: 'paragraph', attrs: {} }
      const allowed = mode === 'film' ? filmTypes : videoTypes
      return {
        ...next,
        attrs: {
          kind: allowed.some((t) => t.id === node.attrs?.kind)
            ? node.attrs?.kind
            : mode === 'film'
              ? 'action'
              : 'narration',
        },
      }
    }
    return next
  }
  const next = map(content)
  if (mode !== 'free')
    next.content = next.content?.map((node) =>
      node.type === 'paragraph'
        ? {
            ...node,
            type: 'scriptBlock',
            attrs: { kind: mode === 'film' ? 'action' : 'narration' },
          }
        : node,
    )
  return next
}
