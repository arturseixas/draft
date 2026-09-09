import { Node, mergeAttributes, type Editor } from '@tiptap/core'
import { TextSelection } from '@tiptap/pm/state'
import { filmTypes, videoTypes, type Mode } from '../types'

export function moveBlock(editor: Editor, direction: -1 | 1) {
  const { state, view } = editor
  const { $from } = state.selection
  if ($from.depth < 1) return false
  const index = $from.index(0)
  const otherIndex = index + direction
  if (otherIndex < 0 || otherIndex >= state.doc.childCount) return false
  const blocks = [...Array(state.doc.childCount)].map((_, i) => state.doc.child(i))
  const positionInBlock = $from.pos - $from.before(1)
  ;[blocks[index], blocks[otherIndex]] = [blocks[otherIndex], blocks[index]]
  const tr = state.tr.replaceWith(0, state.doc.content.size, blocks)
  const newStart = blocks.slice(0, otherIndex).reduce((total, node) => total + node.nodeSize, 0)
  tr.setSelection(
    TextSelection.near(tr.doc.resolve(Math.min(newStart + positionInBlock, tr.doc.content.size))),
  )
  view.dispatch(tr.scrollIntoView())
  view.focus()
  return true
}

export const ScriptBlock = Node.create<{ mode: Mode }>({
  name: 'scriptBlock',
  group: 'block',
  content: 'inline*',
  defining: true,
  addOptions() {
    return { mode: 'film' }
  },
  addAttributes() {
    return {
      kind: { default: 'action', parseHTML: (element) => element.getAttribute('data-kind') },
    }
  },
  parseHTML() {
    return [{ tag: 'div[data-script]' }]
  },
  renderHTML({ node, HTMLAttributes }) {
    const label =
      [...filmTypes, ...videoTypes].find((item) => item.id === node.attrs.kind)?.label ?? 'Ação'
    return [
      'div',
      mergeAttributes(HTMLAttributes, {
        'data-script': '',
        'data-kind': node.attrs.kind,
        'data-label': label,
      }),
      0,
    ]
  },
  addKeyboardShortcuts() {
    const options = this.options.mode === 'film' ? filmTypes : videoTypes
    const cycle = (direction: number) => {
      if (this.options.mode === 'free' || !this.editor.isActive('scriptBlock')) return false
      const kind = this.editor.getAttributes('scriptBlock').kind
      const index = options.findIndex((item) => item.id === kind)
      return this.editor.commands.updateAttributes('scriptBlock', {
        kind: options[(index + direction + options.length) % options.length].id,
      })
    }
    return {
      Tab: () => cycle(1),
      'Shift-Tab': () => cycle(-1),
      'Mod-Alt-ArrowUp': () => moveBlock(this.editor, -1),
      'Mod-Alt-ArrowDown': () => moveBlock(this.editor, 1),
      ...Object.fromEntries(
        options.map((type, index) => [
          `Alt-${index + 1}`,
          () =>
            this.options.mode !== 'free' &&
            this.editor.chain().focus().setNode('scriptBlock', { kind: type.id }).run(),
        ]),
      ),
      Enter: () => {
        if (!this.editor.isActive('scriptBlock')) return false
        const { $from } = this.editor.state.selection
        let kind = $from.parent.attrs.kind as string
        if (this.options.mode === 'film') {
          const value = $from.parent.textContent.trim()
          if (/^(INT\.?|EXT\.?|INT\.?\s*\/\s*EXT\.?)\s/i.test(value)) kind = 'scene'
          else if (
            kind === 'action' &&
            value.length > 0 &&
            value.length < 40 &&
            /^[\p{Lu}\s.()]+$/u.test(value)
          )
            kind = 'character'
          const next: Record<string, string> = {
            scene: 'action',
            action: 'action',
            character: 'dialogue',
            dialogue: 'action',
            parenthetical: 'dialogue',
            transition: 'scene',
            note: 'action',
          }
          return this.editor
            .chain()
            .updateAttributes('scriptBlock', { kind })
            .splitBlock()
            .setNode('scriptBlock', { kind: next[kind] ?? 'action' })
            .run()
        }
        return this.editor.chain().splitBlock().setNode('scriptBlock', { kind }).run()
      },
    }
  },
})

export const Callout = Node.create({
  name: 'callout',
  group: 'block',
  content: 'inline*',
  defining: true,
  parseHTML() {
    return [{ tag: 'aside[data-callout]' }]
  },
  renderHTML({ HTMLAttributes }) {
    return [
      'aside',
      mergeAttributes(HTMLAttributes, { 'data-callout': '', class: 'writing-callout' }),
      0,
    ]
  },
  addKeyboardShortcuts() {
    return {
      Enter: () =>
        this.editor.isActive('callout')
          ? this.editor.chain().splitBlock().setParagraph().run()
          : false,
    }
  },
})
