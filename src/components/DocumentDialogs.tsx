import { useEffect, useState } from 'react'
import { ArrowRight } from '@phosphor-icons/react/dist/csr/ArrowRight'
import { Check } from '@phosphor-icons/react/dist/csr/Check'
import { MagnifyingGlass } from '@phosphor-icons/react/dist/csr/MagnifyingGlass'
import { FilePlus } from '@phosphor-icons/react/dist/csr/FilePlus'
import { ArrowUpRight } from '@phosphor-icons/react/dist/csr/ArrowUpRight'
import { Modal, ModeIcon } from './ui'
import { modes, normalize, plainText, type DraftDocument, type Mode } from '../types'

export function NewDocumentDialog({
  open,
  onOpenChange,
  onCreate,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  onCreate: (title: string, mode: Mode) => void
}) {
  const [title, setTitle] = useState('')
  const [mode, setMode] = useState<Mode>('free')
  useEffect(() => {
    if (open) {
      setTitle('')
      setMode('free')
    }
  }, [open])
  return (
    <Modal
      open={open}
      onOpenChange={onOpenChange}
      title="O que vamos escrever?"
      description="Toda ideia merece um lugar para começar."
    >
      <form
        onSubmit={(event) => {
          event.preventDefault()
          onCreate(title, mode)
          onOpenChange(false)
        }}
      >
        <label className="field-label" htmlFor="new-title">
          NOME DO DOCUMENTO
        </label>
        <input
          className="text-input"
          id="new-title"
          value={title}
          onChange={(event) => setTitle(event.target.value)}
          placeholder="Dê um nome à sua próxima ideia"
          maxLength={250}
          autoFocus
        />
        <fieldset className="mode-options">
          <legend className="field-label">ESCOLHA UM FORMATO</legend>
          {(Object.keys(modes) as Mode[]).map((value) => (
            <label key={value} className={`mode-option ${value === mode ? 'selected' : ''}`}>
              <input
                type="radio"
                name="document-mode"
                value={value}
                checked={value === mode}
                onChange={() => setMode(value)}
              />
              <span className="mode-option-icon">
                <ModeIcon mode={value} size={24} />
              </span>
              <span>
                <strong>{modes[value].label}</strong>
                <small>{modes[value].description}</small>
              </span>
              <span className="radio-check">
                {value === mode && <Check size={12} weight="bold" />}
              </span>
            </label>
          ))}
        </fieldset>
        <div className="modal-actions">
          <span className="modal-footnote">A primeira versão é só o começo.</span>
          <button className="primary-button" type="submit">
            Criar documento
            <ArrowRight size={16} />
          </button>
        </div>
      </form>
    </Modal>
  )
}
export function SearchDialog({
  open,
  onOpenChange,
  documents,
  onSelect,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  documents: DraftDocument[]
  onSelect: (id: string) => void
}) {
  const [query, setQuery] = useState('')
  const [index, setIndex] = useState(0)
  useEffect(() => {
    if (open) {
      setQuery('')
      setIndex(0)
    }
  }, [open])
  const results = [...documents]
    .sort((a, b) => b.updatedAt - a.updatedAt)
    .filter((doc) => normalize(`${doc.title} ${plainText(doc.content)}`).includes(normalize(query)))
  const choose = (id: string) => {
    onSelect(id)
    onOpenChange(false)
  }
  return (
    <Modal
      open={open}
      onOpenChange={onOpenChange}
      title="Encontre suas palavras"
      className="search-modal"
    >
      <div className="search-input-wrap">
        <MagnifyingGlass size={21} />
        <input
          aria-label="Buscar em títulos e conteúdo"
          placeholder="Buscar em títulos e conteúdo…"
          value={query}
          autoFocus
          onChange={(event) => {
            setQuery(event.target.value)
            setIndex(0)
          }}
          onKeyDown={(event) => {
            if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
              event.preventDefault()
              setIndex(
                results.length
                  ? (index + (event.key === 'ArrowDown' ? 1 : -1) + results.length) % results.length
                  : 0,
              )
            }
            if (event.key === 'Enter' && results[index]) choose(results[index].id)
          }}
        />
      </div>
      <div className="search-results">
        <div className="menu-eyebrow">
          {query
            ? `${results.length} DOCUMENTO${results.length !== 1 ? 'S' : ''} ENCONTRADO${results.length !== 1 ? 'S' : ''}`
            : 'ABERTOS ÀS POSSIBILIDADES'}
        </div>
        {results.map((doc, i) => (
          <button
            key={doc.id}
            className={`search-result ${i === index ? 'selected' : ''}`}
            onClick={() => choose(doc.id)}
          >
            <span className={`result-icon ${doc.mode}`}>
              <ModeIcon mode={doc.mode} size={22} />
            </span>
            <span>
              <strong>{doc.title}</strong>
              <small>
                {modes[doc.mode].label} · {plainText(doc.content).slice(0, 75)}
              </small>
            </span>
            <ArrowUpRight size={17} />
          </button>
        ))}
        {!results.length && (
          <div className="empty-search">
            <FilePlus size={32} />
            <strong>Nenhuma palavra por aqui.</strong>
            <p>Tente outro título ou um trecho do seu texto.</p>
          </div>
        )}
      </div>
      <div className="search-footer">
        <span>
          <kbd>↑</kbd>
          <kbd>↓</kbd> navegar
        </span>
        <span>
          <kbd>↵</kbd> abrir
        </span>
        <span>
          <kbd>esc</kbd> fechar
        </span>
      </div>
    </Modal>
  )
}
