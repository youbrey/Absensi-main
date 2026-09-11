const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(__dirname + '/Code.gs', 'utf8');
function fixture({ failStorage = false } = {}) {
  const rows = [], photos = new Map();
  let locked = false;
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
    PropertiesService: { getScriptProperties: () => ({ getProperty: key => ({ SYNC_TOKEN: 'test-secret', SPREADSHEET_ID: 'book', PHOTO_FOLDER_ID: 'photos' })[key] }) },
    SpreadsheetApp: { openById: () => ({ getSheetByName: () => sheet }), flush() {} },
    DriveApp: { getFolderById: () => ({
      getFilesByName: name => ({ hasNext: () => photos.has(name), next: () => photos.get(name) }),
      createFile(blob) { const file = { getUrl: () => 'https://drive.example/' + blob.name }; photos.set(blob.name, file); return file; }
    }) },
    Utilities: { base64Decode: str => Buffer.from(str, 'base64'), newBlob: (bytes, mime, name) => ({ bytes, mime, name }) },
    ContentService: { MimeType: { JSON: 'json' }, createTextOutput: content => ({ content, setMimeType() { return this; } }) }
  });
  vm.runInContext(source, context);
  const payload = { token: 'test-secret', recordId: 'a'.repeat(64), namaLengkap: 'Test', nip: '198001012000011001',
    jabatan: '=DANGEROUS()', jenisAbsensi: 'ABSENSI MASUK', timestamp: 12345, latitude: 1.44, longitude: 125.18,
    foto: Buffer.from('jpeg').toString('base64'), jamMasuk: '07:00:00', jamPulang: '-', tanggal: '11 September 2026' };
  const post = data => JSON.parse(context.doPost({ postData: { contents: JSON.stringify(data) } }).content);
  const get = (token) => JSON.parse(context.doGet({ parameter: { token: token } }).content);
  return { rows, photos, payload, post, get, isLocked: () => locked, context };
}
test('persist real photo, preserve NIP, escape formulas, and acknowledge matching ID', () => {
  const f = fixture();
  assert.deepEqual(f.post(f.payload), { success: true, recordId: f.payload.recordId });
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
  assert.deepEqual(f.get('test-secret'), { success: true, records: [] });
});
