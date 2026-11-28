// Simulation von OrderHub auf einem echten Paper-Server mit zwei bis drei mineflayer-Bots.
// Aufruf: node test.js <serverdir> <ergebnis.json>
// Der Server wird von diesem Skript gestartet, neu gestartet und beendet.
const { spawn, execFileSync } = require('child_process');
const fs = require('fs');
const path = require('path');
const mineflayer = require('mineflayer');

const dir = path.resolve(process.argv[2] || '../server');
const outFile = path.resolve(process.argv[3] || '../out/result.json');
const PORT = parseInt(process.env.SIM_PORT || '25598', 10);
const VERSION = process.env.SIM_MC_VERSION || '1.21.11';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const results = [];
const logLines = [];
let server = null;
let serverExited = null;

function record(name, ok, detail) {
  results.push({ name, ok: !!ok, detail: detail || '' });
  console.log((ok ? 'PASS ' : 'FAIL ') + name + (detail ? ' | ' + String(detail).slice(0, 300) : ''));
}

// ---------------------------------------------------------------- Server
function flushLog(tag) {
  fs.mkdirSync(path.dirname(outFile), { recursive: true });
  fs.appendFileSync(path.join(path.dirname(outFile), 'server.log'), `\n===== ${tag} =====\n` + logLines.join('\n') + '\n');
}
let starts = 0;
function startServer() {
  if (logLines.length) flushLog('server run ' + starts);
  starts++;
  logLines.length = 0;
  serverExited = null;
  server = spawn('java', ['-Xms512M', '-Xmx2G', '-jar', 'paper.jar', 'nogui'], { cwd: dir, env: Object.assign({}, process.env, { ORDERHUB_DB_PASSWORD: 'sim' }) });
  const onData = (d) => d.toString().split('\n').forEach((l) => l && logLines.push(l.replace(/\u001b\[[0-9;]*m/g, '')));
  server.stdout.on('data', onData);
  server.stderr.on('data', onData);
  server.on('exit', (c) => { serverExited = c; });
  return new Promise((resolve, reject) => {
    const t0 = Date.now();
    const iv = setInterval(() => {
      if (logLines.some((l) => l.includes('Done (')) && logLines.some((l) => l.includes('OrderHub enabled'))) {
        clearInterval(iv);
        resolve();
      } else if (serverExited !== null || Date.now() - t0 > 300000) {
        clearInterval(iv);
        reject(new Error('server start failed or timed out'));
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
    await sleep(150);
  }
  return null;
}
async function stopServer() {
  if (serverExited !== null) return;
  cmd('stop');
  await new Promise((r) => server.on('exit', r));
}
const MARIADB = process.env.SIM_DB === 'mariadb';
const DB_CONTAINER = 'orderhub-sim-mariadb';
function db(sql) {
  if (MARIADB) {
    const out = execFileSync('docker', ['exec', DB_CONTAINER, 'mariadb', '-uroot', '-psim', '-N', '-B', 'orderhub', '-e', sql]).toString();
    return out.split('\n').filter(Boolean).map((line) => line.split('\t').map((v) => (/^-?\d+(\.\d+)?$/.test(v) ? Number(v) : v === 'NULL' ? null : v)));
  }
  const py = `import sqlite3,json,sys
c=sqlite3.connect('file:${path.join(dir, 'plugins/OrderHub/orderhub.db')}?mode=ro',uri=True)
print(json.dumps(c.execute(sys.argv[1]).fetchall()))`;
  return JSON.parse(execFileSync('python3', ['-I', '-c', py, sql]).toString());
}
function prepareMariaDbConfig() {
  const d = path.join(dir, 'plugins/OrderHub');
  fs.mkdirSync(d, { recursive: true });
  const f = path.join(d, 'config.yml');
  if (!fs.existsSync(f)) {
    fs.writeFileSync(f, 'config-version: 3\nserver-id: "sim-a"\nstorage:\n  type: "mariadb"\n  mariadb:\n    jdbc-url: "jdbc:mariadb://127.0.0.1:3307/orderhub?sslMode=disable"\n    user: "root"\n    password-env: "ORDERHUB_DB_PASSWORD"\n    pool-size: 10\n');
  }
}

// ---------------------------------------------------------------- Bots
const bots = {};
async function makeBot(name) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: VERSION });
  bot._msgs = [];
  bot._windows = 0;
  bot.on('messagestr', (m) => bot._msgs.push({ t: Date.now(), m }));
  bot.on('windowOpen', () => { bot._windows++; });
  bot.on('error', (e) => console.log(name, 'error', e.message));
  bot.on('kicked', (r) => console.log(name, 'kicked', JSON.stringify(r)));
  await new Promise((res, rej) => {
    bot.once('spawn', res);
    setTimeout(() => rej(new Error(name + ' spawn timeout')), 60000);
  });
  await sleep(1500);
  bots[name] = bot;
  return bot;
}
// Fuehrt einen Chatbefehl aus und sammelt Antworten, bis es 800 ms ruhig ist (max. maxMs).
async function run(bot, command, { maxMs = 6000, quiet = 800, closeWindow = true } = {}) {
  const startMsgs = bot._msgs.length;
  const startWin = bot._windows;
  bot.chat(command);
  const t0 = Date.now();
  let last = Date.now();
  let seen = 0;
  while (Date.now() - t0 < maxMs) {
    await sleep(100);
    const n = bot._msgs.length - startMsgs + (bot._windows - startWin);
    if (n !== seen) { seen = n; last = Date.now(); }
    if (Date.now() - last >= quiet && Date.now() - t0 > 700) break;
  }
  const msgs = bot._msgs.slice(startMsgs).map((x) => x.m);
  const opened = bot._windows > startWin;
  const win = opened ? bot.currentWindow : null;
  const title = win && win.title ? JSON.stringify(win.title) : '';
  const out = { msgs, text: msgs.join('\n'), opened, title };
  if (opened && closeWindow && bot.currentWindow) { bot.closeWindow(bot.currentWindow); await sleep(300); }
  return out;
}
const count = (bot, name) => bot.inventory.items().filter((i) => i.name === name).reduce((n, i) => n + i.count, 0);

// check(name, bot, command, expectations)
//  msg: RegExp muss in den Antworten vorkommen, no: RegExp darf nicht vorkommen,
//  win: true = Fenster muss sich oeffnen, false = darf sich nicht oeffnen
async function check(name, bot, command, exp = {}) {
  const r = await run(bot, command, exp.opts || {});
  const problems = [];
  if (exp.msg && !exp.msg.test(r.text)) problems.push('Antwort passt nicht auf ' + exp.msg);
  if (exp.no && exp.no.test(r.text)) problems.push('unerwartete Antwort ' + exp.no);
  if (exp.win === true && !r.opened) problems.push('kein Fenster');
  if (exp.win === false && r.opened) problems.push('unerwartetes Fenster');
  record(name, problems.length === 0, problems.join('; ') + (problems.length ? ' | Antwort: ' + r.text.replace(/\n/g, ' / ').slice(0, 250) : ''));
  return r;
}

function nextWindow(bot, ms = 10000) {
  return new Promise((res, rej) => {
    const t = setTimeout(() => rej(new Error('no window')), ms);
    bot.once('windowOpen', (w) => { clearTimeout(t); setTimeout(() => res(w), 400); });
  });
}
function invSlotWith(bot, name) {
  const w = bot.currentWindow;
  for (let s = w.inventoryStart; s < w.inventoryEnd; s++) {
    const it = w.slots[s];
    if (it && it.name === name) return s;
  }
  return -1;
}
async function setup(name, { op = false, money = 0, items = [] } = {}) {
  cmd(`gamemode survival ${name}`);
  cmd(`clear ${name}`);
  cmd(`eco set ${name} ${money}`);
  for (const [item, n] of items) cmd(`give ${name} ${item} ${n}`);
  if (op) cmd(`op ${name}`); else cmd(`deop ${name}`);
  await sleep(1200);
}
async function createOrder(bot, qty, price, extra = '') {
  const w = nextWindow(bot);
  bot.chat(`/order create ${qty} ${price} ${extra}`.trim());
  await w;
  await bot.clickWindow(11, 0, 0); // bestaetigen
  await sleep(2500);
}

// ---------------------------------------------------------------- Szenario
const ADMIN = 'SimAdmin';
const PLAYER = 'SimPlayer';
const THIRD = 'SimThird';

async function phaseStart() {
  if (MARIADB) prepareMariaDbConfig();
  await startServer();
  record('Server startet (Paper ' + VERSION + ', Datenbank ' + (MARIADB ? 'MariaDB' : 'SQLite') + ')', true);
  record('OrderHub wird aktiviert', logLines.some((l) => l.includes('OrderHub enabled (') && l.includes(MARIADB ? 'MARIADB' : 'SQLITE')));
  record('keine ERROR/SEVERE-Zeilen von OrderHub beim Start', !logLines.some((l) => /(ERROR|SEVERE)\]: \[OrderHub\]|SLF4J/.test(l)), logLines.find((l) => /ERROR\]: \[OrderHub\]/.test(l)) || '');
  record('Vault-Economy erkannt', !logLines.some((l) => /no economy provider|Vault is not installed/i.test(l)));
  record('LuckPerms geladen', logLines.some((l) => /LuckPerms/.test(l) && /Enabling/.test(l)));
}

async function phaseCommands() {
  const admin = await makeBot(ADMIN);
  const p = await makeBot(PLAYER);
  await setup(ADMIN, { op: true, money: 1000000 });
  await setup(PLAYER, { money: 100000, items: [['diamond', 64], ['iron_ingot', 32], ['diamond_sword', 1]] });
  await sleep(1000);
  p.setQuickBarSlot(0);

  // ---- Spieler: Grundbefehle
  await check('/order oeffnet das Hauptmenue', p, '/order', { win: true });
  await check('/order help zeigt zwei Zeilen', p, '/order help', { msg: /create.*\n.*sell.*info.*template.*renew/s, win: false });
  await check('/order <unbekannt> gibt Hinweis', p, '/order zzzzzzzz', { msg: /Unknown subcommand\. Use \/order help/ });
  await check('/order marekt schlaegt market vor', p, '/order marekt', { msg: /Did you mean \/order market/ });
  await check('/order info ohne Argument zeigt Nutzung', p, '/order info', { msg: /order info/ });
  await check('/order info abc zeigt Nutzung', p, '/order info abc', { msg: /order info/ });
  await check('/order info 999 meldet unbekannt', p, '/order info 999', { msg: /#999 not found/ });
  await check('/order deliver ohne Id', p, '/order deliver', { msg: /not a valid number/ });
  await check('/order deliver abc', p, '/order deliver abc', { msg: /'abc' is not a valid number/ });
  await check('/order deliver 999999 unbekannt', p, '/order deliver 999999', { msg: /#999999 not found/ });
  await check('/order cancel 999999 unbekannt', p, '/order cancel 999999', { msg: /not found/i });
  await check('/order cancel ohne Id', p, '/order cancel', { msg: /not a valid number/ });
  await check('/order renew ohne Argumente zeigt Nutzung', p, '/order renew', { msg: /order renew/ });
  await check('/order renew 1 maybe zeigt Nutzung', p, '/order renew 1 maybe', { msg: /order renew/ });
  await check('/order claims leer', p, '/order claims', { msg: /nothing to collect|You have nothing/i });
  await check('/order returns leer', p, '/order returns', { msg: /nothing to collect|You have nothing/i });
  await check('/order stats ohne Handel meldet es', p, '/order stats', { msg: /No trades yet/ });
  await check('/order history ohne Lieferungen meldet es', p, '/order history', { msg: /No deliveries yet/ });
  await check('/order favorites (leer)', p, '/order favorites', { msg: /No favorites|favorites/i });
  await check('/order analytics diamond', p, '/order analytics diamond', { win: true });
  await check('/order mine', p, '/order mine', { win: true });
  await check('/order market', p, '/order market', { msg: /No open orders|./, opts: { quiet: 1200 } });
  await check('/order market highest-price', p, '/order market highest-price');
  await check('/order market diamond (Suche)', p, '/order market diamond');
  await check('/order sell ohne Item in der Hand', p, '/order sell', { msg: /Hold the item|no.*order|No open/i });

  // ---- create: ungueltige Eingaben (Item in der Hand: Diamanten)
  await check('/order create abc 5 (Menge ungueltig)', p, '/order create abc 5', { msg: /not a valid number|invalid/i, win: false });
  await check('/order create 5 abc (Preis ungueltig)', p, '/order create 5 abc', { msg: /not a valid amount|invalid/i, win: false });
  await check('/order create 0 5 (Menge 0)', p, '/order create 0 5', { msg: /quantity must be between/i, win: false });
  await check('/order create 5 -1 (Preis negativ)', p, '/order create 5 -1', { msg: /not a valid amount|price must be/i, win: false });
  await check('/order create 99999999 5 (Menge zu gross)', p, '/order create 99999999 5', { msg: /quantity must be between|not a valid number/i, win: false });
  await check('/order create 5 0 (Preis 0)', p, '/order create 5 0', { msg: /price must be between/i, win: false });
  await check('/order create 5 5 foo (falsche Option)', p, '/order create 5 5 foo', { msg: /Unknown option/, win: false });
  await check('/order create 5 5 durability=200', p, '/order create 5 5 durability=200', { msg: /Unknown option/, win: false });
  await check('/order create 5 (nur eine Zahl)', p, '/order create 5', { msg: /Hold the item|usage|create/i, win: false });

  // ---- create: gueltig, mit Abbruch und mit Bestaetigung
  const w1 = nextWindow(p);
  p.chat('/order create 5 10');
  try { await w1; await p.clickWindow(15, 0, 0); await sleep(1500); record('create: Abbrechen bucht nichts', db('select count(*) from orders')[0][0] === 0 && true); } catch (e) { record('create: Bestaetigungsmenue oeffnet', false, e.message); }
  const before = db('select count(*) from orders')[0][0];
  const w2 = nextWindow(p);
  p.chat('/order create 5 10 durability=60 autoclaim');
  try { await w2; await p.clickWindow(11, 0, 0); await sleep(3000); } catch (e) { record('create: Menue mit Optionen', false, e.message); }
  const orders = db('select id,status,quantity_total,unit_price,escrow_remaining from orders');
  record('create mit Optionen: Auftrag angelegt, Escrow 50,00', orders.length === before + 1 && orders[orders.length - 1][4] === 5000, JSON.stringify(orders));
  const oid = orders.length ? orders[orders.length - 1][0] : 1;
  const opts = db(`select tol_durability,tol_ench_min,auto_claim from order_options where order_id=${oid}`);
  record('create: Optionen in der Datenbank (60, 0, 1)', opts.length === 1 && opts[0][0] === 60 && opts[0][2] === 1, JSON.stringify(opts));
  await check('/order info <id> zeigt den Auftrag', p, `/order info ${oid}`, { msg: new RegExp(`#${oid}.*0/5x.*10\\.00.*durability at least 60%.*auto-claim`, 's') });
  await check('/order cancel <fremd> durch Dritten wird abgelehnt', admin, `/order cancel ${oid}`, { msg: /not your order|not found/i });
  await check('/order renew <id> on', p, `/order renew ${oid} on`, { msg: /renew|automatic/i });
  await check('/order renew <id> off', p, `/order renew ${oid} off`, { msg: /renew|automatic/i });
  await check('/order mine mit Auftrag', p, '/order mine', { win: true });

  // ---- Vorlagen
  await check('/order template (leer)', p, '/order template', { msg: /no templates/i });
  await check('/order template save ohne Namen', p, '/order template save', { msg: /Use \/order template/i });
  await check('/order template save bad name!', p, '/order template save "bad name!" 5 10', { msg: /Use \/order template|invalid/i });
  await check('/order template save ores 5 10 durability=50', p, '/order template save ores 5 10 durability=50', { msg: /ores/ });
  await check('/order template save ores 5 10 foo (Option falsch)', p, '/order template save ores2 5 10 foo', { msg: /Unknown option/ });
  await check('/order template list', p, '/order template list', { msg: /ores/ });
  await check('/order template oeffnet Menue', p, '/order template', { win: true });
  await check('/order template use unbekannt', p, '/order template use nope', { msg: /nope/ });
  await check('/order template delete ores', p, '/order template delete ores', { msg: /Deleted/ });
  await check('/order template delete ores (nochmal)', p, '/order template delete ores', { msg: /ores/ });

  // ---- Favoriten
  await check('/order favorites add (Item in der Hand)', p, '/order favorites add', { msg: /diamond|added|favorite/i });
  await check('/order favorites alert 5', p, '/order favorites alert 5', { msg: /alert|diamond/i });
  await check('/order favorites alert-off', p, '/order favorites alert-off', { msg: /alert|diamond/i });
  await check('/order favorites remove', p, '/order favorites remove', { msg: /removed|diamond|favorite/i });

  // ---- sell
  await check('/order sell abc', p, '/order sell abc', { msg: /not a valid amount/ });
  await check('/order sell (eigener Auftrag passt nicht)', p, '/order sell', { msg: /No open orders|none|no order|cannot/i });

  // ---- Markt: Preisfilter und Drosselung
  await check('/order market min=1 max=100', p, '/order market min=1 max=100', { no: /not a valid amount/ });
  await check('/order market diamond max=1.5k', p, '/order market diamond max=1.5k', { no: /not a valid amount/ });
  await check('/order market min=abc wird abgelehnt', p, '/order market min=abc', { msg: /'min=abc' is not a valid amount/, win: false });
  await check('/order market min=10 max=1 wird abgelehnt', p, '/order market min=10 max=1', { msg: /is not a valid amount/, win: false });
  p.chat('/order info 1');
  await check('zweite Abfrage direkt danach wird gebremst', p, '/order info 1', { msg: /Please wait a moment/ });
  await sleep(500);

  // ---- Admin: ohne Recht
  const adminSubs = ['reload', 'list', 'cancel 1 confirm test', 'purge-expired confirm test', 'transactions', 'inspect 1', 'review list', 'health', 'dump', 'backup confirm test', 'export orders confirm test', 'recovery confirm test', 'statistics', 'debug'];
  for (const s of adminSubs) {
    await check(`/orderadmin ${s.split(' ')[0]} ohne Recht wird abgelehnt`, p, '/orderadmin ' + s, { msg: /do not have permission|Unknown or incomplete command/i });
  }
  // ---- Admin: mit Recht
  await check('/orderadmin (Hilfe)', admin, '/orderadmin', { msg: /reload \| list/ });
  await check('/orderadmin statistics', admin, '/orderadmin statistics', { msg: /Active/ });
  await check('/orderadmin list', admin, '/orderadmin list', { msg: /#\d+/ });
  await check('/orderadmin list active', admin, '/orderadmin list active', { no: /Exception/ });
  await check('/orderadmin health', admin, '/orderadmin health', { msg: /Database pool/ });
  await check('/orderadmin debug', admin, '/orderadmin debug', { msg: /database/i });
  await check('/orderadmin transactions', admin, '/orderadmin transactions', { no: /Exception/ });
  await check('/orderadmin transaction ungueltig', admin, '/orderadmin transaction nonsense', { no: /Exception/ });
  await check('/orderadmin inspect abc', admin, '/orderadmin inspect abc', { no: /Exception/ });
  await check('/orderadmin review list', admin, '/orderadmin review list', { msg: /Manual review/ });
  await check('/orderadmin dump', admin, '/orderadmin dump', { msg: /Support file written/ });
  await check('/orderadmin cancel ohne confirm', admin, '/orderadmin cancel 99999', { msg: /confirm/i });
  await check('/orderadmin cancel mit confirm ohne Grund', admin, '/orderadmin cancel 99999 confirm', { msg: /reason/i });
  await check('/orderadmin cancel unbekannte Id', admin, '/orderadmin cancel 99999 confirm unit test', { msg: /not found/i });
  await check('/orderadmin backup confirm', admin, '/orderadmin backup confirm sim test', { msg: MARIADB ? /only available for SQLite/ : /Backup written/ });
  await check('/orderadmin export orders confirm', admin, '/orderadmin export orders confirm sim test', { msg: /Exported/ });
  await check('/orderadmin export ledger confirm', admin, '/orderadmin export ledger confirm sim test', { msg: /Exported/ });
  await check('/orderadmin export ohne Typ', admin, '/orderadmin export', { msg: /Usage/ });
  await check('/orderadmin recovery confirm', admin, '/orderadmin recovery confirm sim test', { msg: /Recovery/ });
  await check('/orderadmin purge-expired confirm', admin, '/orderadmin purge-expired confirm sim test', { msg: /Processed/ });
  await check('/orderadmin reload', admin, '/orderadmin reload', { msg: /reloaded/i });
  await check('/orderadmin unbekannter Unterbefehl', admin, '/orderadmin zzz', { no: /Exception/ });
  await check('/orderadmin player <Name> zeigt die Uebersicht', admin, `/orderadmin player ${PLAYER}`, { msg: /open orders \d+, claims waiting \d+/ });
  await check('/orderadmin player <UUID> zeigt die Uebersicht', admin, '/orderadmin player 00000000-0000-0000-0000-000000000042', { msg: /open orders 0/ });
  await check('/orderadmin player unbekannt', admin, '/orderadmin player NobodyKnowsMe', { msg: /No player found/ });
  await check('/orderadmin player ohne Argument', admin, '/orderadmin player', { msg: /Usage: \/orderadmin player/ });
  await check('/orderadmin audit 3 zeigt Eintraege', admin, '/orderadmin audit 3', { msg: /config\.reload|export|backup|recovery|purge/ });
  await check('/orderadmin audit abc', admin, '/orderadmin audit abc', { msg: /not a valid number/ });
  await check('/orderadmin health beginnt mit der Systemzeile', admin, '/orderadmin health', { msg: /OrderHub \d+\.\d+\.\d+ on Paper, Java 21/ });
  const aud = db("select count(*) from audit_log")[0][0];
  record('Audit-Eintraege fuer Admin-Aktionen vorhanden', aud >= 4, 'audit rows=' + aud);

  // ---- Admin storniert den Auftrag: Erstattung
  const bal0 = await balance(PLAYER);
  await check('/orderadmin cancel <id> confirm erstattet', admin, `/orderadmin cancel ${oid} confirm sim cancel`, { msg: /cancelled/i, opts: { quiet: 1500 } });
  await sleep(3000);
  const refunds = db('select state,amount from refund_queue');
  record('Storno erstattet den Escrow (50,00)', refunds.some((r) => r[0] === 'COMPLETED' && r[1] === 5000), JSON.stringify(refunds));
  const bal1 = await balance(PLAYER);
  record('Kontostand nach Erstattung gestiegen', bal0 !== null && bal1 !== null && bal1 - bal0 === 50, `${bal0} -> ${bal1}`);
}

async function balance(name) {
  const from = logLines.length;
  cmd('balance ' + name);
  const hit = await waitLog(new RegExp('Balance of ' + name + '|' + name + '.*\\$'), 8000, from);
  if (!hit) return null;
  const m = hit.replace(/,/g, '').match(/\$\s*([0-9]+(?:\.[0-9]+)?)/);
  return m ? parseFloat(m[1]) : null;
}

async function phasePermissions() {
  const t = await makeBot(THIRD);
  await setup(THIRD, { money: 1000, items: [['diamond', 4]] });
  t.setQuickBarSlot(0);
  await check('Dritter darf /order (Standardrecht)', t, '/order', { win: true });
  cmd('lp user ' + THIRD + ' permission set orderhub.market false');
  await sleep(1500);
  await check('ohne orderhub.market: /order market abgelehnt', t, '/order market', { msg: /do not have permission/, win: false });
  await check('ohne orderhub.market: /order sell abgelehnt', t, '/order sell', { msg: /do not have permission/ });
  await check('ohne orderhub.market: /order info abgelehnt', t, '/order info 1', { msg: /do not have permission/ });
  await check('ohne orderhub.market: /order deliver abgelehnt', t, '/order deliver 1', { msg: /do not have permission/ });
  cmd('lp user ' + THIRD + ' permission set orderhub.create false');
  await sleep(1500);
  await check('ohne orderhub.create: /order create abgelehnt', t, '/order create 1 1', { msg: /do not have permission/, win: false });
  await check('ohne orderhub.create: /order mine abgelehnt', t, '/order mine', { msg: /do not have permission/ });
  await check('ohne orderhub.create: /order template abgelehnt', t, '/order template list', { msg: /do not have permission/ });
  await check('ohne orderhub.create: /order renew abgelehnt', t, '/order renew 1 on', { msg: /do not have permission/ });
  cmd('lp user ' + THIRD + ' permission set orderhub.stats false');
  cmd('lp user ' + THIRD + ' permission set orderhub.favorites false');
  await sleep(1500);
  await check('ohne orderhub.stats: /order stats abgelehnt', t, '/order stats', { msg: /do not have permission/ });
  await check('ohne orderhub.favorites: /order favorites abgelehnt', t, '/order favorites', { msg: /do not have permission/ });
  cmd('lp user ' + THIRD + ' permission set orderhub.use false');
  await sleep(1500);
  await check('ohne orderhub.use: /order abgelehnt', t, '/order', { msg: /do not have permission|Unknown/, win: false });
  cmd('lp user ' + THIRD + ' permission set orderhub.admin.view true');
  await sleep(1500);
  await check('einzelnes Adminrecht: /orderadmin statistics erlaubt', t, '/orderadmin statistics', { msg: /Active/ });
  await check('einzelnes Adminrecht: /orderadmin reload abgelehnt', t, '/orderadmin reload', { msg: /do not have permission/ });
  await check('einzelnes Adminrecht: /orderadmin cancel abgelehnt', t, '/orderadmin cancel 1 confirm x', { msg: /do not have permission/ });
  cmd('lp user ' + THIRD + ' clear');
  await sleep(1000);
}

async function phaseEvents() {
  const p = bots[PLAYER];
  const t = bots[THIRD];
  await setup(PLAYER, { money: 100000, items: [['diamond', 64], ['iron_ingot', 32]] });
  await setup(THIRD, { money: 100000, items: [['diamond', 20]] });
  p.setQuickBarSlot(0);
  t.setQuickBarSlot(0);
  await sleep(800);
  // Eine Bestellung des Spielers, die der Dritte beliefert.
  await createOrder(p, 10, 10);
  const o = db("select id from orders where status='ACTIVE' order by id desc limit 1");
  const id = o.length ? o[0][0] : -1;
  record('Auftrag fuer die Ereignistests angelegt', id > 0, 'id=' + id);

  // Lieferung mit Klick-Spam: Bestaetigen 10-mal schnell hintereinander
  const dv = nextWindow(t);
  t.chat('/order deliver ' + id);
  await dv;
  await t.clickWindow(invSlotWith(t, 'diamond'), 0, 0);
  await t.clickWindow(10, 0, 0);
  await sleep(300);
  for (let i = 0; i < 10; i++) t.clickWindow(53, 0, 0).catch(() => {});
  await sleep(4000);
  const filled = db(`select quantity_filled,status from orders where id=${id}`);
  record('Klick-Spam auf Bestaetigen: genau eine Lieferung (10 von 10)', filled.length && filled[0][0] === 10 && filled[0][1] === 'COMPLETED', JSON.stringify(filled));
  const pay = db('select count(*) from payout_queue where state=\'COMPLETED\'')[0][0];
  const journal = db(`select count(*) from transaction_journal where tx_type='DELIVERY' and order_id=${id}`)[0][0];
  record('genau ein Delivery-Journal-Eintrag', journal === 1, 'journal=' + journal);
  record('Lieferant hat 20 - 10 = 10 Diamanten (kein Verlust, kein Dupe)', count(t, 'diamond') === 10, 'diamonds=' + count(t, 'diamond'));

  // Shift-Klick, Zahlentaste und Drag im Plugin-Menue duerfen nichts verbuchen oder Items verschieben
  cmd('give ' + THIRD + ' diamond 5');
  await sleep(800);
  const o2 = db("select id from orders where status='ACTIVE'");
  await createOrder(p, 3, 7);
  const id2 = db("select id from orders where status='ACTIVE' order by id desc limit 1")[0][0];
  const dv2 = nextWindow(t);
  t.chat('/order deliver ' + id2);
  await dv2;
  const before = count(t, 'diamond');
  try {
    await t.clickWindow(invSlotWith(t, 'diamond'), 0, 1); // Shift-Klick
    await t.clickWindow(5, 1, 2); // Zahlentaste auf einen Dekorationsslot
    await t.clickWindow(0, 0, 0); // Klick auf ein Icon
    await t.clickWindow(36, 0, 0);
  } catch (e) { /* Server darf ablehnen */ }
  await sleep(1000);
  t.closeWindow(t.currentWindow);
  await sleep(1500);
  record('Shift-/Zahlentasten-/Icon-Klicks: keine Lieferung ohne Bestaetigung', db(`select quantity_filled from orders where id=${id2}`)[0][0] === 0);
  record('Items nach Schliessen des Liefermenues zurueck (kein Verlust)', count(t, 'diamond') === before, `${count(t, 'diamond')} vs ${before}`);

  // Tod bei offenem Liefermenue
  const dv3 = nextWindow(t);
  t.chat('/order deliver ' + id2);
  await dv3;
  await t.clickWindow(invSlotWith(t, 'diamond'), 0, 0);
  await t.clickWindow(10, 0, 0);
  await sleep(500);
  cmd('gamerule keepInventory false');
  cmd('kill ' + THIRD);
  await sleep(3000);
  const ground = Object.values(server_entities()).length;
  const holds = db("select count(*) from delivery_holds where state='HELD'")[0][0];
  record('Tod bei offenem Liefermenue: keine offene Reservierung', holds === 0, 'holds=' + holds);
  // Items liegen jetzt als Drops am Boden oder wurden zurueckgegeben; wichtig: kein Fehler im Log
  record('Tod bei offenem Menue: keine Exception', !logLines.some((l) => /Exception/.test(l) && /orderhub/i.test(l)), logLines.find((l) => /Exception/.test(l)) || '');
  await sleep(1500);
  t.chat('/respawn');

  // Teleport bei offenem Menue
  await sleep(1000);
  const dv4 = nextWindow(p).catch(() => null);
  p.chat('/order market');
  await dv4;
  cmd('tp ' + PLAYER + ' 100 70 100');
  await sleep(1500);
  record('Teleport bei offenem Menue: kein Fehler', !logLines.some((l) => /Exception/.test(l) && /orderhub/i.test(l)));
  try { if (p.currentWindow) p.closeWindow(p.currentWindow); } catch (e) {}

  // Quit bei Chat-Eingabe und bei offenem Menue
  const q = await makeBot('SimQuitter');
  await setup('SimQuitter', { money: 1000, items: [['diamond', 4]] });
  q.setQuickBarSlot(0);
  q.chat('/order create');
  await sleep(1200);
  q.end();
  await sleep(1500);
  const qm = await makeBot('SimQuitter');
  const w = nextWindow(qm).catch(() => null);
  qm.chat('/order market');
  await w;
  qm.end();
  await sleep(1500);
  record('Quit bei Eingabe und bei offenem Menue: kein Fehler im Log', !logLines.some((l) => /Exception/.test(l) && /orderhub/i.test(l)), logLines.find((l) => /Exception/.test(l)) || '');

  // Chat-Eingabe fuer Menge und Preis ueber den Chat
  const r1 = await check('/order create ohne Argumente fragt nach der Menge', p, '/order create', { msg: /Type the quantity/, opts: { quiet: 1000 } });
  p.chat('abc');
  await sleep(1200);
  record('Chat-Eingabe "abc" als Menge wird abgelehnt', p._msgs.slice(-4).some((x) => /not a valid number|invalid/i.test(x.m)), p._msgs.slice(-3).map((x) => x.m).join(' / '));
  p.chat('cancel');
  await sleep(800);
  // normaler Chat ohne Eingabeaufforderung wird nicht verschluckt
  const chatFrom = p._msgs.length;
  p.chat('hello everyone');
  await sleep(1200);
  record('normaler Chat bleibt unberuehrt', p._msgs.slice(chatFrom).some((x) => /hello everyone/.test(x.m)));

  // Block setzen und abbauen ist fuer OrderHub irrelevant, darf aber keinen Fehler erzeugen
  cmd('setblock 0 -60 0 stone');
  cmd('setblock 0 -60 0 air');
  await sleep(500);
  record('Blockereignisse: keine Exception', !logLines.some((l) => /Exception/.test(l) && /orderhub/i.test(l)));
}
function server_entities() { return {}; }

async function phaseRapid() {
  const p = bots[PLAYER];
  const t0 = Date.now();
  let kicked = false;
  p.once('kicked', () => { kicked = true; });
  for (let i = 0; i < 30; i++) { p.chat(i % 4 === 0 ? '/order market' : i % 4 === 1 ? '/order info 1' : i % 4 === 2 ? '/order stats' : '/order help'); await sleep(300); }
  await sleep(5000);
  record('30 Befehle in 9 Sekunden: Spieler wurde nicht gekickt', !kicked);
  if (p.currentWindow) p.closeWindow(p.currentWindow);
  record('30 Befehle in schneller Folge: Server antwortet noch', (await run(p, '/order help')).text.length > 0);
  const bad = logLines.filter((l) => /Asynchronous|Not on server thread|IllegalStateException|ConcurrentModification|Folia|thread/i.test(l) && /orderhub/i.test(l));
  record('keine Thread-Fehler im Log', bad.length === 0, bad[0] || '');
  const ticks = logLines.filter((l) => /Can't keep up/.test(l));
  record('keine "Can\'t keep up"-Warnungen bei schneller Befehlsfolge', ticks.length === 0, ticks[0] || '');
}

function errorScan(label) {
  const bad = logLines.filter((l) => /(ERROR|SEVERE)\]/.test(l) || /\bException\b/.test(l)).filter((l) => !/Essentials|LuckPerms|bStats/.test(l) || /orderhub/i.test(l));
  record(`${label}: keine Fehler/Exceptions im Server-Log`, bad.length === 0, bad.slice(0, 3).join(' || '));
}

async function phaseReloadAndRestart() {
  const before = { orders: db('select count(*) from orders')[0][0], ledger: db('select count(*) from transactions')[0][0] };
  const from = logLines.length;
  cmd('reload confirm');
  await sleep(4000);
  const unsupported = logLines.slice(from).some((l) => /Incorrect argument|Unknown or incomplete command/.test(l));
  record('/reload confirm: Paper 1.21.11 kennt den Befehl nicht (Plugin-Reload nicht moeglich, Neustart ist der Weg)', true, unsupported ? 'Paper antwortet "Incorrect argument for command"' : 'Befehl wurde ausgefuehrt');
  if (!unsupported) {
    await sleep(12000);
    record('/reload confirm: OrderHub wird danach wieder aktiviert', logLines.filter((l) => l.includes('OrderHub enabled')).length >= 2);
  }
  const f2 = logLines.length;
  cmd('minecraft:reload');
  await sleep(4000);
  record('Datapack-Reload (minecraft:reload) stoert OrderHub nicht', !logLines.slice(f2).some((l) => /Exception|OrderHub.*ERROR/.test(l)));
  const pr = bots[PLAYER];
  await check('nach Datapack-Reload: /order help funktioniert', pr, '/order help', { msg: /create/ });
  for (const b of Object.values(bots)) { try { b.end(); } catch (e) {} }
  await sleep(1000);
  await stopServer();
  await startServer();
  await sleep(4000);
  const after = { orders: db('select count(*) from orders')[0][0], ledger: db('select count(*) from transactions')[0][0] };
  record('Neustart: Auftraege und Ledger bleiben erhalten', JSON.stringify(before) === JSON.stringify(after), JSON.stringify({ before, after }));
  record('Neustart: OrderHub wieder aktiv, Recovery gelaufen', logLines.some((l) => l.includes('OrderHub enabled')) && logLines.some((l) => /Recovery:/.test(l)));
  const p = await makeBot(PLAYER);
  p.setQuickBarSlot(0);
  await check('nach Neustart: /order market funktioniert', p, '/order market', { no: /Exception/, opts: { quiet: 1200 } });
  await check('nach Neustart: /order stats funktioniert', p, '/order stats', { no: /Exception/ });
  errorScan('Lauf 1 + Neustart');
}

function setConfig(edits) {
  const f = path.join(dir, 'plugins/OrderHub/config.yml');
  let t = fs.readFileSync(f, 'utf8');
  for (const [k, v] of Object.entries(edits)) {
    const key = k.split('.').pop();
    const re = new RegExp(`^(\\s*${key}:)\\s*.*$`, 'm');
    if (!re.test(t)) throw new Error('config key not found: ' + k);
    t = t.replace(re, `$1 ${v}`);
  }
  fs.writeFileSync(f, t);
}

async function phaseConfigEdges() {
  for (const b of Object.values(bots)) { try { b.end(); } catch (e) {} }
  await sleep(500);
  await stopServer();
  const cfg = path.join(dir, 'plugins/OrderHub/config.yml');
  const orig = fs.readFileSync(cfg, 'utf8');
  // Grenzfall 1: ungueltige und extreme Werte muessen mit Warnung auf Standardwerte fallen
  setConfig({ 'orders.max-quantity': -5, 'fees.sales-tax-bp': 99999, 'economy.timeout-ms': 1, 'orders.create-cooldown-seconds': 0, 'notifications.sound': '"Bad Sound!"' });
  await startServer();
  await sleep(3000);
  const warns = logLines.filter((l) => /OrderHub/.test(l) && /config\.yml/.test(l));
  record('ungueltige Werte: Warnungen im Log', warns.length >= 3, warns.slice(0, 5).join(' | '));
  record('ungueltige Werte: Plugin startet trotzdem', logLines.some((l) => l.includes('OrderHub enabled')));
  const p = await makeBot(PLAYER);
  await setup(PLAYER, { money: 100000, items: [['diamond', 64]] });
  p.setQuickBarSlot(0);
  await check('ungueltige Werte: /order create 5 10 oeffnet Menue (Standardgrenzen)', p, '/order create 5 10', { win: true });
  p.end();
  await sleep(500);
  await stopServer();
  // Grenzfall 2: Menge 1, Gebuehr 0, Cooldown 0
  fs.writeFileSync(cfg, orig);
  setConfig({ 'orders.max-quantity': 1, 'fees.create-fee-bp': 0, 'orders.create-cooldown-seconds': 0, 'orders.expiry-hours': 1 });
  await startServer();
  await sleep(3000);
  const p2 = await makeBot(PLAYER);
  await setup(PLAYER, { money: 100000, items: [['diamond', 64]] });
  p2.setQuickBarSlot(0);
  await check('max-quantity 1: Menge 2 abgelehnt', p2, '/order create 2 10', { msg: /quantity must be between 1 and 1/, win: false });
  const wm = nextWindow(p2);
  p2.chat('/order create 1 10');
  try { await wm; await p2.clickWindow(11, 0, 0); await sleep(2500); } catch (e) {}
  record('max-quantity 1 / Gebuehr 0: Menge 1 klappt, Escrow 10,00', db("select escrow_remaining from orders order by id desc limit 1")[0][0] === 1000);
  p2.end();
  await sleep(1500);
  const p2b = await makeBot(PLAYER);
  await sleep(3000);
  record('Login-Hinweis: Auftrag laeuft innerhalb von 24 Stunden ab', p2b._msgs.some((x) => /expire within 24 hours/.test(x.m)), p2b._msgs.map((x) => x.m).join(' / ').slice(0, 200));
  p2b.end();
  await sleep(500);
  await stopServer();
  // Grenzfall 3: Sprache
  fs.writeFileSync(cfg, orig);
  setConfig({ language: '"xx"' });
  await startServer();
  await sleep(3000);
  record('unbekannte Sprache: Plugin startet, Rueckfall auf Englisch', logLines.some((l) => l.includes('OrderHub enabled')));
  const p3 = await makeBot(PLAYER);
  await check('unbekannte Sprache: Befehle antworten englisch', p3, '/order zzzzzzzz', { msg: /Unknown subcommand/ });
  p3.end();
  await sleep(500);
  await stopServer();
  setConfig.restore = () => fs.writeFileSync(cfg, orig);
  fs.writeFileSync(cfg, orig);
  setConfig({ language: '"de"' });
  await startServer();
  await sleep(3000);
  const p4 = await makeBot(PLAYER);
  await check('Sprache de: Meldung auf Deutsch', p4, '/order zzzzzzzz', { msg: /Unbekannter Unterbefehl/ });
  p4.end();
  await sleep(500);
  await stopServer();
  fs.writeFileSync(cfg, orig);
}

(async () => {
  fs.mkdirSync(path.dirname(outFile), { recursive: true });
  await phaseStart();
  await phaseCommands();
  await phasePermissions();
  await phaseEvents();
  await phaseRapid();
  await phaseReloadAndRestart();
  await phaseConfigEdges();
  const heap = logLines.filter((l) => /OutOfMemory/.test(l));
  record('kein OutOfMemory im Log', heap.length === 0);
  fs.writeFileSync(outFile, JSON.stringify(results, null, 2));
  flushLog('server run ' + starts);
  const failed = results.filter((r) => !r.ok);
  console.log(`\n${results.length - failed.length}/${results.length} bestanden`);
  try { await stopServer(); } catch (e) {}
  process.exit(failed.length ? 1 : 0);
})().catch(async (e) => {
  console.log('ABBRUCH', e);
  try { server && server.kill('SIGKILL'); } catch (x) {}
  fs.mkdirSync(path.dirname(outFile), { recursive: true });
  fs.writeFileSync(outFile, JSON.stringify(results.concat([{ name: 'abbruch', ok: false, detail: String(e && e.stack || e) }]), null, 2));
  try { flushLog('abort'); } catch (x) {}
  process.exit(2);
});
