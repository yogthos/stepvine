// Auth gate + form-list visibility + a couple of widget-UX guards.
//
//   • anonymous users get the login screen (never a page of forms),
//   • a signed-in user sees only the forms they can access (open forms + ones
//     their role is granted), not role-restricted forms they lack,
//   • the radio group renders compactly (regression guard for the absurd
//     8rem-per-option spacing bug).
//
//   BASE=http://localhost:3000 node auth-access.mjs
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
async function ensureUser(page, u, dn, pw) {
  await page.goto(BASE + '/register');
  await page.fill('input[name=username]', u); await page.fill('input[name=display-name]', dn); await page.fill('input[name=password]', pw);
  await page.click('button:has-text("Create account")');
  try { await page.waitForURL(BASE + '/', { timeout: 3000 }); } catch { await signIn(page, u, pw); }
}

const browser = await chromium.launch();
try {
  step('1. Anonymous users get the login screen, never a forms page');
  const anonCtx = await browser.newContext();
  const anon = await anonCtx.newPage(); watch(anon, 'anon');
  await anon.goto(BASE + '/');
  anon.url().includes('/login') ? ok('GET / redirects an anonymous visitor to /login') : bad(`/ did not gate: ${anon.url()}`);
  (await anon.locator('input[name=username]').count()) > 0 && (await anon.locator('button:has-text("Sign in")').count()) > 0
    ? ok('the login form is shown') : bad('no login form on the gate');
  (await anon.locator('button:has-text("New ")').count()) === 0 && (await anon.locator('.doc').count()) === 0
    ? ok('no form-create buttons or documents leak to the anonymous visitor') : bad('forms/documents visible while logged out');
  for (const path of ['/admin/forms', '/search', '/queue', '/doc/00000000-0000-0000-0000-000000000000']) {
    await anon.goto(BASE + path);
    anon.url().includes('/login') ? ok(`protected ${path} redirects to /login`) : bad(`${path} not gated: ${anon.url()}`);
  }
  await anonCtx.close();

  step('2. A signed-in user sees only forms they can access');
  // admin restricts a demo form (Order Calculator) to a role the test user lacks
  const adminCtx = await browser.newContext();
  const admin = await adminCtx.newPage(); watch(admin, 'admin');
  await signIn(admin, 'admin', 'admin');
  await admin.goto(BASE + '/admin/forms');
  const oSec = admin.locator('section.app-access', { hasText: 'Order Calculator' });
  await oSec.locator('input[name=new-role]').fill('finance');
  await Promise.all([admin.waitForNavigation(), oSec.locator('button:has-text("Save access")').click()]);
  ok('admin restricted "Order Calculator" to the finance role');
  await adminCtx.close();

  const uCtx = await browser.newContext();
  const user = await uCtx.newPage(); watch(user, 'user');
  await ensureUser(user, 'guest', 'Guest User', 'pw');
  (await user.locator('button:has-text("New BMI Calculator")').count()) > 0
    ? ok('an OPEN form (BMI Calculator) is visible to a role-less user') : bad('open form not visible');
  (await user.locator('button:has-text("New Order Calculator")').count()) === 0
    ? ok('the finance-restricted form is hidden from the role-less user') : bad('restricted form leaked to a user without the role');
  // admin still sees the restricted form
  const a2 = await (await browser.newContext()).newPage(); watch(a2, 'admin2');
  await signIn(a2, 'admin', 'admin');
  (await a2.locator('button:has-text("New Order Calculator")').count()) > 0
    ? ok('admin still sees the restricted form (admin override)') : bad('admin lost access to a restricted form');

  step('3. Radio group renders compactly (UX regression guard)');
  const sse = user.waitForRequest(r => /\/sse/.test(r.url()), { timeout: 8000 });
  await user.click('button:has-text("New Project Report")'); await user.waitForSelector('.widget.radio.field'); await sse;
  await user.waitForTimeout(200);
  const opt = user.locator('.widget.radio .radio-option').first();
  const box = await opt.boundingBox();
  box && box.height < 40
    ? ok(`each radio option is a normal row height (${Math.round(box.height)}px, not ~128px)`)
    : bad(`radio option is absurdly tall: ${box && Math.round(box.height)}px`);
  await uCtx.close();

  step('4. A stale session (the user was deleted) is treated as logged out');
  // ghost registers and creates a document, then is deleted by an admin. Holding
  // a session cookie with a now-orphaned :user-id must NOT grant access — the
  // exact bypass that let a "signed-out" visitor see a document + form list.
  const ghostCtx = await browser.newContext();
  const ghost = await ghostCtx.newPage(); watch(ghost, 'ghost');
  await ensureUser(ghost, 'ghost', 'Ghost User', 'pw');
  const sse2 = ghost.waitForRequest(r => /\/sse/.test(r.url()), { timeout: 8000 });
  await ghost.click('button:has-text("New Project Report")'); await ghost.waitForSelector('input[data-bind="project"]'); await sse2;
  const ghostDoc = ghost.url();
  ghostDoc.includes('/doc/') ? ok('ghost created a document while logged in') : bad('ghost could not create a doc');

  const adm = await (await browser.newContext()).newPage(); watch(adm, 'admin3');
  await signIn(adm, 'admin', 'admin');
  await adm.goto(BASE + '/admin/users');
  await Promise.all([adm.waitForNavigation(),
                     adm.locator('tr', { hasText: 'ghost' }).locator('button:has-text("Delete")').click()]);
  (await adm.locator('tr', { hasText: 'ghost' }).count()) === 0 ? ok('admin deleted the ghost user') : bad('ghost not deleted');

  // ghost's session cookie is now orphaned — every protected route must bounce to /login
  await ghost.goto(BASE + '/');
  ghost.url().includes('/login') ? ok('the deleted user\'s session no longer shows the form list (→ /login)') : bad(`stale session still saw the landing: ${ghost.url()}`);
  await ghost.goto(ghostDoc);
  ghost.url().includes('/login') ? ok('the deleted user can no longer open their old document (→ /login)') : bad(`stale session still opened the document: ${ghost.url()}`);
  await ghostCtx.close();

  step('Console / page errors');
  pageErrors.length === 0 ? ok('no uncaught page or console errors') : (bad(`${pageErrors.length} error(s):`), pageErrors.forEach(e => console.log('     ' + e)));
} catch (e) { bad(`storyboard threw: ${e.message}`); console.log(e.stack); }
finally { await browser.close(); }
console.log('\n' + (failures === 0 ? 'ALL STEPS PASSED' : `${failures} FAILURE(S)`));
process.exit(failures === 0 ? 0 : 1);
