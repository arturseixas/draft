import type { ComponentProps, ReactNode } from 'react'
import * as Dialog from '@radix-ui/react-dialog'
import * as Dropdown from '@radix-ui/react-dropdown-menu'
import { X } from '@phosphor-icons/react/dist/csr/X'
import { FileText } from '@phosphor-icons/react/dist/csr/FileText'
import { FilmSlate } from '@phosphor-icons/react/dist/csr/FilmSlate'
import { VideoCamera } from '@phosphor-icons/react/dist/csr/VideoCamera'
import type { Mode } from '../types'

export function ModeIcon({ mode, size = 18 }: { mode: Mode; size?: number }) {
  const Icon = mode === 'film' ? FilmSlate : mode === 'video' ? VideoCamera : FileText
  return <Icon size={size} aria-hidden="true" />
}
export function IconButton({
  label,
  children,
  className = '',
  ...props
}: ComponentProps<'button'> & { label: string }) {
  return (
    <button
      type="button"
      className={`icon-button ${className}`}
      aria-label={label}
      title={label}
      {...props}
    >
      {children}
    </button>
  )
}
export function Modal({
  open,
  onOpenChange,
  title,
  description,
  children,
  className = '',
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: string
  description?: string
  children: ReactNode
  className?: string
}) {
  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="modal-overlay" />
        <Dialog.Content className={`modal ${className}`}>
          <div className="modal-heading">
            <Dialog.Title>{title}</Dialog.Title>
            <Dialog.Close asChild>
              <IconButton label="Fechar">
                <X size={20} />
              </IconButton>
            </Dialog.Close>
          </div>
          <Dialog.Description className={description ? 'modal-description' : 'sr-only'}>
            {description ?? title}
          </Dialog.Description>
          {children}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
export function Menu({
  trigger,
  children,
  align = 'end',
  label,
}: {
  trigger: ReactNode
  children: ReactNode
  align?: 'start' | 'center' | 'end'
  label?: string
}) {
  return (
    <Dropdown.Root>
      <Dropdown.Trigger asChild>{trigger}</Dropdown.Trigger>
      <Dropdown.Portal>
        <Dropdown.Content className="dropdown" align={align} sideOffset={7} aria-label={label}>
          {children}
        </Dropdown.Content>
      </Dropdown.Portal>
    </Dropdown.Root>
  )
}
export function MenuItem({
  children,
  onSelect,
  danger,
  disabled,
}: {
  children: ReactNode
  onSelect: () => void
  danger?: boolean
  disabled?: boolean
}) {
  return (
    <Dropdown.Item
      className={`menu-item ${danger ? 'danger' : ''}`}
      onSelect={onSelect}
      disabled={disabled}
    >
      {children}
    </Dropdown.Item>
  )
}
export function MenuSeparator() {
  return <Dropdown.Separator className="menu-separator" />
}
