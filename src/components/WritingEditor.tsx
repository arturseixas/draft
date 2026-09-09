import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { closeHistory } from '@tiptap/pm/history'
import {
  EditorContent,
  useEditor,
  useEditorState,
  type Editor,
  type JSONContent,
} from '@tiptap/react'
import StarterKit from '@tiptap/starter-kit'
import Highlight from '@tiptap/extension-highlight'
import TaskList from '@tiptap/extension-task-list'
import TaskItem from '@tiptap/extension-task-item'
import Placeholder from '@tiptap/extension-placeholder'
import { DragHandle } from '@tiptap/extension-drag-handle-react'
import { TextB } from '@phosphor-icons/react/dist/csr/TextB'
import { TextItalic } from '@phosphor-icons/react/dist/csr/TextItalic'
import { Highlighter } from '@phosphor-icons/react/dist/csr/Highlighter'
import { LinkSimple } from '@phosphor-icons/react/dist/csr/LinkSimple'
import { ArrowCounterClockwise } from '@phosphor-icons/react/dist/csr/ArrowCounterClockwise'
import { ArrowClockwise } from '@phosphor-icons/react/dist/csr/ArrowClockwise'
import { CaretDown } from '@phosphor-icons/react/dist/csr/CaretDown'
import { DotsSixVertical } from '@phosphor-icons/react/dist/csr/DotsSixVertical'
import { TextHOne } from '@phosphor-icons/react/dist/csr/TextHOne'
import { TextHTwo } from '@phosphor-icons/react/dist/csr/TextHTwo'
import { TextHThree } from '@phosphor-icons/react/dist/csr/TextHThree'
import { ListBullets } from '@phosphor-icons/react/dist/csr/ListBullets'
import { ListNumbers } from '@phosphor-icons/react/dist/csr/ListNumbers'
import { CheckSquare } from '@phosphor-icons/react/dist/csr/CheckSquare'
import { Quotes } from '@phosphor-icons/react/dist/csr/Quotes'
import { Minus } from '@phosphor-icons/react/dist/csr/Minus'
import { Code } from '@phosphor-icons/react/dist/csr/Code'
import { Lightbulb } from '@phosphor-icons/react/dist/csr/Lightbulb'
import { TextT } from '@phosphor-icons/react/dist/csr/TextT'
import { ArrowUp } from '@phosphor-icons/react/dist/csr/ArrowUp'
import { ArrowDown } from '@phosphor-icons/react/dist/csr/ArrowDown'
import { Plus } from '@phosphor-icons/react/dist/csr/Plus'
import { Check } from '@phosphor-icons/react/dist/csr/Check'
import { ScriptBlock, Callout, moveBlock } from '../editor/extensions'
import { filmTypes, videoTypes, normalize, type DraftDocument } from '../types'
import { IconButton, Menu, MenuItem, MenuSeparator, Modal } from './ui'

interface BlockCommand {
  id: string
  label: string
  description: string
  icon: ReactNode
  run: (editor: Editor) => void
}
const freeCommands: BlockCommand[] = [
  {
    id: 'paragraph',
    label: 'Texto',
    description: 'Deixe as palavras fluírem',
    icon: <TextT />,
    run: (e) => {
      e.chain().focus().setParagraph().run()
    },
  },
  ...([1, 2, 3] as const).map((level) => ({
    id: `heading${level}`,
    label: `Título ${level}`,
    description: ['Uma nova seção', 'Um novo assunto', 'Um pequeno detalhe'][level - 1],
    icon: level === 1 ? <TextHOne /> : level === 2 ? <TextHTwo /> : <TextHThree />,
    run: (e: Editor) => {
      e.chain().focus().setHeading({ level }).run()
    },
  })),
  {
    id: 'bulletList',
    label: 'Lista',
    description: 'Uma ideia de cada vez',
    icon: <ListBullets />,
    run: (e) => {
      e.chain().focus().toggleBulletList().run()
    },
  },
  {
    id: 'orderedList',
    label: 'Lista numerada',
    description: 'Passo a passo',
    icon: <ListNumbers />,
    run: (e) => {
      e.chain().focus().toggleOrderedList().run()
    },
  },
  {
    id: 'taskList',
    label: 'Checklist',
    description: 'Pequenos passos, grandes histórias',
    icon: <CheckSquare />,
    run: (e) => {
      e.chain().focus().toggleTaskList().run()
    },
  },
  {
    id: 'blockquote',
    label: 'Citação',
    description: 'Palavras que merecem espaço',
    icon: <Quotes />,
    run: (e) => {
      e.chain().focus().toggleBlockquote().run()
    },
  },
  {
    id: 'callout',
    label: 'Callout',
    description: 'Uma observação em destaque',
    icon: <Lightbulb />,
    run: (e) => {
      e.chain().focus().setNode('callout').run()
    },
  },
  {
    id: 'codeBlock',
    label: 'Código',
    description: 'Texto monoespaçado',
    icon: <Code />,
    run: (e) => {
      e.chain().focus().toggleCodeBlock().run()
    },
  },
  {
    id: 'horizontalRule',
    label: 'Divisor',
    description: 'Uma pausa entre ideias',
    icon: <Minus />,
    run: (e) => {
      e.chain().focus().setHorizontalRule().run()
    },
  },
]
interface SlashState {
  from: number
  to: number
  query: string
  left: number
  top: number
  index: number
}
interface Props {
  document: DraftDocument
  onChange: (content: JSONContent) => void
  onReady: (editor: Editor | null) => void
  focus: boolean
}

export function WritingEditor({ document: doc, onChange, onReady, focus }: Props) {
  const [slash, setSlash] = useState<SlashState | null>(null)
  const slashRef = useRef<SlashState | null>(null)
  const [linkOpen, setLinkOpen] = useState(false)
  const [link, setLink] = useState('')
  const [linkError, setLinkError] = useState('')
  const selection = useRef({ from: 0, to: 0 })
  const callbacks = useRef({ onChange, onReady })
  callbacks.current = { onChange, onReady }
  const mode = doc.mode
  const commands: BlockCommand[] =
    mode === 'free'
      ? freeCommands
      : [
          ...(mode === 'film' ? filmTypes : videoTypes).map((type) => ({
            ...type,
            label: type.label,
            description: type.hint,
            icon: <TextT />,
            run: (e: Editor) => {
              e.chain().focus().setNode('scriptBlock', { kind: type.id }).run()
            },
          })),
          ...freeCommands.filter((command) => command.id.startsWith('heading')),
        ]
  const commandsRef = useRef(commands)
  commandsRef.current = commands
  const setSlashState = (state: SlashState | null) => {
    slashRef.current = state
    setSlash(state)
  }
  const runSlash = (editor: Editor, command: BlockCommand) => {
    const current = slashRef.current
    if (!current) return
    editor.chain().focus().deleteRange({ from: current.from, to: current.to }).run()
    command.run(editor)
    setSlashState(null)
  }
  const editor = useEditor({
    extensions: [
      StarterKit.configure({
        heading: { levels: [1, 2, 3] },
        link: {
          openOnClick: false,
          autolink: true,
          HTMLAttributes: { target: '_blank', rel: 'noopener noreferrer' },
        },
        trailingNode: false,
      }),
      Highlight,
      TaskList,
      TaskItem.configure({
        nested: true,
        a11y: { checkboxLabel: (node) => node.textContent || 'Item da checklist' },
      }),
      ScriptBlock.configure({ mode }),
      Callout,
      Placeholder.configure({
        placeholder:
          mode === 'film'
            ? 'INT. OU EXT. — LUGAR — TEMPO'
            : mode === 'video'
              ? 'Dê voz à sua próxima ideia…'
              : 'Escreva alguma coisa ou digite / para explorar os blocos…',
        showOnlyCurrent: true,
      }),
    ],
    content: doc.content,
    shouldRerenderOnTransaction: false,
    editorProps: {
      attributes: {
        class: `writing-content mode-${mode}`,
        'aria-label': 'Conteúdo do documento',
        role: 'textbox',
        'aria-multiline': 'true',
        spellcheck: 'true',
      },
      handleKeyDown: (view, event) => {
        const current = slashRef.current
        if (!current) return false
        const filtered = commandsRef.current.filter((item) =>
          normalize(item.label).includes(normalize(current.query)),
        )
        if (event.key === 'Escape') {
          setSlashState(null)
          return true
        }
        if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
          event.preventDefault()
          setSlashState({
            ...current,
            index: filtered.length
              ? (current.index + (event.key === 'ArrowDown' ? 1 : -1) + filtered.length) %
                filtered.length
              : 0,
          })
          return true
        }
        if (event.key === 'Enter' && filtered.length) {
          event.preventDefault()
          const command = filtered[current.index] ?? filtered[0]
          view.dispatch(view.state.tr.delete(current.from, current.to))
          // Commands execute after the deletion, within this same input event.
          if (editor) command.run(editor)
          setSlashState(null)
          return true
        }
        return false
      },
    },
    onUpdate: ({ editor: current }) => callbacks.current.onChange(current.getJSON()),
    onTransaction: ({ editor: current }) => {
      const { $from, empty } = current.state.selection
      const before = $from.parent.textBetween(0, $from.parentOffset, '\n')
      const match = empty && !current.isActive('codeBlock') && before.match(/^\/([^\s/]*)$/)
      if (!match) {
        if (slashRef.current) setSlashState(null)
        return
      }
      const coords = current.view.coordsAtPos($from.pos)
      setSlashState({
        from: $from.start(),
        to: $from.pos,
        query: match[1],
        left: Math.max(12, Math.min(coords.left, window.innerWidth - 302)),
        top: Math.max(70, Math.min(coords.bottom + 10, window.innerHeight - 330)),
        index: slashRef.current?.query === match[1] ? slashRef.current.index : 0,
      })
    },
  })
  useEffect(() => {
    callbacks.current.onReady(editor)
    return () => callbacks.current.onReady(null)
  }, [editor])
  useEffect(() => {
    if (!editor || editor.isDestroyed || JSON.stringify(editor.getJSON()) === JSON.stringify(doc.content)) return
    const position = editor.state.selection.from
    editor
      .chain()
      .setContent(doc.content, { emitUpdate: false })
      .command(({ tr }) => {
        tr.setMeta('addToHistory', false)
        return true
      })
      .setTextSelection(Math.min(position, Math.max(0, editor.schema.nodeFromJSON(doc.content).content.size)))
      .run()
  }, [editor, doc.content])
  useEffect(() => {
    if (!slash) return
    const close = () => setSlashState(null)
    window.addEventListener('resize', close)
    window.addEventListener('scroll', close, true)
    return () => {
      window.removeEventListener('resize', close)
      window.removeEventListener('scroll', close, true)
    }
  }, [Boolean(slash)])
  const state = useEditorState({
    editor,
    selector: ({ editor: e }) =>
      e
        ? {
            bold: e.isActive('bold'),
            italic: e.isActive('italic'),
            highlight: e.isActive('highlight'),
            link: e.isActive('link'),
            undo: e.can().undo(),
            redo: e.can().redo(),
            kind: e.getAttributes('scriptBlock').kind,
            block: e.isActive('heading')
              ? `Título ${e.getAttributes('heading').level}`
              : (freeCommands.find((c) => c.id !== 'paragraph' && e.isActive(c.id))?.label ??
                'Texto'),
          }
        : null,
  })
  const openLink = useCallback(() => {
    if (!editor) return
    selection.current = { from: editor.state.selection.from, to: editor.state.selection.to }
    setLink(editor.getAttributes('link').href ?? '')
    setLinkError('')
    setLinkOpen(true)
  }, [editor])
  if (!editor || !state)
    return (
      <div className="editor-loading" role="status">
        Preparando sua página…
      </div>
    )
  const filtered = slash
    ? commands.filter((item) => normalize(item.label).includes(normalize(slash.query)))
    : []
  const blockLabel =
    mode !== 'free' && editor.isActive('scriptBlock')
      ? commands.find((c) => c.id === state.kind)?.label
      : state.block
  const applyLink = () => {
    // A link dialog is a distinct edit, even when it is completed quickly.
    editor.view.dispatch(closeHistory(editor.state.tr))
    const value = link.trim()
    if (!value) {
      editor
        .chain()
        .focus()
        .setTextSelection(selection.current)
        .extendMarkRange('link')
        .unsetLink()
        .run()
      setLinkOpen(false)
      return
    }
    const url = /^[a-z][a-z\d+.-]*:/i.test(value) ? value : `https://${value}`
    if (!/^(https?:\/\/|mailto:)/i.test(url)) {
      setLinkError('Use um link https://, http:// ou mailto:.')
      return
    }
    try {
      new URL(url)
    } catch {
      setLinkError('Digite um endereço válido.')
      return
    }
    let chain = editor.chain().focus().setTextSelection(selection.current)
    if (selection.current.from === selection.current.to && !state.link)
      chain = chain.insertContent({
        type: 'text',
        text: value,
        marks: [{ type: 'link', attrs: { href: url } }],
      })
    else chain = chain.extendMarkRange('link').setLink({ href: url })
    chain.run()
    setLinkOpen(false)
  }
  return (
    <>
      {!focus && (
        <div className="format-toolbar" role="toolbar" aria-label="Formatação do texto">
          <Menu
            align="start"
            trigger={
              <button className="block-select" title="Tipo de bloco">
                {blockLabel}
                <CaretDown size={12} />
              </button>
            }
          >
            {commands.map((command) => (
              <MenuItem key={command.id} onSelect={() => command.run(editor)}>
                {command.icon}
                <span>{command.label}</span>
                {blockLabel === command.label && <Check size={14} />}
              </MenuItem>
            ))}
          </Menu>
          <span className="toolbar-divider" />
          <IconButton
            label="Negrito (Ctrl+B)"
            aria-pressed={state.bold}
            onClick={() => editor.chain().focus().toggleBold().run()}
          >
            <TextB size={17} />
          </IconButton>
          <IconButton
            label="Itálico (Ctrl+I)"
            aria-pressed={state.italic}
            onClick={() => editor.chain().focus().toggleItalic().run()}
          >
            <TextItalic size={17} />
          </IconButton>
          <IconButton
            label="Destacar texto"
            aria-pressed={state.highlight}
            onClick={() => editor.chain().focus().toggleHighlight().run()}
          >
            <Highlighter size={18} />
          </IconButton>
          <IconButton label="Inserir link" aria-pressed={state.link} onClick={openLink}>
            <LinkSimple size={18} />
          </IconButton>
          <span className="toolbar-divider" />
          <IconButton
            label="Desfazer (Ctrl+Z)"
            disabled={!state.undo}
            onClick={() => editor.chain().focus().undo().run()}
          >
            <ArrowCounterClockwise size={17} />
          </IconButton>
          <IconButton
            label="Refazer (Ctrl+Shift+Z)"
            disabled={!state.redo}
            onClick={() => editor.chain().focus().redo().run()}
          >
            <ArrowClockwise size={17} />
          </IconButton>
          <Menu
            trigger={
              <button className="block-move">
                <DotsSixVertical size={18} />
                <CaretDown size={10} />
                <span className="sr-only">Organizar bloco</span>
              </button>
            }
          >
            <MenuItem onSelect={() => moveBlock(editor, -1)}>
              <ArrowUp />
              Mover bloco para cima
            </MenuItem>
            <MenuItem onSelect={() => moveBlock(editor, 1)}>
              <ArrowDown />
              Mover bloco para baixo
            </MenuItem>
            <MenuSeparator />
            <MenuItem
              onSelect={() =>
                editor
                  .chain()
                  .focus()
                  .insertContentAt(editor.state.doc.content.size, {
                    type: mode === 'free' ? 'paragraph' : 'scriptBlock',
                    attrs:
                      mode === 'free' ? {} : { kind: mode === 'film' ? 'action' : 'narration' },
                  })
                  .focus('end')
                  .run()
              }
            >
              <Plus />
              Adicionar bloco ao final
            </MenuItem>
          </Menu>
        </div>
      )}
      <div className="editor-body">
        <DragHandle editor={editor} className="drag-handle-wrapper">
          <div className="drag-handle" title="Arraste para mover o bloco">
            <DotsSixVertical size={20} />
          </div>
        </DragHandle>
        <EditorContent editor={editor} />
        <button
          className="append-block"
          onClick={() =>
            editor
              .chain()
              .focus()
              .insertContentAt(editor.state.doc.content.size, {
                type: mode === 'free' ? 'paragraph' : 'scriptBlock',
                attrs: mode === 'free' ? {} : { kind: mode === 'film' ? 'action' : 'narration' },
              })
              .focus('end')
              .run()
          }
        >
          <Plus size={15} />
          <span>Continuar escrevendo</span>
        </button>
      </div>
      {slash && (
        <div
          className="slash-menu"
          role="listbox"
          aria-label="Inserir bloco"
          style={{ top: slash.top, left: slash.left }}
        >
          <div className="menu-eyebrow">ADICIONAR UM BLOCO</div>
          {filtered.length ? (
            filtered.map((command, index) => (
              <button
                key={command.id}
                role="option"
                aria-selected={index === slash.index}
                className={index === slash.index ? 'selected' : ''}
                onMouseDown={(event) => event.preventDefault()}
                onClick={() => runSlash(editor, command)}
              >
                {command.icon}
                <span>
                  <strong>{command.label}</strong>
                  <small>{command.description}</small>
                </span>
                {index === slash.index && <kbd>↵</kbd>}
              </button>
            ))
          ) : (
            <p className="empty-small">Nenhum bloco encontrado.</p>
          )}
        </div>
      )}
      <Modal
        open={linkOpen}
        onOpenChange={setLinkOpen}
        title="Inserir link"
        description="Conecte suas palavras a uma referência."
      >
        <form
          onSubmit={(event) => {
            event.preventDefault()
            applyLink()
          }}
        >
          <label className="field-label" htmlFor="link-url">
            Endereço
          </label>
          <input
            id="link-url"
            className="text-input"
            value={link}
            onChange={(event) => setLink(event.target.value)}
            placeholder="https://"
            autoFocus
          />
          {linkError && (
            <p className="error-text" role="alert">
              {linkError}
            </p>
          )}
          <div className="modal-actions">
            {state.link && (
              <button
                type="button"
                className="text-button danger"
                onClick={() => {
                  editor
                    .chain()
                    .focus()
                    .setTextSelection(selection.current)
                    .extendMarkRange('link')
                    .unsetLink()
                    .run()
                  setLinkOpen(false)
                }}
              >
                Remover link
              </button>
            )}
            <button className="primary-button" type="submit">
              Salvar link
            </button>
          </div>
        </form>
      </Modal>
    </>
  )
}
