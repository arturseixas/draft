import { MagnifyingGlass } from '@phosphor-icons/react/dist/csr/MagnifyingGlass'
import { Plus } from '@phosphor-icons/react/dist/csr/Plus'
import { Star } from '@phosphor-icons/react/dist/csr/Star'
import { ClockCounterClockwise } from '@phosphor-icons/react/dist/csr/ClockCounterClockwise'
import { Files } from '@phosphor-icons/react/dist/csr/Files'
import { SidebarSimple } from '@phosphor-icons/react/dist/csr/SidebarSimple'
import { ArrowDown } from '@phosphor-icons/react/dist/csr/ArrowDown'
import { Sun } from '@phosphor-icons/react/dist/csr/Sun'
import { Moon } from '@phosphor-icons/react/dist/csr/Moon'
import { Keyboard } from '@phosphor-icons/react/dist/csr/Keyboard'
import { HardDrives } from '@phosphor-icons/react/dist/csr/HardDrives'
import { DotsThree } from '@phosphor-icons/react/dist/csr/DotsThree'
import { Copy } from '@phosphor-icons/react/dist/csr/Copy'
import { PencilSimple } from '@phosphor-icons/react/dist/csr/PencilSimple'
import { Trash } from '@phosphor-icons/react/dist/csr/Trash'
import { Export } from '@phosphor-icons/react/dist/csr/Export'
import { CloudArrowUp } from '@phosphor-icons/react/dist/csr/CloudArrowUp'
import type { DriveStatus } from '../hooks/useDrive'
import { modes, type DraftDocument } from '../types'
import { IconButton, Menu, MenuItem, MenuSeparator, ModeIcon } from './ui'

export type Filter = 'all' | 'favorites' | 'recent'
interface Props {
  documents: DraftDocument[]
  activeId?: string
  filter: Filter
  dark: boolean
  onFilter: (filter: Filter) => void
  onSelect: (id: string) => void
  onCreate: () => void
  onSearch: () => void
  onClose: () => void
  onTheme: () => void
  onHelp: () => void
  onBackup: () => void
  onDrive: () => void
  driveStatus: DriveStatus
  driveEmail: string
  onFavorite: (document: DraftDocument) => void
  onDuplicate: (document: DraftDocument) => void
  onDelete: (document: DraftDocument) => void
  onRename: (document: DraftDocument) => void
  onExport: (document: DraftDocument) => void
}
function DocumentRow({
  doc,
  active,
  onSelect,
  ...props
}: { doc: DraftDocument; active: boolean; onSelect: () => void } & Pick<
  Props,
  'onFavorite' | 'onDuplicate' | 'onDelete' | 'onRename' | 'onExport'
>) {
  return (
    <div className={`document-row ${active ? 'active' : ''}`}>
      <button
        className="document-link"
        onClick={onSelect}
        aria-current={active ? 'page' : undefined}
        title={`${doc.title} · ${modes[doc.mode].label}`}
      >
        <ModeIcon mode={doc.mode} size={17} />
        <span>{doc.title}</span>
      </button>
      <Menu
        trigger={
          <IconButton className="document-menu" label={`Opções de ${doc.title}`}>
            <DotsThree size={19} />
          </IconButton>
        }
      >
        <MenuItem onSelect={() => props.onFavorite(doc)}>
          <Star weight={doc.favorite ? 'fill' : 'regular'} />
          {doc.favorite ? 'Remover dos favoritos' : 'Adicionar aos favoritos'}
        </MenuItem>
        <MenuItem onSelect={() => props.onRename(doc)}>
          <PencilSimple />
          Renomear
        </MenuItem>
        <MenuItem onSelect={() => props.onDuplicate(doc)}>
          <Copy />
          Duplicar
        </MenuItem>
        <MenuItem onSelect={() => props.onExport(doc)}>
          <Export />
          Exportar Markdown
        </MenuItem>
        <MenuSeparator />
        <MenuItem danger onSelect={() => props.onDelete(doc)}>
          <Trash />
          Excluir documento
        </MenuItem>
      </Menu>
    </div>
  )
}
export function Sidebar(props: Props) {
  const { documents, activeId, filter, onFilter } = props
  const sorted = [...documents].sort((a, b) => b.updatedAt - a.updatedAt)
  const favorites = sorted.filter((doc) => doc.favorite)
  const visible = filter === 'favorites' ? favorites : sorted
  const row = (doc: DraftDocument) => (
    <DocumentRow
      key={doc.id}
      doc={doc}
      active={activeId === doc.id}
      onSelect={() => props.onSelect(doc.id)}
      onFavorite={props.onFavorite}
      onDuplicate={props.onDuplicate}
      onDelete={props.onDelete}
      onRename={props.onRename}
      onExport={props.onExport}
    />
  )
  return (
    <aside className="sidebar" aria-label="Documentos">
      <div className="brand-row">
        <a
          className="wordmark"
          href="#"
          onClick={(event) => {
            event.preventDefault()
            onFilter('all')
          }}
          aria-label="Draft, todos os documentos"
        >
          draft<span>.</span>
        </a>
        <IconButton label="Recolher navegação" onClick={props.onClose}>
          <SidebarSimple size={19} />
        </IconButton>
      </div>
      <div className="workspace-name">
        <span className="workspace-avatar">D</span>
        <span>
          Meu espaço<span className="workspace-caption">Um lugar para criar.</span>
        </span>
      </div>
      <button className="search-trigger" onClick={props.onSearch}>
        <MagnifyingGlass size={17} />
        <span>Buscar documentos</span>
        <kbd>Ctrl K</kbd>
      </button>
      <button className="new-document-button" onClick={props.onCreate}>
        <Plus size={18} />
        Novo documento<span>+</span>
      </button>
      <nav className="library-nav" aria-label="Biblioteca">
        <button className={filter === 'all' ? 'selected' : ''} onClick={() => onFilter('all')}>
          <Files size={18} />
          <span>Todos os documentos</span>
          <small>{documents.length}</small>
        </button>
        <button
          className={filter === 'favorites' ? 'selected' : ''}
          onClick={() => onFilter('favorites')}
        >
          <Star size={18} />
          <span>Favoritos</span>
          <small>{favorites.length}</small>
        </button>
        <button
          className={filter === 'recent' ? 'selected' : ''}
          onClick={() => onFilter('recent')}
        >
          <ClockCounterClockwise size={18} />
          <span>Recentes</span>
        </button>
      </nav>
      <div className="sidebar-scroll">
        {filter === 'all' && favorites.length > 0 && (
          <section className="document-section">
            <div className="section-label">FAVORITOS</div>
            {favorites.map(row)}
          </section>
        )}
        <section className="document-section">
          <div className="section-label">
            <span>
              {filter === 'favorites'
                ? 'SEUS FAVORITOS'
                : filter === 'recent'
                  ? 'EDITADOS RECENTEMENTE'
                  : 'DOCUMENTOS'}
            </span>
            {filter !== 'favorites' && (
              <IconButton label="Criar documento" onClick={props.onCreate}>
                <Plus size={14} />
              </IconButton>
            )}
          </div>
          {visible.length ? (
            visible.map(row)
          ) : (
            <div className="sidebar-empty">
              {filter === 'favorites'
                ? 'As histórias favoritas ficam por aqui. Use a estrela de um documento.'
                : 'Seu próximo texto começa com uma página em branco.'}
            </div>
          )}
        </section>
      </div>
      <div className="sidebar-bottom">
        <div className="sidebar-note">
          Boas histórias
          <br />
          precisam de espaço.
        </div>
        <button className="drive-sidebar-button" onClick={props.onDrive} aria-label="Google Drive">
          <CloudArrowUp size={19} />
          <span>
            {props.driveStatus === 'synced'
              ? 'Sincronizado com o Drive'
              : props.driveStatus === 'syncing'
                ? 'Sincronizando…'
                : props.driveStatus === 'pending'
                  ? 'Alterações pendentes'
                  : props.driveStatus === 'error'
                    ? 'Revisar sincronização'
                    : props.driveEmail
                      ? 'Reconectar ao Google Drive'
                      : 'Conectar ao Google Drive'}
            <small>{props.driveEmail || 'Suas ideias em todos os dispositivos'}</small>
          </span>
        </button>
        <button className="local-storage-button" onClick={props.onBackup}>
          <HardDrives size={17} />
          <span>
            Salvo neste dispositivo<small>Seu espaço. Só seu.</small>
          </span>
          <ArrowDown size={13} />
        </button>
        <div className="sidebar-bottom-row">
          <span>Feito para escrever.</span>
          <div>
            <IconButton
              label={props.dark ? 'Ativar tema claro' : 'Ativar tema escuro'}
              onClick={props.onTheme}
            >
              {props.dark ? <Sun size={18} /> : <Moon size={18} />}
            </IconButton>
            <IconButton label="Atalhos e ajuda" onClick={props.onHelp}>
              <Keyboard size={19} />
            </IconButton>
          </div>
        </div>
      </div>
    </aside>
  )
}
