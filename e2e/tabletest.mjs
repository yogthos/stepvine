import { chromium } from 'playwright';
const BASE='http://localhost:3000';
const b=await chromium.launch(); const p=await(await b.newContext()).newPage();
p.on('pageerror',e=>console.log('PAGEERR',e.message.slice(0,90)));
async function ready(p){const s=p.waitForRequest(r=>/\/sse/.test(r.url()),{timeout:5000});await p.waitForSelector('#name');await s;await p.waitForTimeout(250);}
const tcells=async()=>{const rs=p.locator('#sec-table tbody tr[data-table-row-idx]');const c=await rs.count();const out=[];for(let i=0;i<c;i++)out.push([await rs.nth(i).locator('input').nth(0).inputValue(),await rs.nth(i).locator('input').nth(1).inputValue()]);return out;};
await p.goto(BASE+'/login');
await p.fill('input[name=username]','admin');await p.fill('input[name=password]','admin');
await p.click('button:has-text("Sign in")');await p.waitForLoadState('networkidle');
await p.click('button:has-text("New Widget Showcase")'); await ready(p);
const url=p.url();
// add 4 rows, fill distinct values
for(let i=0;i<4;i++){await p.click('#sec-table .widget-table-add-row');await p.waitForTimeout(280);}
const data=[['Delta','4'],['Alpha','1'],['Charlie','3'],['Bravo','2']];
for(let i=0;i<4;i++){const r=p.locator('#sec-table tbody tr[data-table-row-idx]').nth(i);await r.locator('input').nth(0).fill(data[i][0]);await r.locator('input').nth(1).fill(data[i][1]);await p.waitForTimeout(160);}
await p.waitForTimeout(300);
console.log('after fill (page1, size5 -> all 4):', JSON.stringify(await tcells()));
// edit an EXISTING (non-last) row's cell — does it stay put / not scramble?
await p.locator('#sec-table tbody tr[data-table-row-idx]').nth(1).locator('input').nth(0).fill('Alpha-EDIT'); await p.waitForTimeout(300);
console.log('after editing row1 title:', JSON.stringify(await tcells()));
// delete row 2 (Charlie)
await p.locator('#sec-table tbody tr[data-table-row-idx]').nth(2).locator('.widget-table-row-remove-control').click({timeout:3000}).catch(e=>console.log('DELETE click failed:',e.message.slice(0,60)));
await p.waitForTimeout(350);
console.log('after delete row2 (Charlie):', JSON.stringify(await tcells()));
// sort by title asc
await p.click('#sec-table th.widget-table-sortable-column:has-text("Title")'); await p.waitForTimeout(350);
console.log('after sort title asc:', JSON.stringify((await tcells()).map(r=>r[0])));
// reload, check persistence of remaining rows
await p.goto(url); await ready(p);
console.log('after RELOAD:', JSON.stringify(await tcells()));
await b.close();
