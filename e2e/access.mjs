// Focused storyboard for per-role view access. Verifies the admin /admin/forms
// picker (role × view checkboxes, not a freeform text box) and that the grant is
// enforced: a user whose role is scoped to one view lands on that view and cannot
// reach the others.
//
//   BASE=http://localhost:3000 node access.mjs
import { chromium } from 'playwright';

const BASE = process.env.BASE || 'http://localhost:3000';
let failures = 0;
const ok   = (m) => console.log(`  ✓ ${m}`);
const bad  = (m) => { failures++; console.log(`  ✗ ${m}`); };
const step = (n) => console.log(`\n=== ${n} ===`);

const pageErrors = [];
const watch = (p, l) => { p.on('pageerror', e => pageErrors.push(`[${l}] ${e.message}`));
                          p.on('console', m => { if (m.type() === 'error') pageErrors.push(`[${l}] console: ${m.text()}`); }); };

async function signIn(page, u, pw) {
  await page.goto(BASE + '/login');
  await page.fill('input[name=username]', u); await page.fill('input[name=password]', pw);
  await Promise.all([page.waitForLoadState('networkidle'), page.click('button:has-text("Sign in")')]);
}
async function openDoc(page, navigate, readySel, ms = 6000) {
  const sse = page.waitForRequest(r => /\/sse/.test(r.url()), { timeout: ms });
  await navigate(); if (readySel) await page.waitForSelector(readySel); await sse;
  await page.waitForTimeout(200);
}

const browser = await chromium.launch();
try {
  const adminCtx = await browser.newContext();
  const admin = await adminCtx.newPage(); watch(admin, 'admin');

  step('1. Admin forms picker is a role × view grid (no freeform roles box)');
  await signIn(admin, 'admin', 'admin');
  await admin.goto(BASE + '/admin/forms');
  const sec = admin.locator('section.app-access', { hasText: 'Project Report' });
  (await sec.count()) > 0 ? ok('Project Report has an access section') : bad('no access section for Project Report');
  (await admin.locator('input[name=roles]').count()) === 0 ? ok('the old freeform "roles" text box is gone') : bad('freeform roles box still present');
  (await sec.locator('input[name=new-role]').count()) > 0 ? ok('an "add a new role" input is offered') : bad('no add-role input');
  (await sec.locator('th:has-text("default")').count()) > 0 && (await sec.locator('th:has-text("preview")').count()) > 0
    ? ok('the grid lists the form\'s views (default, preview) as columns') : bad('view columns missing from the grid');

  step('2. Add a role, then scope it to the preview view only');
  await sec.locator('input[name=new-role]').fill('reviewer');
  await Promise.all([admin.waitForNavigation(), sec.locator('button:has-text("Save access")').click()]);
  const sec2 = admin.locator('section.app-access', { hasText: 'Project Report' });
  (await sec2.locator('input[name=role][value=reviewer]').count()) > 0 ? ok('the new role appears in the grid after saving') : bad('new role row missing');
  // scope reviewer → preview only (role stays checked; tick only the preview view)
  await sec2.locator('input[name=role][value=reviewer]').check();
  await sec2.locator('input[name="v_reviewer"][value=preview]').check();
  // make sure default is NOT ticked for reviewer
  if (await sec2.locator('input[name="v_reviewer"][value=default]').isChecked()) await sec2.locator('input[name="v_reviewer"][value=default]').uncheck();
  await Promise.all([admin.waitForNavigation(), sec2.locator('button:has-text("Save access")').click()]);
  const sec3 = admin.locator('section.app-access', { hasText: 'Project Report' });
  (await sec3.locator('input[name="v_reviewer"][value=preview]').isChecked()) ? ok('reviewer is scoped to the preview view (persisted)') : bad('preview grant not persisted');
  (await sec3.locator('input[name="v_reviewer"][value=default]').isChecked()) ? bad('default view wrongly granted to reviewer') : ok('reviewer is NOT granted the edit (default) view');

  step('3. Give a user the reviewer role');
  await admin.goto(BASE + '/admin/users');
  await admin.fill('form[action="/admin/users/new"] input[name=username]', 'rev');
  await admin.fill('form[action="/admin/users/new"] input[name=password]', 'pw');
  await admin.fill('form[action="/admin/users/new"] input[name=roles]', 'reviewer');
  await Promise.all([admin.waitForNavigation(), admin.click('button:has-text("Create user")')]);
  (await admin.locator('td:has-text("rev")').count()) > 0 ? ok('created user "rev" with the reviewer role') : bad('failed to create reviewer user');
  await adminCtx.close();

  step('4. The reviewer sees only their granted view');
  const uCtx = await browser.newContext();
  const page = await uCtx.newPage(); watch(page, 'reviewer');
  await signIn(page, 'rev', 'pw');
  (await page.locator('button:has-text("New Project Report")').count()) > 0
    ? ok('the role-restricted form is visible to the reviewer (can access)') : bad('reviewer cannot see the form they were granted');
  // open a doc — requested default view is NOT permitted, so it must fall back to preview
  await openDoc(page, () => page.click('button:has-text("New Project Report")'), 'h1');
  (await page.locator('h1:has-text("Preview")').count()) > 0
    ? ok('opening the doc lands on the granted PREVIEW view (edit view withheld)') : bad(`did not land on preview: ${await page.locator('h1').first().innerText()}`);
  (await page.locator('input[data-bind="project"]').count()) === 0
    ? ok('the edit view\'s inputs are not rendered for the reviewer') : bad('edit-view inputs leaked to the reviewer');
  // explicitly requesting the edit view also falls back to preview (no access)
  const url = page.url().split('?')[0];
  await openDoc(page, () => page.goto(url + '?view=default'), 'h1');
  (await page.locator('h1:has-text("Preview")').count()) > 0
    ? ok('explicitly requesting ?view=default still resolves to the permitted preview view') : bad('reviewer reached the withheld edit view via ?view=default');
  await uCtx.close();

  step('Console / page errors');
  pageErrors.length === 0 ? ok('no uncaught page or console errors') : (bad(`${pageErrors.length} error(s):`), pageErrors.forEach(e => console.log('     ' + e)));
} catch (e) { bad(`storyboard threw: ${e.message}`); console.log(e.stack); }
finally { await browser.close(); }
console.log('\n' + (failures === 0 ? 'ALL STEPS PASSED' : `${failures} FAILURE(S)`));
process.exit(failures === 0 ? 0 : 1);
