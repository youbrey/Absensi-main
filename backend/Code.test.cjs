const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const crypto = require('node:crypto');
const source = fs.readFileSync(__dirname + '/Code.gs', 'utf8');
function fixture({ failStorage = false, adminAccounts = null } = {}) {
  const rows = [], photos = new Map();
  let locked = false;
  const properties = { SYNC_TOKEN: 'test-secret', SPREADSHEET_ID: 'book', PHOTO_FOLDER_ID: 'photos' };
  if (adminAccounts) properties.ADMIN_ACCOUNTS = JSON.stringify(adminAccounts);
  const cacheStore = {};
  const sheet = {
    getLastRow: () => rows.length,
    appendRow(row) { if (failStorage) throw Error('Storage unavailable'); rows.push(row); },
    getRange(startRow, startCol, numRows, numCols) {
      return {
        createTextFinder(id) { return { matchEntireCell() { return this; }, findNext() { return rows.slice(1).find(r => r[0] === id); } }; },
        getValues() {
          return rows.slice(startRow - 1, startRow - 1 + numRows).map(r => r.slice(startCol - 1, startCol - 1 + numCols));
        }
      };
    }
  };
  const context = vm.createContext({
    LockService: { getScriptLock: () => ({ waitLock() { locked = true; }, hasLock: () => locked, releaseLock() { locked = false; } }) },
    PropertiesService: {
      getScriptProperties: () => ({
        getProperty: key => (key in properties ? properties[key] : null),
        setProperty: (key, value) => { properties[key] = value; }
      })
    },
    CacheService: {
      getScriptCache: () => ({
        get: key => (key in cacheStore ? cacheStore[key] : null),
        put: (key, value) => { cacheStore[key] = String(value); },
        remove: key => { delete cacheStore[key]; }
      })
    },
    SpreadsheetApp: { openById: () => ({ getSheetByName: () => sheet }), flush() {} },
    DriveApp: { getFolderById: () => ({
      getFilesByName: name => ({ hasNext: () => photos.has(name), next: () => photos.get(name) }),
      createFile(blob) { const file = { getUrl: () => 'https://drive.example/' + blob.name }; photos.set(blob.name, file); return file; }
    }) },
    Utilities: {
      base64Decode: str => Buffer.from(str, 'base64'),
      newBlob: (bytes, mime, name) => ({ bytes, mime, name }),
      computeDigest: (_algo, bytes) => {
        const digest = crypto.createHash('sha256').update(Buffer.from(bytes)).digest();
        return Array.from(digest).map(b => (b > 127 ? b - 256 : b));
      },
      DigestAlgorithm: { SHA_256: 'SHA_256' }
    },
    ContentService: { MimeType: { JSON: 'json' }, createTextOutput: content => ({ content, setMimeType() { return this; } }) }
  });
  vm.runInContext(source, context);
  const payload = { token: 'test-secret', recordId: 'a'.repeat(64), namaLengkap: 'Test', nip: '198001012000011001',
    jabatan: '=DANGEROUS()', jenisAbsensi: 'ABSENSI MASUK', timestamp: Date.now() - 60000, latitude: 1.44, longitude: 125.18,
    foto: Buffer.from('jpeg').toString('base64'), jamMasuk: '07:00:00', jamPulang: '-', tanggal: '11 September 2026' };
  const post = data => JSON.parse(context.doPost({ postData: { contents: JSON.stringify(data) } }).content);
  const get = (token, extraParams) => JSON.parse(context.doGet({ parameter: Object.assign({ token: token }, extraParams) }).content);
  const adminLogin = (nip, password) => post({ action: 'adminLogin', nip: nip, password: password });
  // Strips the always-present, non-deterministic serverTime field so callers can deepEqual
  // the rest of a response; separately asserts it looks like a real, recent timestamp.
  const withoutServerTime = (result) => {
    const { serverTime, ...rest } = result;
    assert.equal(typeof serverTime, 'number');
    assert.ok(Math.abs(serverTime - Date.now()) < 5000, 'serverTime should be close to real time');
    return rest;
  };
  return { rows, photos, payload, post, get, adminLogin, withoutServerTime, properties, cacheStore, isLocked: () => locked, context };
}
test('persist real photo, preserve NIP, escape formulas, and acknowledge matching ID', () => {
  const f = fixture();
  assert.deepEqual(f.withoutServerTime(f.post(f.payload)), { success: true, recordId: f.payload.recordId });
  assert.equal(f.rows.length, 2);
  assert.equal(f.rows[1][2], "'198001012000011001");
  assert.equal(f.rows[1][3], "'=DANGEROUS()");
  assert.equal(f.photos.size, 1);
  assert.equal(f.isLocked(), false);
});
test('retry does not duplicate rows or photo files', () => {
  const f = fixture(); f.post(f.payload); f.post(f.payload);
  assert.equal(f.rows.length, 2); assert.equal(f.photos.size, 1);
});
test('wrong token cannot write any data', () => {
  const f = fixture(); assert.equal(f.post({ ...f.payload, token: 'wrong' }).success, false);
  assert.equal(f.rows.length, 0); assert.equal(f.photos.size, 0);
});
test('invalid payload cannot be acknowledged', () => {
  const f = fixture();
  for (const patch of [{ recordId: '' }, { jenisAbsensi: 'OTHER' }, { latitude: 100 }, { timestamp: null }, { foto: 'x'.repeat(4000001) }]) {
    assert.equal(f.post({ ...f.payload, ...patch }).success, false);
  }
  assert.equal(f.rows.length, 0);
});

// ---- Timestamp sanity bound (defense in depth only -- see the comment above the constants
// in Code.gs for why this deliberately does NOT re-enforce the MASUK/PULANG time-of-day
// window; that would break the admin's legitimate FORCE_OPEN override). --------------------

test('a timestamp from decades ago (garbage/corrupted data) is rejected', () => {
  const f = fixture();
  const result = f.post({ ...f.payload, timestamp: 12345 }); // 1970-01-01
  assert.equal(result.success, false);
  assert.equal(f.rows.length, 0);
});

test('a timestamp far in the future is rejected', () => {
  const f = fixture();
  const result = f.post({ ...f.payload, timestamp: Date.now() + 365 * 24 * 60 * 60 * 1000 });
  assert.equal(result.success, false);
  assert.equal(f.rows.length, 0);
});

test('a legitimately delayed offline sync (captured hours ago) is still accepted', () => {
  const f = fixture();
  const result = f.post({ ...f.payload, timestamp: Date.now() - 6 * 60 * 60 * 1000 }); // 6h ago
  assert.equal(result.success, true);
});

test('a timestamp outside the MASUK/PULANG hours is still accepted (FORCE_OPEN is a client-side admin override the server cannot verify)', () => {
  const f = fixture();
  // 15:00 WITA is outside both attendance windows, but the server does not reject on that
  // basis alone -- see the comment above ATTENDANCE_TIMESTAMP_FUTURE_SLACK_MS in Code.gs.
  const threePmWita = new Date();
  threePmWita.setUTCHours(15 - 8, 0, 0, 0); // WITA = UTC+8
  const result = f.post({ ...f.payload, timestamp: threePmWita.getTime() });
  assert.equal(result.success, true);
});
test('storage error is negative and releases lock', () => {
  const f = fixture({ failStorage: true }); assert.equal(f.post(f.payload).success, false);
  assert.equal(f.isLocked(), false);
});
test('malformed JSON produces a negative response', () => {
  const f = fixture();
  assert.equal(JSON.parse(f.context.doPost({ postData: { contents: '{' } }).content).success, false);
});
test('doGet with wrong or missing token cannot read any data', () => {
  const f = fixture(); f.post(f.payload);
  assert.equal(f.get('wrong').success, false);
  assert.equal(f.get(undefined).success, false);
});
test('doGet with correct token returns the combined recap without leaking the token', () => {
  const f = fixture(); f.post(f.payload);
  const result = f.get('test-secret');
  assert.equal(result.success, true);
  assert.equal(result.records.length, 1);
  const record = result.records[0];
  assert.equal(record.recordId, f.payload.recordId);
  assert.equal(record.nip, f.payload.nip);
  assert.equal(record.jabatan, f.payload.jabatan);
  assert.equal(JSON.stringify(result).indexOf('test-secret'), -1);
});
test('doGet on an empty sheet returns an empty recap, not an error', () => {
  const f = fixture();
  assert.deepEqual(f.withoutServerTime(f.get('test-secret')), { success: true, records: [] });
});

test('doGet ?action=time returns the server clock without needing a token', () => {
  const f = fixture();
  const result = f.get(undefined, { action: 'time' });
  assert.equal(result.success, true);
  assert.ok(Math.abs(result.serverTime - Date.now()) < 5000);
});

// ---- Admin login (server-verified, multi-admin) ------------------------------------------

test('a fresh deployment with no ADMIN_ACCOUNTS rejects every login -- no bootstrap path', () => {
  const f = fixture();
  const result = f.adminLogin('196501011990031001', 'whatever-password');
  assert.equal(result.success, false);
  assert.equal(f.properties.ADMIN_ACCOUNTS, undefined); // login never creates the property
});

test('provisioning via PropertiesService + hashAdminPassword (what setupAdminAccount does) enables login', () => {
  const f = fixture();
  // Exercises the exact same hashing/storage path setupAdminAccount() uses internally,
  // without needing to edit that function's hardcoded placeholder constants for this test.
  const props = f.context.PropertiesService.getScriptProperties();
  props.setProperty('ADMIN_ACCOUNTS', JSON.stringify([
    { nip: '196501011990031001', name: 'Budi (IT)', hash: f.context.hashAdminPassword('kata-sandi-kuat-1') }
  ]));

  const ok = f.adminLogin('196501011990031001', 'kata-sandi-kuat-1');
  assert.deepEqual(f.withoutServerTime(ok), { success: true, nip: '196501011990031001', name: 'Budi (IT)' });

  const wrongPassword = f.adminLogin('196501011990031001', 'salah-password');
  assert.equal(wrongPassword.success, false);

  const unknownNip = f.adminLogin('000000000000000000', 'kata-sandi-kuat-1');
  assert.equal(unknownNip.success, false);
});

test('setupAdminAccount refuses to run with its placeholder NIP untouched', () => {
  const f = fixture();
  assert.throws(() => f.context.setupAdminAccount());
});

test('multiple admins can be configured server-side and each logs in independently', () => {
  const f = fixture();
  const hashOne = f.context.hashAdminPassword('password-satu');
  const hashTwo = f.context.hashAdminPassword('password-dua');
  f.properties.ADMIN_ACCOUNTS = JSON.stringify([
    { nip: '111', name: 'Admin Satu', hash: hashOne },
    { nip: '222', name: 'Admin Dua', hash: hashTwo }
  ]);

  assert.equal(f.adminLogin('111', 'password-satu').success, true);
  assert.equal(f.adminLogin('222', 'password-dua').success, true);
  assert.equal(f.adminLogin('222', 'wrong').success, false);
  assert.equal(f.adminLogin('111', 'password-satu-salah').success, false);
  // One admin's password never unlocks another admin's account.
  assert.equal(f.adminLogin('111', 'password-dua').success, false);
});

test('admin login never returns or leaks the stored hash', () => {
  const f = fixture();
  const hash = f.context.hashAdminPassword('kata-sandi-kuat-1');
  f.properties.ADMIN_ACCOUNTS = JSON.stringify([{ nip: '111', name: 'Admin', hash: hash }]);
  const result = f.adminLogin('111', 'kata-sandi-kuat-1');
  assert.equal(JSON.stringify(result).indexOf(hash.split(':')[3]), -1);
});

test('repeated failed attempts for one NIP lock out that NIP but not others', () => {
  const f = fixture();
  const hash = f.context.hashAdminPassword('kata-sandi-kuat-1');
  f.properties.ADMIN_ACCOUNTS = JSON.stringify([
    { nip: '111', name: 'Admin', hash: hash },
    { nip: '222', name: 'Admin Lain', hash: hash }
  ]);
  for (let i = 0; i < 5; i++) assert.equal(f.adminLogin('111', 'salah').success, false);
  const stillLocked = f.adminLogin('111', 'kata-sandi-kuat-1'); // correct password, but now locked out
  assert.equal(stillLocked.success, false);
  assert.match(stillLocked.error, /percobaan/);
  // A different NIP is not affected by NIP-111's lockout.
  assert.equal(f.adminLogin('222', 'kata-sandi-kuat-1').success, true);
});

test('attendance sync (doPost with a token) is unaffected by the adminLogin branch', () => {
  const f = fixture();
  assert.deepEqual(f.withoutServerTime(f.post(f.payload)), { success: true, recordId: f.payload.recordId });
});
