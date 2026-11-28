// Spieler-Szenario auf einem echten Paper-Server mit VaultUnlocked und EssentialsX, gespielt von
// zwei mineflayer-Bots. Aufruf: node e2e.js <serverdir> <ergebnis.json>
// Voraussetzungen im Serverordner: paper.jar, plugins/{OrderHub,EssentialsX,VaultUnlocked}.jar,
// eula.txt, server.properties (online-mode=false, server-port=25599).
const { spawn, execFileSync } = require('child_process');
const fs = require('fs');
const path = require('path');
const mineflayer = require('mineflayer');

const dir = path.resolve(process.argv[2]);
const outFile = path.resolve(process.argv[3] || 'e2e-result.json');
const PORT = parseInt(process.env.SIM_PORT || '25599', 10);
const VERSION = process.env.SIM_MC_VERSION || '1.21.11';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const results = [];
const logLines = [];
let server = null;

function record(name, ok, detail) {
  results.push({ name, ok: !!ok, detail: detail || '' });
  console.log((ok ? 'PASS ' : 'FAIL ') + name + (detail ? ' | ' + detail : ''));
}

function startServer() {
  logLines.length = 0;
  server = spawn('java', ['-Xmx1G', '-jar', 'paper.jar', 'nogui'], { cwd: dir });
  const onData = (d) => d.toString().split('\n').forEach((l) => l && logLines.push(l));
  server.stdout.on('data', onData);
  server.stderr.on('data', onData);
  return new Promise((resolve, reject) => {
    const t0 = Date.now();
    const iv = setInterval(() => {
      if (logLines.some((l) => l.includes('OrderHub enabled')) && logLines.some((l) => l.includes('Done ('))) {
        clearInterval(iv);
        resolve();
      } else if (Date.now() - t0 > 240000) {
        clearInterval(iv);
        reject(new Error('server start timeout'));
      }
    }, 500);
  });
}
const cmd = (c) => server.stdin.write(c + '\n');
async function waitLog(re, ms = 15000, from = 0) {
  const t0 = Date.now();
  while (Date.now() - t0 < ms) {
    const hit = logLines.slice(from).find((l) => re.test(l));
    if (hit) return hit;
    await sleep(200);
  }
  return null;
}
async function stopServer() {
  cmd('stop');
  await new Promise((r) => server.on('exit', r));
}

function db(sql) {
  const py = `import sqlite3,json,sys
c=sqlite3.connect('file:${path.join(dir, 'plugins/OrderHub/orderhub.db')}?mode=ro',uri=True)
print(json.dumps(c.execute(sys.argv[1]).fetchall()))`;
  return JSON.parse(execFileSync('python3', ['-I', '-c', py, sql]).toString());
}

async function makeBot(name) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: VERSION });
  bot.on('error', (e) => console.log(name, 'error', e.message));
  bot.on('kicked', (r) => console.log(name, 'kicked', JSON.stringify(r)));
  await new Promise((res, rej) => {
    bot.once('spawn', res);
    setTimeout(() => rej(new Error(name + ' spawn timeout')), 60000);
  });
  await sleep(1500);
  return bot;
}
function nextWindow(bot, ms = 15000) {
  return new Promise((res, rej) => {
    const t = setTimeout(() => rej(new Error('no window')), ms);
    bot.once('windowOpen', (w) => { clearTimeout(t); setTimeout(() => res(w), 400); });
  });
}
const count = (bot, name) => bot.inventory.items().filter((i) => i.name === name).reduce((n, i) => n + i.count, 0);
function invSlotWith(bot, name) {
  const w = bot.currentWindow;
  const first = w.inventoryStart;
  for (let s = first; s < w.inventoryEnd; s++) {
    const it = w.slots[s];
    if (it && it.name === name) return s;
  }
  return -1;
}
async function stageAndConfirm(bot, orderId, confirm = true) {
  const opened = nextWindow(bot);
  bot.chat('/order deliver ' + orderId);
  const w = await opened;
  const slot = invSlotWith(bot, 'diamond');
  await bot.clickWindow(slot, 0, 0); // aufnehmen
  await bot.clickWindow(10, 0, 0); // in Eingabeslot legen
  await sleep(300);
  if (confirm) await bot.clickWindow(53, 0, 0);
  return w;
}
async function balance(name) {
  const from = logLines.length;
  cmd('balance ' + name);
  const hit = await waitLog(new RegExp('Balance of .*' + name + '.*\\$'), 8000, from);
  return hit;
}

(async () => {
  // ---- Lauf 1
  await startServer();
  record('server startet mit EssentialsX, VaultUnlocked und OrderHub', logLines.some((l) => l.includes('OrderHub enabled')));
  record('keine ERROR-Zeilen von OrderHub beim Start', !logLines.some((l) => /ERROR\]: \[OrderHub\]|SLF4J/.test(l)));
  record('Vault-Economy erkannt', !logLines.some((l) => /Vault is not installed|no economy provider/i.test(l)), logLines.find((l) => /Vault/.test(l) && /OrderHub/.test(l)) || '');
  const owner = await makeBot('Owner');
  const sup = await makeBot('Supplier');
  cmd('eco set Owner 100000');
  cmd('eco set Supplier 0');
  await sleep(1500);
  cmd('give Owner diamond 1');
  cmd('give Supplier diamond 8');
  await sleep(1500);
  record('Bots haben ihre Items', count(owner, 'diamond') === 1 && count(sup, 'diamond') === 8, `owner=${count(owner, 'diamond')} supplier=${count(sup, 'diamond')}`);

  // Auftrag erstellen: 5 Diamanten zu 10,00
  const win1 = nextWindow(owner);
  owner.chat('/order create 5 10');
  try {
    await win1;
    await owner.clickWindow(11, 0, 0);
    await sleep(2500);
  } catch (e) { record('Bestaetigungsmenue oeffnet', false, e.message); }
  const orders = db('select id,status,quantity_total,quantity_filled,escrow_remaining from orders');
  record('Auftrag erstellt und Escrow gebucht', orders.length === 1 && orders[0][2] === 5 && orders[0][4] === 5000, JSON.stringify(orders));
  const orderId = orders.length ? orders[0][0] : 1;

  // Teil- und Ueberschusslieferung: 8 Diamanten ins Menue, 5 gebraucht
  try {
    await stageAndConfirm(sup, orderId, true);
    await sleep(4000);
  } catch (e) { record('Liefermenue', false, e.message); }
  const after = db('select status,quantity_filled,escrow_remaining from orders where id=' + orderId);
  record('Lieferung festgeschrieben, Auftrag abgeschlossen', after.length && after[0][1] === 5 && after[0][0] === 'COMPLETED', JSON.stringify(after));
  const payouts = db("select state,amount from payout_queue");
  record('Auszahlung abgeschlossen (Queue COMPLETED)', payouts.length === 1 && payouts[0][0] === 'COMPLETED', JSON.stringify(payouts));
  await sleep(500);
  const surplusInv = count(sup, 'diamond');
  record('Ueberschuss (3) liegt wieder beim Lieferanten', surplusInv === 3, 'supplier diamonds=' + surplusInv);
  const bal = await balance('Supplier');
  record('Lieferant hat 49 (50 minus 2 % Steuer) ueber Vault/Essentials bekommen', !!bal && /\$\s*49(\D|$)/.test(bal.replace(/\u001b\[[0-9;]*m/g, '')), bal || 'keine Antwort');

  // Abholung durch den Auftraggeber
  try {
    const wc = nextWindow(owner);
    owner.chat('/order claims');
    await wc;
    const w = owner.currentWindow;
    let slot = -1;
    for (let s = 0; s < w.inventoryStart; s++) if (w.slots[s] && w.slots[s].name === 'diamond') { slot = s; break; }
    await owner.clickWindow(slot, 0, 0);
    await sleep(2500);
  } catch (e) { record('Claim-Menue', false, e.message); }
  record('Auftraggeber hat die 5 Diamanten abgeholt (1 Vorlage + 5)', count(owner, 'diamond') === 6, 'owner diamonds=' + count(owner, 'diamond'));
  const claims = db("select state,quantity from item_claims");
  record('Claim in der Datenbank abgeschlossen', claims.length === 1 && claims[0][0] === 'CLAIMED', JSON.stringify(claims));

  // Ablauf mit Erstattung: zweiter Auftrag mit kurzer Laufzeit gibt es nicht ueber den Befehl, daher Storno.
  cmd('give Owner diamond 1');
  await sleep(1000);
  const w2 = nextWindow(owner);
  owner.chat('/order create 4 10');
  try { await w2; await owner.clickWindow(11, 0, 0); await sleep(2500); } catch (e) { record('zweiter Auftrag', false, e.message); }
  const o2 = db("select id from orders where status='ACTIVE'");
  const order2 = o2.length ? o2[0][0] : -1;
  cmd(`orderadmin cancel ${order2} confirm e2e refund check`);
  await sleep(4000);
  const refunds = db("select state,amount from refund_queue");
  record('Storno erstattet den Escrow (Refund COMPLETED)', refunds.length === 1 && refunds[0][0] === 'COMPLETED' && refunds[0][1] === 4000, JSON.stringify(refunds));

  // ---- Absturz mitten in einer Lieferung
  cmd('give Owner diamond 1');
  cmd('give Supplier diamond 6');
  await sleep(1500);
  const w3 = nextWindow(owner);
  owner.chat('/order create 4 10');
  try { await w3; await owner.clickWindow(11, 0, 0); await sleep(3500); } catch (e) { record('dritter Auftrag', false, e.message); }
  const o3 = db("select id from orders where status='ACTIVE'");
  const order3 = o3.length ? o3[0][0] : -1;
  const supBefore = count(sup, 'diamond');
  let killed = false;
  try {
    const opened = nextWindow(sup);
    sup.chat('/order deliver ' + order3);
    await opened;
    const slot = invSlotWith(sup, 'diamond');
    await sup.clickWindow(slot, 0, 0);
    await sup.clickWindow(10, 0, 0);
    await sleep(300);
    const click = sup.clickWindow(53, 0, 0);
    await sleep(60); // wenige Millisekunden nach dem Bestaetigen: harter Absturz
    server.kill('SIGKILL');
    killed = true;
    await click.catch(() => {});
  } catch (e) { console.log('crash scenario', e.message); if (!killed) { server.kill('SIGKILL'); } }
  await new Promise((r) => server.on('exit', r));
  record('Server wurde hart beendet (SIGKILL) direkt nach der Bestaetigung', true, 'diamonds before=' + supBefore);
  try { owner.end(); sup.end(); } catch (e) {}
  await sleep(1000);

  // ---- Lauf 2: Neustart, Recovery
  await startServer();
  await sleep(6000);
  const rec = logLines.find((l) => /Recovery:/.test(l));
  record('Recovery lief nach dem Neustart', !!rec, rec || '');
  const journal = db(`select id,state,quantity from transaction_journal where tx_type='DELIVERY' and order_id=${order3}`);
  const holds = db("select state,quantity from delivery_holds where state='HELD'");
  record('keine offene Reservierung (HELD) nach der Recovery', holds.length === 0, JSON.stringify(holds));
  if (journal.length === 0) {
    record('Absturz vor prepare: kein Journal-Eintrag, nichts verbucht', true, 'kein Eintrag');
  } else {
    for (const [id, state] of journal) {
      const held = db(`select quantity from delivery_holds where tx_id='${id}'`);
      const claimed = db(`select coalesce(sum(quantity),0) from item_claims where source_tx='${id}'`)[0][0];
      const returned = db(`select coalesce(sum(quantity),0) from item_returns where source_tx='${id}'`)[0][0];
      const heldQty = held.length ? held[0][0] : 0;
      const info = JSON.stringify({ state, heldQty, claimed, returned });
      record('Absturz nach prepare: Claim + Rueckgabe = alle Items im Menue (kein Verlust, kein Dupe)', heldQty > 0 && claimed + returned === heldQty, info);
    }
  }
  const review = db("select count(*) from transaction_journal where state='MANUAL_REVIEW'");
  record('keine unklaren Zahlungen (MANUAL_REVIEW) im Lauf', review[0][0] === 0, JSON.stringify(review));
  await stopServer();
  fs.writeFileSync(outFile, JSON.stringify(results, null, 2));
  const failed = results.filter((r) => !r.ok);
  console.log(`\n${results.length - failed.length}/${results.length} bestanden`);
  process.exit(failed.length ? 1 : 0);
})().catch(async (e) => {
  console.log('ABBRUCH', e);
  try { server && server.kill('SIGKILL'); } catch (x) {}
  fs.writeFileSync(outFile, JSON.stringify(results.concat([{ name: 'abbruch', ok: false, detail: String(e) }]), null, 2));
  process.exit(2);
});
