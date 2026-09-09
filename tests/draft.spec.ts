import { test, expect, type Page } from '@playwright/test'
import { readFile } from 'node:fs/promises'

async function openNavigation(page: Page) {
  const button = page.getByRole('button', { name: 'Abrir documentos', exact: true })
  if (await button.isVisible()) await button.click()
}
async function createDocument(page: Page, title: string, mode = 'Escrita livre') {
  await page.keyboard.press('Control+Alt+n')
  const modal = page.getByRole('dialog', { name: 'O que vamos escrever?' })
  await expect(modal).toBeVisible()
  await modal.getByLabel('NOME DO DOCUMENTO').fill(title)
  await modal.getByRole('radio', { name: new RegExp(mode) }).check()
  await modal.getByRole('button', { name: 'Criar documento', exact: true }).click()
  await expect(page.getByRole('textbox', { name: 'Título do documento' })).toHaveValue(title)
}
async function docMenu(page: Page, item: string) {
  await page.getByRole('button', { name: 'Opções do documento', exact: true }).click()
  await page.getByRole('menuitem', { name: item, exact: true }).click()
}
test.beforeEach(async ({ page }) => {
  await page.goto('/')
  await expect(page.getByRole('textbox', { name: 'Título do documento' })).toHaveValue(
    'O lugar onde as ideias começam',
  )
})

test('examples, layout, focus, theme and persisted preference', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  await expect(page.getByRole('textbox', { name: 'Conteúdo do documento' })).toContainText(
    'Antes de tudo, escreva.',
  )
  const toolbar = await page.getByRole('toolbar').boundingBox()
  const title = await page.getByRole('textbox', { name: 'Título do documento' }).boundingBox()
  expect(toolbar!.y + toolbar!.height).toBeLessThan(title!.y)
  expect(
    await page
      .getByRole('textbox', { name: 'Título do documento' })
      .evaluate((element) => element.scrollHeight <= element.clientHeight + 1),
  ).toBe(true)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.keyboard.press('Control+Shift+f')
  await expect(page.getByRole('button', { name: /Sair do foco/ })).toBeVisible()
  await expect(page.getByRole('toolbar')).toHaveCount(0)
  await page.keyboard.press('Escape')
  await expect(page.getByRole('toolbar')).toBeVisible()
  await openNavigation(page)
  await page.getByRole('button', { name: 'Ativar tema escuro', exact: true }).click()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
  await page.reload()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
  expect(errors).toEqual([])
})

test('create, type, reload and reopen preserves latest content', async ({ page }) => {
  await createDocument(page, 'Caderno de marés')
  const content = page.getByRole('textbox', { name: 'Conteúdo do documento' })
  await content.fill('A maré trouxe uma carta. Amanhã, ela terá uma resposta.')
  await page.reload()
  await expect(page.getByRole('textbox', { name: 'Título do documento' })).toHaveValue(
    'Caderno de marés',
  )
  await expect(content).toHaveText('A maré trouxe uma carta. Amanhã, ela terá uma resposta.')
  await page.keyboard.press('Control+k')
  await page.getByRole('textbox', { name: 'Buscar em títulos e conteúdo' }).fill('estacao')
  await page.getByRole('button', { name: /Entre uma estação e outra/ }).click()
  await expect(page.getByRole('textbox', { name: 'Título do documento' })).toHaveValue(
    'Entre uma estação e outra',
  )
  await page.keyboard.press('Control+k')
  await page.getByRole('textbox', { name: 'Buscar em títulos e conteúdo' }).fill('resposta')
  await expect(page.getByRole('button', { name: /Caderno de marés/ })).toBeVisible()
  await page.keyboard.press('Enter')
  await expect(content).toHaveText('A maré trouxe uma carta. Amanhã, ela terá uma resposta.')
})

test('screenplay Enter, Tab and explicit block types', async ({ page }) => {
  await createDocument(page, 'O último farol', 'Roteiro de filme')
  const content = page.getByRole('textbox', { name: 'Conteúdo do documento' })
  await content.fill('INT. FAROL — NOITE')
  await content.press('End')
  await content.press('Enter')
  await page.keyboard.insertText('Uma luz aparece no horizonte.')
  await expect(content.locator('[data-kind="action"]')).toHaveText('Uma luz aparece no horizonte.')
  await page.keyboard.press('Enter')
  await page.keyboard.press('Tab')
  await page.keyboard.insertText('LIA')
  await page.keyboard.press('Enter')
  await page.keyboard.insertText('Ainda estamos aqui.')
  await expect(content.locator('[data-kind="character"]')).toHaveText('LIA')
  await expect(content.locator('[data-kind="dialogue"]')).toHaveText('Ainda estamos aqui.')
  await page.reload()
  await expect(content.locator('[data-kind="dialogue"]')).toHaveText('Ainda estamos aqui.')
})

test('video blocks, format change preserves all words and undo restores format', async ({
  page,
}) => {
  await createDocument(page, 'A cidade acorda', 'Roteiro de vídeo')
  const content = page.getByRole('textbox', { name: 'Conteúdo do documento' })
  await content.fill('A cidade desperta devagar.')
  await content.press('End')
  await page.keyboard.press('Enter')
  await page.keyboard.press('Tab')
  await page.keyboard.insertText('Plano aberto da avenida.')
  await expect(content.locator('[data-kind="broll"]')).toHaveText('Plano aberto da avenida.')
  await page.getByRole('button', { name: 'Roteiro de vídeo', exact: true }).click()
  await page.getByRole('menuitem', { name: 'Escrita livre', exact: true }).click()
  await expect(content).toContainText('A cidade desperta devagar.')
  await expect(content).toContainText('Plano aberto da avenida.')
  await expect(content.locator('[data-script]')).toHaveCount(0)
  await page.getByRole('button', { name: 'Desfazer', exact: true }).click()
  await expect(content.locator('[data-kind="broll"]')).toHaveText('Plano aberto da avenida.')
})

test('slash commands, bold, checklist and block reordering', async ({ page }) => {
  await createDocument(page, 'Lista de cenas')
  const content = page.getByRole('textbox', { name: 'Conteúdo do documento' })
  await content.click()
  await page.keyboard.insertText('/título')
  await expect(page.getByRole('listbox', { name: 'Inserir bloco' })).toBeVisible()
  await page.keyboard.press('ArrowDown')
  await page.keyboard.press('Enter')
  await page.keyboard.insertText('Primeiro ato')
  await expect(content.locator('h2')).toHaveText('Primeiro ato')
  await page.keyboard.press('Enter')
  await page.keyboard.insertText('Uma chegada inesperada.')
  await page.keyboard.press('Home')
  await page.keyboard.press('Shift+End')
  await page.keyboard.press('Control+b')
  await expect(content.locator('strong')).toHaveText('Uma chegada inesperada.')
  await page.keyboard.press('End')
  await page.keyboard.press('Enter')
  await page.keyboard.insertText('/check')
  await page.keyboard.press('Enter')
  await page.keyboard.insertText('Revisar o diálogo')
  await expect(content.locator('input[type="checkbox"]')).toHaveCount(1)
  await content.locator('input[type="checkbox"]').check()
  await page.reload()
  await expect(content.locator('input[type="checkbox"]')).toBeChecked()
  await content.locator('h2').click()
  await page.getByRole('button', { name: 'Organizar bloco' }).click()
  await page.getByRole('menuitem', { name: 'Mover bloco para baixo' }).click()
  await expect(content.locator(':scope > :first-child')).toContainText('Uma chegada inesperada.')
})

test('favorites, duplicate, rename, delete and restore', async ({ page }) => {
  await createDocument(page, 'Carta para o futuro')
  await page
    .getByRole('textbox', { name: 'Conteúdo do documento' })
    .fill('Guarde uma pequena parte de hoje.')
  // The same document action remains available in the mobile sidebar.
  await openNavigation(page)
  const nav = page.locator('.sidebar:visible')
  await nav.getByRole('button', { name: 'Opções de Carta para o futuro', exact: true }).click()
  await page.getByRole('menuitem', { name: 'Adicionar aos favoritos' }).click()
  await nav.getByRole('button', { name: /Favoritos \d/ }).click()
  await expect(nav.getByRole('button', { name: 'Carta para o futuro', exact: true })).toHaveCount(1)
  await nav.getByRole('button', { name: 'Carta para o futuro', exact: true }).click()
  await docMenu(page, 'Duplicar documento')
  await expect(page.getByRole('textbox', { name: 'Título do documento' })).toHaveValue(
    'Carta para o futuro (cópia)',
  )
  await expect(page.getByRole('textbox', { name: 'Conteúdo do documento' })).toHaveText(
    'Guarde uma pequena parte de hoje.',
  )
  await docMenu(page, 'Renomear')
  await page.getByRole('textbox', { name: 'Nome do documento' }).fill('Carta para amanhã')
  await page.getByRole('button', { name: 'Salvar nome' }).click()
  await expect(page.getByRole('textbox', { name: 'Título do documento' })).toHaveValue(
    'Carta para amanhã',
  )
  await docMenu(page, 'Excluir documento')
  await page
    .getByRole('dialog')
    .getByRole('button', { name: 'Excluir documento', exact: true })
    .click()
  await expect(page.getByRole('textbox', { name: 'Título do documento' })).not.toHaveValue(
    'Carta para amanhã',
  )
  await page.getByRole('button', { name: 'Desfazer', exact: true }).click()
  await expect(page.getByRole('textbox', { name: 'Título do documento' })).toHaveValue(
    'Carta para amanhã',
  )
  await page.reload()
  await expect(page.getByRole('textbox', { name: 'Título do documento' })).toHaveValue(
    'Carta para amanhã',
  )
})

test('Markdown, text and backup exports contain real text and can be restored', async ({
  page,
}) => {
  for (const [name, extension, expectedText] of [
    ['Markdown', '.md', '## Antes de tudo, escreva.'],
    ['Texto simples', '.txt', '[x] Encontrar um lugar para as ideias'],
  ]) {
    await page.getByRole('button', { name: 'Exportar', exact: true }).click()
    const pending = page.waitForEvent('download')
    await page.getByRole('menuitem', { name: new RegExp(name) }).click()
    const download = await pending
    expect(download.suggestedFilename()).toContain(extension)
    const value = await readFile((await download.path())!, 'utf8')
    expect(value).toContain(expectedText)
    expect(value).toContain('O lugar onde as ideias começam')
  }
  await openNavigation(page)
  await page.getByRole('button', { name: 'Salvo neste dispositivo Seu espaço. Só seu.' }).click()
  const pending = page.waitForEvent('download')
  await page.getByRole('button', { name: 'Exportar backup' }).click()
  const backup = await pending
  const file = (await backup.path())!
  const parsed = JSON.parse(await readFile(file, 'utf8'))
  expect(parsed.documents).toHaveLength(4)
  await page.getByLabel('Arquivo de backup').setInputFiles({
    name: 'backup.json',
    mimeType: 'application/json',
    buffer: Buffer.from(JSON.stringify(parsed)),
  })
  await expect(
    page.getByRole('status').filter({ hasText: '4 documentos importados' }),
  ).toBeVisible()
  await openNavigation(page)
  await expect(
    page.getByRole('button', { name: 'Todos os documentos 8', exact: true }),
  ).toBeVisible()
})

test('link insertion, keyboard undo and print styling', async ({ page }) => {
  await createDocument(page, 'Referências')
  const content = page.getByRole('textbox', { name: 'Conteúdo do documento' })
  await content.fill('Uma referência importante')
  await content.press('Home')
  await page.keyboard.press('Shift+End')
  await page.getByRole('button', { name: 'Inserir link', exact: true }).click()
  await page.getByLabel('Endereço', { exact: true }).fill('https://example.com')
  await page.getByRole('button', { name: 'Salvar link' }).click()
  await expect(content.locator('a')).toHaveAttribute('href', 'https://example.com')
  await page.keyboard.press('Control+z')
  await expect(content.locator('a')).toHaveCount(0)
  await page.emulateMedia({ media: 'print' })
  await expect(page.locator('.print-title')).toBeVisible()
  await expect(page.locator('.topbar')).toBeHidden()
  await expect(page.locator('.format-toolbar')).toBeHidden()
  await expect(content).toContainText('Uma referência importante')
})

test('drag and drop moves complete blocks without losing text', async ({ page }, testInfo) => {
  test.skip(
    testInfo.project.name === 'mobile',
    'On touch screens the accessible block menu handles reordering, covered above.',
  )
  await createDocument(page, 'Ordem das ideias')
  const content = page.getByRole('textbox', { name: 'Conteúdo do documento' })
  await content.fill('Primeiro bloco')
  await content.press('End')
  await page.keyboard.press('Enter')
  await page.keyboard.insertText('Segundo bloco')
  await page.keyboard.press('Enter')
  await page.keyboard.insertText('Terceiro bloco')
  await content.locator('p').first().hover()
  const handle = page.locator('.drag-handle-wrapper')
  await expect(handle).toBeVisible()
  await handle.dragTo(content.locator('p').nth(2), { targetPosition: { x: 30, y: 20 } })
  await expect(content.locator('p').first()).toHaveText('Segundo bloco')
  await expect(content.locator('p')).toHaveCount(3)
  await expect(content).toContainText('Primeiro bloco')
  await page.reload()
  await expect(content.locator('p').first()).toHaveText('Segundo bloco')
})

test('closing and reopening a page retains the most recent edit', async ({ page, context }) => {
  await createDocument(page, 'Até amanhã')
  await page
    .getByRole('textbox', { name: 'Conteúdo do documento' })
    .fill('A última palavra também fica.')
  await page.close({ runBeforeUnload: false })
  const reopened = await context.newPage()
  await reopened.goto('/')
  await expect(reopened.getByRole('textbox', { name: 'Título do documento' })).toHaveValue(
    'Até amanhã',
  )
  await expect(reopened.getByRole('textbox', { name: 'Conteúdo do documento' })).toHaveText(
    'A última palavra também fica.',
  )
})

test('recovery journal survives an interrupted IndexedDB write', async ({ page }) => {
  await page.evaluate(async () => {
    const request = indexedDB.open('draft-writing', 1)
    const database = await new Promise<IDBDatabase>((resolve, reject) => {
      request.onsuccess = () => resolve(request.result)
      request.onerror = () => reject(request.error)
    })
    const tx = database.transaction('documents', 'readonly')
    const get = tx.objectStore('documents').get(localStorage.getItem('draft:active')!)
    const doc = await new Promise<Record<string, unknown>>((resolve, reject) => {
      get.onsuccess = () => resolve(get.result)
      get.onerror = () => reject(get.error)
    })
    doc.title = 'Texto recuperado'
    doc.content = {
      type: 'doc',
      content: [{ type: 'paragraph', content: [{ type: 'text', text: 'Nada ficou para trás.' }] }],
    }
    localStorage.setItem(`draft:recovery:${doc.id}`, JSON.stringify({ document: doc, id: doc.id }))
    database.close()
  })
  await page.reload()
  await expect(page.getByRole('textbox', { name: 'Título do documento' })).toHaveValue(
    'Texto recuperado',
  )
  await expect(page.getByRole('textbox', { name: 'Conteúdo do documento' })).toHaveText(
    'Nada ficou para trás.',
  )
  expect(
    await page.evaluate(
      () => Object.keys(localStorage).filter((key) => key.startsWith('draft:recovery:')).length,
    ),
  ).toBe(0)
})

test('empty library stays empty after reopening and can create a fresh document', async ({
  page,
}) => {
  for (let i = 0; i < 4; i++) {
    await docMenu(page, 'Excluir documento')
    await page
      .getByRole('dialog')
      .getByRole('button', { name: 'Excluir documento', exact: true })
      .click()
  }
  await expect(page.getByRole('heading', { name: 'A próxima história é sua.' })).toBeVisible()
  await page.reload()
  await expect(page.getByRole('heading', { name: 'A próxima história é sua.' })).toBeVisible()
  await page.getByRole('button', { name: 'Criar meu primeiro documento' }).click()
  await page.getByRole('dialog').getByLabel('NOME DO DOCUMENTO').fill('Recomeço')
  await page
    .getByRole('dialog')
    .getByRole('button', { name: 'Criar documento', exact: true })
    .click()
  await expect(page.getByRole('textbox', { name: 'Título do documento' })).toHaveValue('Recomeço')
})

test('title resizes on viewport change and narrow screens can open the outline', async ({
  page,
}) => {
  await page.setViewportSize({ width: 360, height: 800 })
  const title = page.getByRole('textbox', { name: 'Título do documento' })
  await expect
    .poll(() => title.evaluate((element) => element.scrollHeight <= element.clientHeight + 1))
    .toBe(true)
  await docMenu(page, 'Estrutura do documento')
  const dialog = page.getByRole('dialog', { name: 'Nesta página' })
  await expect(dialog).toBeVisible()
  await dialog.getByRole('button', { name: 'Comece por aqui' }).click()
  await expect(dialog).not.toBeVisible()
  await expect(page.getByRole('heading', { name: 'Comece por aqui' })).toBeInViewport()
})

test('failed storage shows a recovery action and retry commits the text', async ({ page }) => {
  await createDocument(page, 'Escrita protegida')
  await page.evaluate(() => {
    const original = IDBObjectStore.prototype.put
    ;(window as unknown as { restoreDraftWrites: () => void }).restoreDraftWrites = () => {
      IDBObjectStore.prototype.put = original
    }
    IDBObjectStore.prototype.put = function (...args) {
      if (this.name === 'documents') throw new DOMException('Storage full', 'QuotaExceededError')
      return original.apply(this, args)
    }
  })
  await page
    .getByRole('textbox', { name: 'Conteúdo do documento' })
    .fill('Este texto precisa ficar.')
  await expect(page.getByRole('alert')).toContainText('Não foi possível salvar')
  await page.evaluate(() =>
    (window as unknown as { restoreDraftWrites: () => void }).restoreDraftWrites(),
  )
  await page.getByRole('button', { name: 'Tentar novamente', exact: true }).click()
  await expect(page.getByRole('alert')).toHaveCount(0)
  await page.reload()
  await expect(page.getByRole('textbox', { name: 'Conteúdo do documento' })).toHaveText(
    'Este texto precisa ficar.',
  )
})
