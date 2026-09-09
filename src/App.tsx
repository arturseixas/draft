import { Suspense, lazy, useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import type { Editor } from '@tiptap/react'
import * as Dialog from '@radix-ui/react-dialog'
import { SidebarSimple } from '@phosphor-icons/react/dist/csr/SidebarSimple'
import { CaretRight } from '@phosphor-icons/react/dist/csr/CaretRight'
import { CaretDown } from '@phosphor-icons/react/dist/csr/CaretDown'
import { Star } from '@phosphor-icons/react/dist/csr/Star'
import { CornersOut } from '@phosphor-icons/react/dist/csr/CornersOut'
import { CornersIn } from '@phosphor-icons/react/dist/csr/CornersIn'
import { Export } from '@phosphor-icons/react/dist/csr/Export'
import { DotsThree } from '@phosphor-icons/react/dist/csr/DotsThree'
import { Copy } from '@phosphor-icons/react/dist/csr/Copy'
import { PencilSimple } from '@phosphor-icons/react/dist/csr/PencilSimple'
import { Trash } from '@phosphor-icons/react/dist/csr/Trash'
import { FileText } from '@phosphor-icons/react/dist/csr/FileText'
import { FileMd } from '@phosphor-icons/react/dist/csr/FileMd'
import { Printer } from '@phosphor-icons/react/dist/csr/Printer'
import { Check } from '@phosphor-icons/react/dist/csr/Check'
import { List } from '@phosphor-icons/react/dist/csr/List'
import { X } from '@phosphor-icons/react/dist/csr/X'
import { Plus } from '@phosphor-icons/react/dist/csr/Plus'
import { ArrowRight } from '@phosphor-icons/react/dist/csr/ArrowRight'
import { DownloadSimple } from '@phosphor-icons/react/dist/csr/DownloadSimple'
import { UploadSimple } from '@phosphor-icons/react/dist/csr/UploadSimple'
import { ShieldCheck } from '@phosphor-icons/react/dist/csr/ShieldCheck'
import { WarningCircle } from '@phosphor-icons/react/dist/csr/WarningCircle'
import { Keyboard } from '@phosphor-icons/react/dist/csr/Keyboard'
import { WifiSlash } from '@phosphor-icons/react/dist/csr/WifiSlash'
import { useLibrary } from './hooks/useLibrary'
import { useDrive } from './hooks/useDrive'
import { DriveDialog } from './components/DriveDialog'
import { Sidebar, type Filter } from './components/Sidebar'
import { NewDocumentDialog, SearchDialog } from './components/DocumentDialogs'
import { IconButton, Menu, MenuItem, MenuSeparator, Modal, ModeIcon } from './components/ui'
import { convertContent, modes, plainText, wordCount, type DraftDocument, type Mode } from './types'
import { getPreference, setPreference } from './lib/storage'
import { downloadFile, exportDocument, parseBackup, printDocument } from './lib/export'

type Toast = { message: string; action?: () => void; actionLabel?: string }
const WritingEditor = lazy(() =>
  import('./components/WritingEditor').then((module) => ({ default: module.WritingEditor })),
)
const date = (value: number) =>
  new Intl.DateTimeFormat('pt-BR', { day: 'numeric', month: 'long', year: 'numeric' }).format(value)
export default function App() {
  const library = useLibrary()
  const drive = useDrive(library)
  const [driveOpen, setDriveOpen] = useState(false)
  const [activeId, setActiveId] = useState(() => getPreference('active', ''))
  const [filter, setFilter] = useState<Filter>('all')
  const [mobileOpen, setMobileOpen] = useState(false)
  const [collapsed, setCollapsed] = useState(false)
  const [newOpen, setNewOpen] = useState(false)
  const [searchOpen, setSearchOpen] = useState(false)
  const [helpOpen, setHelpOpen] = useState(false)
  const [backupOpen, setBackupOpen] = useState(false)
  const [deleteTarget, setDeleteTarget] = useState<DraftDocument | null>(null)
  const [renameTarget, setRenameTarget] = useState<DraftDocument | null>(null)
  const [renameTitle, setRenameTitle] = useState('')
  const [focus, setFocus] = useState(false)
  const [outlineOpen, setOutlineOpen] = useState(() => getPreference('outline', 'true') === 'true')
  const [outlineDialogOpen, setOutlineDialogOpen] = useState(false)
  const [dark, setDark] = useState(() => getPreference('theme', 'light') === 'dark')
  const [offline, setOffline] = useState(!navigator.onLine)
  const [toast, setToast] = useState<Toast | null>(null)
  const [editor, setEditor] = useState<Editor | null>(null)
  const [backupError, setBackupError] = useState('')
  const titleRef = useRef<HTMLTextAreaElement>(null)
  const scrollRef = useRef<HTMLDivElement>(null)
  const importRef = useRef<HTMLInputElement>(null)
  const docs = library.documents
  const active =
    docs.find((doc) => doc.id === activeId) ??
    [...docs].sort((a, b) => b.updatedAt - a.updatedAt)[0]
  useEffect(() => {
    document.documentElement.dataset.theme = dark ? 'dark' : 'light'
    setPreference('theme', dark ? 'dark' : 'light')
  }, [dark])
  useEffect(() => {
    const onOnline = () => setOffline(!navigator.onLine)
    window.addEventListener('online', onOnline)
    window.addEventListener('offline', onOnline)
    return () => {
      window.removeEventListener('online', onOnline)
      window.removeEventListener('offline', onOnline)
    }
  }, [])
  useEffect(() => {
    if (!active) return
    document.title = `${active.title || 'Sem título'} — Draft`
    setPreference('active', active.id)
  }, [active?.id, active?.title])
  useLayoutEffect(() => {
    const area = titleRef.current
    if (!area) return
    const resize = () => {
      area.style.height = 'auto'
      area.style.height = `${area.scrollHeight}px`
    }
    let width = area.clientWidth
    resize()
    const observer = new ResizeObserver(() => {
      if (area.clientWidth !== width) {
        width = area.clientWidth
        resize()
      }
    })
    observer.observe(area)
    void document.fonts.ready.then(resize)
    return () => observer.disconnect()
  }, [active?.title, active?.id, outlineOpen, collapsed])
  useEffect(() => {
    scrollRef.current?.scrollTo({ top: 0 })
  }, [active?.id])
  useEffect(() => {
    if (!toast) return
    const timeout = setTimeout(() => setToast(null), toast.action ? 10000 : 4500)
    return () => clearTimeout(timeout)
  }, [toast])
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      const mod = event.ctrlKey || event.metaKey
      if (mod && event.key.toLowerCase() === 'k') {
        event.preventDefault()
        setSearchOpen((open) => !open)
      }
      if (mod && event.altKey && event.key.toLowerCase() === 'n') {
        event.preventDefault()
        setNewOpen(true)
      }
      if (mod && event.shiftKey && event.key.toLowerCase() === 'f') {
        event.preventDefault()
        setFocus((value) => !value)
      }
      if (mod && event.key.toLowerCase() === 's') {
        event.preventDefault()
        if (library.saveState === 'error') library.retry()
        else setToast({ message: 'Sua escrita é salva automaticamente.' })
      }
      if (event.key === 'Escape') setFocus(false)
      if (mod && event.key === '/') {
        event.preventDefault()
        setHelpOpen(true)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [library.saveState, library.retry])
  const select = useCallback((id: string) => {
    setActiveId(id)
    setMobileOpen(false)
  }, [])
  const onReady = useCallback((instance: Editor | null) => setEditor(instance), [])
  const duplicate = (doc: DraftDocument) => {
    const copy = library.duplicate(doc.id)
    if (copy) {
      select(copy.id)
      setToast({ message: 'Uma nova versão, todas as possibilidades. Documento duplicado.' })
    }
  }
  const rename = (doc: DraftDocument) => {
    setRenameTitle(doc.title)
    setRenameTarget(doc)
  }
  const favorite = (doc: DraftDocument) => library.update(doc.id, { favorite: !doc.favorite })
  const changeMode = (mode: Mode) => {
    if (!active || active.mode === mode) return
    const previous = { mode: active.mode, content: active.content }
    library.update(active.id, {
      mode,
      content: convertContent(editor?.getJSON() ?? active.content, mode),
    })
    setToast({
      message: `Formato alterado para ${modes[mode].label.toLowerCase()}.`,
      actionLabel: 'Desfazer',
      action: () => library.update(active.id, previous),
    })
  }
  const exportText = (doc: DraftDocument, format: 'md' | 'txt') => {
    exportDocument(doc, format)
    setToast({ message: `Documento exportado em ${format === 'md' ? 'Markdown' : 'TXT'}.` })
  }
  const sidebarProps = {
    onDrive: () => {
      setMobileOpen(false)
      setDriveOpen(true)
    },
    driveStatus: drive.status,
    driveEmail: drive.email,
    documents: docs,
    activeId: active?.id,
    filter,
    dark,
    onFilter: setFilter,
    onSelect: select,
    onCreate: () => {
      setMobileOpen(false)
      setNewOpen(true)
    },
    onSearch: () => {
      setMobileOpen(false)
      setSearchOpen(true)
    },
    onClose: () => {
      setMobileOpen(false)
      if (window.innerWidth >= 768) setCollapsed(true)
    },
    onTheme: () => setDark((value) => !value),
    onHelp: () => {
      setMobileOpen(false)
      setHelpOpen(true)
    },
    onBackup: () => {
      setMobileOpen(false)
      setBackupError('')
      setBackupOpen(true)
    },
    onFavorite: favorite,
    onDuplicate: duplicate,
    onDelete: setDeleteTarget,
    onRename: rename,
    onExport: (doc: DraftDocument) => exportText(doc, 'md'),
  }
  const counts = active ? wordCount(active.content) : { words: 0, characters: 0 }
  const outline = (active?.content.content ?? [])
    .map((node, index) => ({ node, index }))
    .filter(
      ({ node }) =>
        node.type === 'heading' || (node.type === 'scriptBlock' && node.attrs?.kind === 'scene'),
    )
  const jumpTo = (index: number) => {
    if (!editor) return
    let position = 0
    for (let i = 0; i < index; i++) position += editor.state.doc.child(i).nodeSize
    editor.commands.focus(position + 1)
    const element = editor.view.nodeDOM(position)
    if (element instanceof HTMLElement)
      element.scrollIntoView({
        behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches
          ? 'instant'
          : 'smooth',
        block: 'center',
      })
  }
  return (
    <div className={`app ${collapsed ? 'sidebar-collapsed' : ''} ${focus ? 'focus-mode' : ''}`}>
      <a className="skip-link" href="#document-title">
        Ir para o documento
      </a>
      {!focus && (
        <div className="desktop-sidebar">
          <Sidebar {...sidebarProps} />
        </div>
      )}
      <Dialog.Root open={mobileOpen} onOpenChange={setMobileOpen}>
        <Dialog.Portal>
          <Dialog.Overlay className="mobile-overlay" />
          <Dialog.Content className="mobile-sidebar" aria-describedby={undefined}>
            <Dialog.Title className="sr-only">Seus documentos</Dialog.Title>
            <Sidebar {...sidebarProps} />
          </Dialog.Content>
        </Dialog.Portal>
      </Dialog.Root>
      <main className="main-area">
        {!focus && (
          <header className="topbar">
            <div className="breadcrumb">
              <IconButton
                className="mobile-menu-toggle"
                label="Abrir documentos"
                onClick={() => setMobileOpen(true)}
              >
                <SidebarSimple size={20} />
              </IconButton>
              {collapsed && (
                <IconButton
                  className="desktop-menu-toggle"
                  label="Expandir navegação"
                  onClick={() => setCollapsed(false)}
                >
                  <SidebarSimple size={20} />
                </IconButton>
              )}
              <span className="breadcrumb-space">Meu espaço</span>
              <CaretRight className="breadcrumb-separator" size={12} />
              {active && (
                <>
                  <ModeIcon mode={active.mode} size={15} />
                  <span className="breadcrumb-title">{active.title || 'Sem título'}</span>
                </>
              )}
            </div>
            <div className="header-actions">
              <span className={`save-indicator ${library.saveState}`} role="status">
                {library.saveState === 'error' ? (
                  <WarningCircle size={14} />
                ) : (
                  <span className="status-dot" />
                )}
                <span>
                  {library.saveState === 'saving'
                    ? 'Salvando…'
                    : library.saveState === 'error'
                      ? 'Falha ao salvar'
                      : 'Todas as alterações salvas'}
                </span>
              </span>
              {active && (
                <>
                  <span className="header-divider" />
                  <IconButton
                    label={active.favorite ? 'Remover dos favoritos' : 'Adicionar aos favoritos'}
                    className={`header-star ${active.favorite ? 'is-favorite' : ''}`}
                    aria-pressed={active.favorite}
                    onClick={() => favorite(active)}
                  >
                    <Star size={18} weight={active.favorite ? 'fill' : 'regular'} />
                  </IconButton>
                  <IconButton label="Modo foco (Ctrl+Shift+F)" onClick={() => setFocus(true)}>
                    <CornersOut size={19} />
                  </IconButton>
                  <Menu
                    trigger={
                      <button className="export-button" aria-label="Exportar" title="Exportar">
                        <Export size={17} />
                        <span>Exportar</span>
                        <CaretDown size={11} />
                      </button>
                    }
                  >
                    <div className="menu-eyebrow">LEVE SUAS PALAVRAS</div>
                    <MenuItem onSelect={() => exportText(active, 'md')}>
                      <FileMd />
                      Markdown<span className="menu-suffix">.md</span>
                    </MenuItem>
                    <MenuItem onSelect={() => exportText(active, 'txt')}>
                      <FileText />
                      Texto simples<span className="menu-suffix">.txt</span>
                    </MenuItem>
                    <MenuSeparator />
                    <MenuItem onSelect={() => setTimeout(() => printDocument(active.title), 50)}>
                      <Printer />
                      PDF / imprimir<span className="menu-suffix">A4</span>
                    </MenuItem>
                  </Menu>
                  <Menu
                    trigger={
                      <IconButton label="Opções do documento">
                        <DotsThree size={22} />
                      </IconButton>
                    }
                  >
                    <MenuItem onSelect={() => rename(active)}>
                      <PencilSimple />
                      Renomear
                    </MenuItem>
                    <MenuItem onSelect={() => duplicate(active)}>
                      <Copy />
                      Duplicar documento
                    </MenuItem>
                    <MenuItem
                      onSelect={() => {
                        if (window.innerWidth < 1280) setOutlineDialogOpen(true)
                        else {
                          setOutlineOpen((value) => !value)
                          setPreference('outline', String(!outlineOpen))
                        }
                      }}
                    >
                      <List />
                      Estrutura do documento
                    </MenuItem>
                    <MenuSeparator />
                    <MenuItem danger onSelect={() => setDeleteTarget(active)}>
                      <Trash />
                      Excluir documento
                    </MenuItem>
                    <MenuSeparator />
                    <div className="document-dates">
                      Criado em {date(active.createdAt)}
                      <br />
                      Editado em {date(active.updatedAt)}
                    </div>
                  </Menu>
                </>
              )}
            </div>
          </header>
        )}
        {focus && (
          <button className="exit-focus" onClick={() => setFocus(false)}>
            <CornersIn size={17} />
            Sair do foco<kbd>esc</kbd>
          </button>
        )}
        {library.saveState === 'error' && (
          <div className="save-error" role="alert">
            <WarningCircle size={18} />
            <span>Não foi possível salvar. Exporte uma cópia para proteger seu texto.</span>
            <button onClick={library.retry}>Tentar novamente</button>
            {active && <button onClick={() => exportText(active, 'md')}>Exportar</button>}
          </div>
        )}
        <div className="document-scroll" ref={scrollRef}>
          {library.loading ? (
            <div className="app-empty" role="status">
              <span className="loading-mark">d.</span>
              <p>Abrindo seu espaço…</p>
            </div>
          ) : library.loadError ? (
            <div className="app-empty">
              <WarningCircle size={34} />
              <h1>Vamos recuperar seu espaço.</h1>
              <p>{library.loadError}</p>
              <button className="primary-button" onClick={() => window.location.reload()}>
                Tentar novamente
              </button>
            </div>
          ) : active ? (
            <div className={`document-layout ${outlineOpen ? 'with-outline' : ''}`}>
              <article className={`paper paper-${active.mode}`}>
                <div className="document-meta">
                  <Menu
                    align="start"
                    trigger={
                      <button className={`mode-label ${active.mode}`}>
                        <ModeIcon mode={active.mode} size={17} />
                        {modes[active.mode].label}
                        <CaretDown size={11} />
                      </button>
                    }
                  >
                    <div className="menu-eyebrow">FORMATO DO DOCUMENTO</div>
                    {(Object.keys(modes) as Mode[]).map((mode) => (
                      <MenuItem key={mode} onSelect={() => changeMode(mode)}>
                        <ModeIcon mode={mode} />
                        <span>{modes[mode].label}</span>
                        {active.mode === mode && <Check size={14} />}
                      </MenuItem>
                    ))}
                    <MenuSeparator />
                    <div className="menu-help">O texto é mantido ao trocar de formato.</div>
                  </Menu>
                  <span className="meta-separator">·</span>
                  <span className="document-date">{date(active.createdAt)}</span>
                </div>
                <textarea
                  id="document-title"
                  ref={titleRef}
                  rows={1}
                  className="document-title"
                  aria-label="Título do documento"
                  value={active.title}
                  onChange={(event) =>
                    library.update(active.id, { title: event.target.value.replace(/\n/g, '') })
                  }
                  onBlur={() => {
                    if (!active.title.trim()) library.update(active.id, { title: 'Sem título' })
                  }}
                  onKeyDown={(event) => {
                    if (event.key === 'Enter') {
                      event.preventDefault()
                      editor?.commands.focus('start')
                    }
                  }}
                  maxLength={250}
                  spellCheck={false}
                />
                <div className="print-title">{active.title}</div>
                <Suspense
                  fallback={
                    <div className="editor-loading" role="status">
                      Preparando sua página…
                    </div>
                  }
                >
                  <WritingEditor
                    key={`${active.id}:${active.mode}`}
                    document={active}
                    onChange={(content) => library.update(active.id, { content })}
                    onReady={onReady}
                    focus={focus}
                  />
                </Suspense>
              </article>
              {!focus && outlineOpen && (
                <aside className="document-outline" aria-label="Estrutura do documento">
                  <div className="outline-heading">
                    <span>NESTA PÁGINA</span>
                    <IconButton
                      label="Ocultar estrutura"
                      onClick={() => {
                        setOutlineOpen(false)
                        setPreference('outline', 'false')
                      }}
                    >
                      <List size={16} />
                    </IconButton>
                  </div>
                  {outline.length ? (
                    outline.map(({ node, index }, i) => (
                      <button
                        key={index}
                        className={i === 0 ? 'outline-first' : ''}
                        onClick={() => jumpTo(index)}
                      >
                        {plainText(node) || 'Sem título'}
                      </button>
                    ))
                  ) : (
                    <p>Seus títulos e cenas aparecerão aqui.</p>
                  )}
                  <div className="outline-tip">
                    <Keyboard size={15} />
                    <p>
                      {active.mode === 'free' ? (
                        <>
                          Uma ideia por vez.
                          <br />
                          <kbd>/</kbd> para adicionar blocos.
                        </>
                      ) : (
                        <>
                          <kbd>Tab</kbd> muda o bloco.
                          <br />
                          <kbd>Enter</kbd> segue a história.
                        </>
                      )}
                    </p>
                  </div>
                </aside>
              )}
            </div>
          ) : (
            <div className="app-empty">
              <span className="empty-document-icon">
                <PencilSimple size={34} />
              </span>
              <span className="eyebrow">ESPAÇO PARA O QUE VEM</span>
              <h1>A próxima história é sua.</h1>
              <p>
                Um roteiro, uma ideia, uma primeira frase.
                <br />
                Comece com uma página em branco.
              </p>
              <button className="primary-button" onClick={() => setNewOpen(true)}>
                Criar meu primeiro documento
                <ArrowRight size={17} />
              </button>
            </div>
          )}
        </div>
        {!focus && (
          <footer className="statusbar">
            <div>
              <span>{counts.words.toLocaleString('pt-BR')} palavras</span>
              <span className="statusbar-dot">·</span>
              <span>{counts.characters.toLocaleString('pt-BR')} caracteres</span>
              <span className="reading-time">
                {Math.max(1, Math.ceil(counts.words / 200))} min de leitura
              </span>
            </div>
            <div>
              {offline && (
                <span className="offline-label">
                  <WifiSlash size={13} />
                  Offline
                </span>
              )}
              <span className="footer-hint">
                {active?.mode === 'film'
                  ? 'Tab muda o bloco'
                  : 'Sua próxima ideia está a uma palavra de distância.'}
              </span>
              <button onClick={() => setHelpOpen(true)} title="Atalhos e ajuda">
                <Keyboard size={15} />
                <span>Atalhos</span>
              </button>
            </div>
          </footer>
        )}
      </main>
      <NewDocumentDialog
        open={newOpen}
        onOpenChange={setNewOpen}
        onCreate={(title, mode) => {
          const doc = library.create(title, mode)
          select(doc.id)
          setFilter('all')
        }}
      />
      <DriveDialog open={driveOpen} onOpenChange={setDriveOpen} drive={drive} />
      <SearchDialog
        open={searchOpen}
        onOpenChange={setSearchOpen}
        documents={docs}
        onSelect={select}
      />
      <Modal
        open={outlineDialogOpen}
        onOpenChange={setOutlineDialogOpen}
        title="Nesta página"
        description="Encontre o ponto da história que quer continuar."
      >
        <div className="outline-dialog-list">
          {outline.length ? (
            outline.map(({ node, index }) => (
              <button
                key={index}
                onClick={() => {
                  setOutlineDialogOpen(false)
                  setTimeout(() => jumpTo(index), 50)
                }}
              >
                <span>{plainText(node) || 'Sem título'}</span>
                <ArrowRight size={16} />
              </button>
            ))
          ) : (
            <p>
              Adicione títulos ou cabeçalhos de cena ao texto para criar a estrutura do documento.
            </p>
          )}
        </div>
      </Modal>
      <Modal
        open={Boolean(renameTarget)}
        onOpenChange={(open) => {
          if (!open) setRenameTarget(null)
        }}
        title="Um novo nome"
        description="Encontre as palavras que combinam com seu documento."
      >
        <form
          onSubmit={(event) => {
            event.preventDefault()
            if (renameTarget)
              library.update(renameTarget.id, { title: renameTitle.trim() || 'Sem título' })
            setRenameTarget(null)
          }}
        >
          <label className="field-label" htmlFor="rename-title">
            Nome do documento
          </label>
          <input
            id="rename-title"
            autoFocus
            className="text-input"
            value={renameTitle}
            maxLength={250}
            onChange={(event) => setRenameTitle(event.target.value)}
          />
          <div className="modal-actions">
            <button
              className="secondary-button"
              type="button"
              onClick={() => setRenameTarget(null)}
            >
              Cancelar
            </button>
            <button type="submit" className="primary-button">
              Salvar nome
            </button>
          </div>
        </form>
      </Modal>
      <Modal
        open={Boolean(deleteTarget)}
        onOpenChange={(open) => {
          if (!open) setDeleteTarget(null)
        }}
        title="Excluir este documento?"
        description={`“${deleteTarget?.title ?? ''}” será removido deste dispositivo. Você poderá desfazer logo após a exclusão.`}
      >
        <div className="modal-actions">
          <button className="secondary-button" onClick={() => setDeleteTarget(null)}>
            Manter documento
          </button>
          <button
            className="danger-button"
            onClick={() => {
              if (!deleteTarget) return
              const removed = deleteTarget
              library.remove(removed.id)
              setDeleteTarget(null)
              setToast({
                message: 'Documento excluído.',
                actionLabel: 'Desfazer',
                action: () => {
                  library.restore(removed)
                  select(removed.id)
                },
              })
            }}
          >
            <Trash size={17} />
            Excluir documento
          </button>
        </div>
      </Modal>
      <Modal
        open={helpOpen}
        onOpenChange={setHelpOpen}
        title="Menos cliques. Mais palavras."
        description="Pequenos atalhos para não perder o fio da ideia."
        className="help-modal"
      >
        <div className="shortcut-list">
          {[
            ['Buscar documentos', 'Ctrl / ⌘ K'],
            ['Novo documento', 'Ctrl / ⌘ Alt N'],
            ['Entrar / sair do modo foco', 'Ctrl / ⌘ Shift F'],
            ['Sair do modo foco', 'Esc'],
            ['Negrito / itálico', 'Ctrl / ⌘ B / I'],
            ['Desfazer', 'Ctrl / ⌘ Z'],
            ['Refazer', 'Ctrl / ⌘ Shift Z'],
            ['Adicionar bloco', '/ em uma nova linha'],
            ['Mudar bloco de roteiro', 'Tab / Shift Tab'],
            ['Escolher bloco de roteiro', 'Alt 1 a 7'],
            ['Mover bloco para cima / baixo', 'Ctrl / ⌘ Alt ↑ / ↓'],
            ['Abrir esta ajuda', 'Ctrl / ⌘ /'],
          ].map(([label, key]) => (
            <div key={label}>
              <span>{label}</span>
              <kbd>{key}</kbd>
            </div>
          ))}
        </div>
        <div className="help-note">
          <strong>Escreva do seu jeito.</strong>
          <p>
            Use # para títulos, - para listas, [ ] para checklists e &gt; para citações. No desktop,
            arraste a alça à esquerda dos blocos para organizá-los. No celular, use o menu de
            organização da barra de ferramentas.
          </p>
          <p>
            No roteiro de filme, Enter passa de personagem para diálogo e de cena para ação.
            Shift+Enter cria uma quebra dentro do mesmo bloco.
          </p>
        </div>
      </Modal>
      <Modal
        open={backupOpen}
        onOpenChange={setBackupOpen}
        title="Seu texto vai com você."
        description="Os documentos ficam neste navegador, sem conta e sem servidor. Faça um backup para levá-los a outro dispositivo."
      >
        <div className="backup-summary">
          <ShieldCheck size={27} />
          <div>
            <strong>{docs.length} documentos no seu espaço</strong>
            <span>Uma cópia preserva textos, formatos e favoritos.</span>
          </div>
        </div>
        <div className="backup-actions">
          <button
            className="primary-button"
            onClick={() => {
              downloadFile(
                `Draft-backup-${new Date().toISOString().slice(0, 10)}.json`,
                JSON.stringify(
                  { version: 1, exportedAt: new Date().toISOString(), documents: docs },
                  null,
                  2,
                ),
                'application/json',
              )
              setToast({ message: 'Backup completo exportado.' })
            }}
          >
            <DownloadSimple size={18} />
            Exportar backup
          </button>
          <button className="secondary-button" onClick={() => importRef.current?.click()}>
            <UploadSimple size={18} />
            Importar backup
          </button>
        </div>
        <input
          ref={importRef}
          type="file"
          accept=".json,application/json"
          className="sr-only"
          aria-label="Arquivo de backup"
          onChange={async (event) => {
            const file = event.target.files?.[0]
            if (!file) return
            setBackupError('')
            try {
              if (file.size > 20 * 1024 * 1024) throw new Error('Escolha um backup de até 20 MB.')
              const imported = parseBackup(await file.text())
              imported.forEach((doc) => library.restore(doc))
              if (imported[0]) select(imported[0].id)
              setBackupOpen(false)
              setToast({
                message: `${imported.length} documentos importados. Seus documentos anteriores foram mantidos.`,
              })
            } catch (error) {
              setBackupError(
                error instanceof Error ? error.message : 'Não foi possível importar este arquivo.',
              )
            }
            event.target.value = ''
          }}
        />
        {backupError && (
          <p className="error-text" role="alert">
            {backupError}
          </p>
        )}
        <p className="backup-note">
          Limpar os dados do navegador remove os documentos locais. O backup é a sua cópia
          independente. Depois do primeiro acesso à versão de produção, o Draft também abre offline.
        </p>
      </Modal>
      {toast && (
        <div className="toast" role="status">
          <Check size={17} />
          <span>{toast.message}</span>
          {toast.action && (
            <button
              onClick={() => {
                toast.action?.()
                setToast(null)
              }}
            >
              {toast.actionLabel}
            </button>
          )}
          <IconButton label="Fechar mensagem" onClick={() => setToast(null)}>
            <X size={15} />
          </IconButton>
        </div>
      )}
      <button
        className="mobile-new"
        aria-label="Novo documento"
        onClick={() => setNewOpen(true)}
        hidden={focus || Boolean(active)}
      >
        <Plus size={21} />
      </button>
    </div>
  )
}
