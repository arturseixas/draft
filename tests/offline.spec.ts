import { test, expect } from '@playwright/test'
test('production app opens, edits and saves offline', async ({ page, context }) => {
  test.skip(!process.env.DRAFT_TEST_URL, 'The service worker is enabled in production builds.')
  await page.goto('/')
  await expect(page.getByRole('textbox', { name: 'Título do documento' })).toBeVisible()
  await page.evaluate(async () => {
    await navigator.serviceWorker.ready
    if (!navigator.serviceWorker.controller)
      await new Promise<void>((resolve) =>
        navigator.serviceWorker.addEventListener('controllerchange', () => resolve(), {
          once: true,
        }),
      )
  })
  await context.setOffline(true)
  await page.reload()
  await expect(page.getByRole('textbox', { name: 'Título do documento' })).toHaveValue(
    'O lugar onde as ideias começam',
  )
  await page.keyboard.press('Control+Alt+n')
  await page.getByRole('dialog').getByLabel('NOME DO DOCUMENTO').fill('Ideias sem conexão')
  await page
    .getByRole('dialog')
    .getByRole('button', { name: 'Criar documento', exact: true })
    .click()
  await page
    .getByRole('textbox', { name: 'Conteúdo do documento' })
    .fill('Mesmo longe da rede, a história continua.')
  await page.reload()
  await expect(page.getByRole('textbox', { name: 'Título do documento' })).toHaveValue(
    'Ideias sem conexão',
  )
  await expect(page.getByRole('textbox', { name: 'Conteúdo do documento' })).toHaveText(
    'Mesmo longe da rede, a história continua.',
  )
  await expect(page.locator('.offline-label')).toBeVisible()
})
