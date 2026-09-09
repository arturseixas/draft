import { test, expect, type Page } from '@playwright/test'
import type { Revision } from '../src/lib/drive/merge'
async function mockGoogle(page: Page) {
  const files = new Map<string, Revision>()
  let failUploads = false
  let denied = false
  await page.addInitScript(() =>
    localStorage.setItem('draft:google-client-id', '123456-testclient.apps.googleusercontent.com'),
  )
  await page.route('https://accounts.google.com/gsi/client', (route) =>
    route.fulfill({
      contentType: 'application/javascript',
      body: `window.google={accounts:{oauth2:{initTokenClient:config=>({requestAccessToken:()=>config.callback({access_token:'test-only-token',expires_in:3600,scope:'https://www.googleapis.com/auth/drive.appdata openid email'})})}}};`,
    }),
  )
  await page.route('https://www.googleapis.com/oauth2/v3/userinfo', (route) =>
    route.fulfill({ json: { sub: 'test-account', email: 'writer@example.test' } }),
  )
  await page.route('https://www.googleapis.com/drive/v3/files**', async (route) => {
    if (denied) {
      await route.fulfill({ status: 401, json: { error: 'expired' } })
      return
    }
    const url = new URL(route.request().url())
    if (url.searchParams.get('alt') === 'media') {
      const value = files.get(url.pathname.split('/').at(-1)!)
      await route.fulfill({ json: value })
      return
    }
    await route.fulfill({
      json: { files: [...files].map(([id, r]) => ({ id, name: `draft-revision-${r.id}.json` })) },
    })
  })
  await page.route('https://www.googleapis.com/upload/drive/v3/files**', async (route) => {
    if (failUploads) {
      await route.fulfill({ status: 503, json: { error: 'temporary' } })
      return
    }
    const text = route.request().postData()!
    const start = text.indexOf('{"version":1')
    const revision = JSON.parse(text.slice(start, text.lastIndexOf('\r\n--'))) as Revision
    files.set(revision.id, revision)
    await route.fulfill({ json: { id: revision.id } })
  })
  return {
    files,
    setFail: (value: boolean) => {
      failUploads = value
    },
    setDenied: (value: boolean) => {
      denied = value
    },
  }
}
async function openDrive(page: Page) {
  const mobile = page.getByRole('button', { name: 'Abrir documentos', exact: true })
  if (await mobile.isVisible()) await mobile.click()
  await page.getByRole('button', { name: 'Google Drive', exact: true }).click()
}
test('Drive configuration explains setup and rejects a secret instead of a client ID', async ({
  page,
}) => {
  await page.goto('/')
  await openDrive(page)
  await page.getByLabel('Client ID do Google', { exact: true }).fill('not-a-client-id')
  await page.getByRole('button', { name: 'Salvar configuração' }).click()
  await expect(page.getByRole('alert')).toContainText('Client ID')
  await page.getByText('Como criar meu Client ID gratuito', { exact: true }).click()
  await expect(page.getByText('Google Drive API', { exact: true })).toBeVisible()
})
test('Drive uploads, receives changes and requires reconnect after reopening', async ({ page }) => {
  const cloud = await mockGoogle(page)
  await page.goto('/')
  await openDrive(page)
  await page.getByRole('button', { name: 'Conectar ao Google Drive', exact: true }).click()
  await expect(page.getByText(/Última sincronização às/)).toBeVisible()
  expect(cloud.files.size).toBe(4)
  const original = [...cloud.files.values()].find(
    (r) => r.document?.title === 'O lugar onde as ideias começam',
  )!
  cloud.files.set('remote-edit', {
    ...original,
    id: 'remote-edit',
    parents: [original.id],
    document: {
      ...original.document!,
      title: 'Título recebido do Drive',
      updatedAt: Date.now() + 1,
    },
  })
  await page.getByRole('button', { name: 'Sincronizar agora' }).click()
  await expect(page.getByText(/Última sincronização às/)).toBeVisible()
  await page.getByRole('dialog').getByRole('button', { name: 'Fechar', exact: true }).click()
  await expect(page.getByRole('textbox', { name: 'Título do documento' })).toHaveValue(
    'Título recebido do Drive',
  )
  expect(
    await page.evaluate(() =>
      Object.values(localStorage).some((value) => value.includes('test-only-token')),
    ),
  ).toBe(false)
  await page.reload()
  await openDrive(page)
  await expect(
    page.getByRole('button', { name: 'Reconectar ao Google Drive', exact: true }),
  ).toBeVisible()
})
test('Drive keeps edits after a failed upload and retries without duplicate revisions', async ({
  page,
}) => {
  const cloud = await mockGoogle(page)
  cloud.setFail(true)
  await page.goto('/')
  await openDrive(page)
  await page.getByRole('button', { name: 'Conectar ao Google Drive', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('temporariamente indisponível')
  cloud.setFail(false)
  await page.getByRole('button', { name: 'Sincronizar agora' }).click()
  await expect(page.getByText(/Última sincronização às/)).toBeVisible()
  expect(cloud.files.size).toBe(4)
  await page.getByRole('button', { name: 'Sincronizar agora' }).click()
  await expect(page.getByRole('button', { name: 'Sincronizar agora' })).toBeEnabled()
  expect(cloud.files.size).toBe(4)
  cloud.setDenied(true)
  await page.getByRole('button', { name: 'Sincronizar agora' }).click()
  await expect(
    page.getByRole('button', { name: 'Reconectar ao Google Drive', exact: true }),
  ).toBeVisible()
})
