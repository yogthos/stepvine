import { chromium } from 'playwright';
const BASE='http://localhost:3000';
const b=await chromium.launch(); const ctx=await b.newContext(); const p=await ctx.newPage();
p.on('pageerror',e=>console.log('PAGEERR',e.message.slice(0,90)));
async function ready(p){const s=p.waitForRequest(r=>/\/sse/.test(r.url()),{timeout:5000});await p.waitForSelector('#name');await s;await p.waitForTimeout(250);}
const seed = async (pg) => JSON.parse(await pg.locator('.sv-shell[data-signals]').first().getAttribute('data-signals').catch(async()=>await pg.locator('[data-signals]').first().getAttribute('data-signals')));
await p.goto(BASE+'/login');
await p.fill('input[name=username]','admin');await p.fill('input[name=password]','admin');
await p.click('button:has-text("Sign in")');await p.waitForLoadState('networkidle');
await p.click('button:has-text("New Widget Showcase")'); await ready(p);
const url=p.url();
// --- set every widget ---
await p.fill('#name','Ada');                              await p.waitForTimeout(120);
await p.fill('#age','42');                                await p.waitForTimeout(120);
await p.fill('#bio','hello bio');                         await p.waitForTimeout(120);
await p.fill('#fruit','banana');                          await p.waitForTimeout(120);
await p.selectOption('#color','green');                   await p.waitForTimeout(120);
await p.check('input[name="size"][value="m"]');           await p.waitForTimeout(120);
await p.click('.selections-group button:has-text("Pro")');await p.waitForTimeout(120);
await p.click('.menu-group button:has-text("Brush")');    await p.waitForTimeout(120);
await p.check('#agree');                                  await p.waitForTimeout(120);
await p.locator('#rating').fill('8');                     await p.waitForTimeout(120);
await p.fill('#birthday','2020-01-15');                   await p.waitForTimeout(120);
await p.fill('#meeting','14:30');                         await p.waitForTimeout(120);
// collection + table
await p.click('#sec-people .add');                        await p.waitForTimeout(300);
const pr=p.locator('#sec-people .coll-item').first();
await pr.locator('input').nth(0).fill('Grace'); await pr.locator('input').nth(1).fill('Hopper'); await p.waitForTimeout(250);
await p.click('#sec-table .widget-table-add-row');        await p.waitForTimeout(300);
const tr=p.locator('#sec-table tbody tr[data-table-row-idx]').first();
await tr.locator('input').nth(0).fill('Ship it'); await tr.locator('input').nth(1).fill('1'); await p.waitForTimeout(300);
// --- reload (fresh page = render from persisted db) ---
await p.goto(url); await ready(p);
const s = await seed(p);
const want = {name:'Ada',age:42,bio:'hello bio',fruit:'banana',color:'green',size:'m',plan:'pro',tool:'brush',agree:true,rating:8,birthday:'2020-01-15',meeting:'14:30'};
console.log('=== top-level field persistence (after reload) ===');
for (const [k,v] of Object.entries(want)) {
  const got=s[k];
  console.log(`  ${(JSON.stringify(got)===JSON.stringify(v)||String(got)===String(v))?'✓':'✗'} ${k}: got=${JSON.stringify(got)} want=${JSON.stringify(v)}`);
}
console.log('=== collection/table persistence ===');
const peopleFull = Object.entries(s).filter(([k])=>k.startsWith('people_')&&k.endsWith('_full')).map(([,v])=>v);
console.log(`  ${peopleFull.includes('Grace Hopper')?'✓':'✗'} people row persisted (full=${JSON.stringify(peopleFull)})`);
const taskTitles = Object.entries(s).filter(([k])=>k.startsWith('tasks_')&&k.endsWith('_title')).map(([,v])=>v);
console.log(`  ${taskTitles.includes('Ship it')?'✓':'✗'} table row persisted (titles=${JSON.stringify(taskTitles)})`);
await b.close();
