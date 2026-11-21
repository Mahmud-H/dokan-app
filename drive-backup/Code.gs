// দামের খাতা: Google Drive backup + two-phone sync  (version 2)
// Keeps one shared copy of the shop's records in your Drive ("Dokan Backup" folder),
// merges changes from every phone that has the shop code, and saves one dated backup per day.

const FOLDER_NAME = "Dokan Backup";
const MASTER_NAME = "dokan-master.json";
const KEEP = 30; // how many daily backup files to keep

const SYNC_COLS = ["items", "sales", "customers", "loans", "expenses", "services", "suppliers", "shop", "trips", "dayAdj"];
const SYNC_MAPS = ["cash"];

function mergeRec(a, b) {
  if (!a) return b;
  if (!b) return a;
  const win = (b.u || 0) > (a.u || 0) ? b : a, r = Object.assign({}, win);
  ["entries", "payments"].forEach(k => { if (a[k] || b[k]) r[k] = mergeArr(a[k] || [], b[k] || []); });
  return r;
}
function mergeArr(x, y) {
  const m = new Map();
  x.forEach(e => m.set(e.id, e));
  y.forEach(e => m.set(e.id, m.has(e.id) ? mergeRec(m.get(e.id), e) : e));
  return [...m.values()].sort((p, q) => (p.t || 0) - (q.t || 0));
}
function dropTombNested(r, tomb) {
  ["entries", "payments"].forEach(k => { if (Array.isArray(r[k])) r[k] = r[k].filter(e => !tomb[e.id]); });
  return r;
}
const plain = r => { const c = Object.assign({}, r); delete c.su; return JSON.stringify(c); };

function doPost(e) {
  const lock = LockService.getScriptLock();
  lock.waitLock(25000);
  try {
    const body = JSON.parse(e.postData.contents);
    const props = PropertiesService.getScriptProperties();
    const saved = props.getProperty("KEY");
    if (!body.key) return out({ ok: false, err: "nokey" });
    if (!saved) props.setProperty("KEY", body.key);
    else if (saved !== body.key) return out({ ok: false, err: "key" });

    if (body.op === "sync") return out(sync(body));

    // version-1 style: a full backup file sent from the phone
    const folder = getFolder();
    folder.createFile(stamp("dokan-backup-"), JSON.stringify(body.data), "application/json");
    prune(folder);
    return out({ ok: true });
  } catch (err) {
    return out({ ok: false, err: String(err) });
  } finally {
    lock.releaseLock();
  }
}

function sync(body) {
  const now = Date.now(), since = body.since || 0, ch = body.changes || {};
  const folder = getFolder(), master = loadMaster(folder);

  // deletions
  Object.entries(ch.tomb || {}).forEach(([id, t]) => {
    if (!master.tomb[id]) master.tomb[id] = { t: t, su: now };
    SYNC_COLS.forEach(c => {
      const col = master.cols[c];
      if (col[id]) delete col[id];
      if (c === "customers" || c === "loans") Object.values(col).forEach(r => {
        const before = plain(r); dropTombNested(r, { [id]: 1 });
        if (plain(r) !== before) r.su = now;
      });
    });
  });
  const tombIds = {};
  Object.keys(master.tomb).forEach(id => tombIds[id] = 1);

  // new and changed records
  SYNC_COLS.forEach(c => (((ch.cols || {})[c]) || []).forEach(rec => {
    if (!rec || !rec.id || tombIds[rec.id]) return;
    const old = master.cols[c][rec.id];
    const merged = dropTombNested(mergeRec(old, rec), tombIds);
    if (!old || plain(merged) !== plain(old)) { merged.su = now; master.cols[c][rec.id] = merged; }
  }));
  SYNC_MAPS.forEach(m => Object.entries(((ch.maps || {})[m]) || {}).forEach(([k, rec]) => {
    const old = master.maps[m][k], merged = mergeRec(old, rec);
    if (!old || plain(merged) !== plain(old)) { merged.su = now; master.maps[m][k] = merged; }
  }));

  // what this phone has not seen yet
  const res = { tomb: {}, cols: {}, maps: {} };
  Object.entries(master.tomb).forEach(([id, x]) => { if (x.su > since) res.tomb[id] = x.t; });
  SYNC_COLS.forEach(c => { const r = Object.values(master.cols[c]).filter(x => (x.su || 0) > since); if (r.length) res.cols[c] = r; });
  SYNC_MAPS.forEach(m => { const r = {}; Object.entries(master.maps[m]).forEach(([k, x]) => { if ((x.su || 0) > since) r[k] = x; }); if (Object.keys(r).length) res.maps[m] = r; });

  saveMaster(folder, master);
  dailyBackup(folder, master);
  return { ok: true, now: now, changes: res };
}

function loadMaster(folder) {
  const it = folder.getFilesByName(MASTER_NAME);
  let m = null;
  if (it.hasNext()) { try { m = JSON.parse(it.next().getBlob().getDataAsString("UTF-8")); } catch (e) { m = null; } }
  m = m || {};
  m.tomb = m.tomb || {}; m.cols = m.cols || {}; m.maps = m.maps || {};
  SYNC_COLS.forEach(c => m.cols[c] = m.cols[c] || {});
  SYNC_MAPS.forEach(x => m.maps[x] = m.maps[x] || {});
  return m;
}
function saveMaster(folder, m) {
  const it = folder.getFilesByName(MASTER_NAME), txt = JSON.stringify(m);
  if (it.hasNext()) it.next().setContent(txt);
  else folder.createFile(MASTER_NAME, txt, "application/json");
}

// One file per day in the app's own backup format, so "Backup থেকে ফেরত আনুন" can read it.
function dailyBackup(folder, m) {
  const name = "dokan-backup-" + Utilities.formatDate(new Date(), "Asia/Dhaka", "yyyy-MM-dd") + ".json";
  const st = { v: 4, tomb: {}, cash: {}, days: {}, prefs: {} };
  SYNC_COLS.forEach(c => st[c] = Object.values(m.cols[c]));
  SYNC_MAPS.forEach(x => st[x] = m.maps[x]);
  Object.entries(m.tomb).forEach(([id, x]) => st.tomb[id] = x.t);
  const txt = JSON.stringify(st), it = folder.getFilesByName(name);
  if (it.hasNext()) it.next().setContent(txt);
  else { folder.createFile(name, txt, "application/json"); prune(folder); }
}

function prune(folder) {
  const files = [], it = folder.getFiles();
  while (it.hasNext()) { const f = it.next(); if (f.getName().indexOf("dokan-backup-") === 0) files.push(f); }
  files.sort((a, b) => b.getDateCreated() - a.getDateCreated());
  files.slice(KEEP).forEach(f => f.setTrashed(true));
}

function doGet() { return out({ ok: true, app: "dokan-sync", version: 2 }); }

// Run this from the editor only if you want to start using a new shop code.
function resetShopCode() { PropertiesService.getScriptProperties().deleteProperty("KEY"); }

function getFolder() {
  const it = DriveApp.getFoldersByName(FOLDER_NAME);
  return it.hasNext() ? it.next() : DriveApp.createFolder(FOLDER_NAME);
}
function stamp(prefix) { return prefix + Utilities.formatDate(new Date(), "Asia/Dhaka", "yyyy-MM-dd_HH-mm") + ".json"; }
function out(o) { return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON); }
