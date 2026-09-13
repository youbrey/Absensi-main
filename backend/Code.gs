/** Apps Script receiver. Configure Script Properties before deploying; see docs/SETUP.md. */

// ---- Admin login tuning (see "Admin login (server-verified)" section near the bottom) -------
var ADMIN_HASH_ITERATIONS_DEFAULT = 50000;
var ADMIN_LOGIN_MAX_FAILS_PER_NIP = 5;
var ADMIN_LOGIN_LOCK_SECONDS_PER_NIP = 300;
var ADMIN_LOGIN_MAX_FAILS_GLOBAL = 20;
var ADMIN_LOGIN_LOCK_SECONDS_GLOBAL = 600;
var ADMIN_LOGIN_FAIL_WINDOW_SECONDS = 900;

// ---- Attendance timestamp sanity bound (see "Trusted time" notes below doPost) ---------------
// This is a loose backstop against garbage/corrupted data, NOT a MASUK/PULANG window check:
// the server deliberately does not re-enforce the 07-09 / 12-14 WITA window here, because a
// device an admin has switched to FORCE_OPEN is *meant* to submit outside those hours, and the
// server has no way to tell that mode apart from a spoofed one. The real fix for device clock
// tampering lives on the Android side (see util/TrustedTime.kt): the app no longer trusts
// System.currentTimeMillis() for the window check or for what gets recorded, so a device whose
// date/time has been changed manually can no longer fool its own local window check. What is
// still worth catching here is a timestamp so far off it can only be corrupted data or a raw,
// hand-crafted request -- not a legitimately delayed offline sync (which can be hours or days
// late and must still be accepted with its original, correctly-anchored capture time).
var ATTENDANCE_TIMESTAMP_FUTURE_SLACK_MS = 5 * 60 * 1000; // 5 minutes of clock/latency slop
var ATTENDANCE_TIMESTAMP_MAX_AGE_MS = 30 * 24 * 60 * 60 * 1000; // 30 days

function doPost(e) {
  var lock = LockService.getScriptLock();
  try {
    var data = JSON.parse(e.postData.contents);
    // Admin login has its own credential (NIP+password checked against Script Properties)
    // and does not touch the spreadsheet, so it is handled before the SYNC_TOKEN/lock logic
    // below, which exists only for the attendance-sync payload.
    if (data && data.action === 'adminLogin') return handleAdminLogin(data);
    var props = PropertiesService.getScriptProperties();
    var token = props.getProperty('SYNC_TOKEN');
    if (!token || data.token !== token) return reply({ success: false, error: 'Unauthorized' });
    if (!/^[a-f0-9]{64}$/.test(data.recordId || '') || !data.nip || !data.namaLengkap ||
        !Number.isFinite(data.timestamp) || data.timestamp <= 0 ||
        data.timestamp > Date.now() + ATTENDANCE_TIMESTAMP_FUTURE_SLACK_MS ||
        data.timestamp < Date.now() - ATTENDANCE_TIMESTAMP_MAX_AGE_MS ||
        ['ABSENSI MASUK', 'ABSENSI PULANG'].indexOf(data.jenisAbsensi) < 0 ||
        !Number.isFinite(data.latitude) || !Number.isFinite(data.longitude) ||
        Math.abs(data.latitude) > 90 || Math.abs(data.longitude) > 180 ||
        typeof data.foto !== 'string' || data.foto.length > 4000000) {
      return reply({ success: false, error: 'Invalid payload' });
    }
    lock.waitLock(20000);
    var book = SpreadsheetApp.openById(props.getProperty('SPREADSHEET_ID'));
    var sheet = book.getSheetByName('Absensi') || book.insertSheet('Absensi');
    if (sheet.getLastRow() === 0) {
      sheet.appendRow(['RECORD ID', 'NAMA LENGKAP', 'NIP', 'JABATAN', 'JENIS ABSENSI',
        'JAM MASUK', 'JAM PULANG', 'TANGGAL', 'FOTO', 'LATITUDE', 'LONGITUDE', 'TIMESTAMP']);
    }
    var count = sheet.getLastRow();
    var exists = count > 1 && sheet.getRange(2, 1, count - 1, 1).createTextFinder(data.recordId)
      .matchEntireCell(true).findNext();
    if (!exists) {
      var photoUrl = '';
      if (data.foto) {
        var folder = DriveApp.getFolderById(props.getProperty('PHOTO_FOLDER_ID'));
        var filename = data.recordId + '.jpg';
        var files = folder.getFilesByName(filename);
        var photo = files.hasNext() ? files.next() : folder.createFile(
          Utilities.newBlob(Utilities.base64Decode(data.foto), 'image/jpeg', filename));
        // Inherit folder permissions; never make employee photos public.
        photoUrl = photo.getUrl();
      }
      sheet.appendRow([data.recordId, sheetText(data.namaLengkap), sheetText(data.nip),
        sheetText(data.jabatan), sheetText(data.jenisAbsensi), sheetText(data.jamMasuk),
        sheetText(data.jamPulang), sheetText(data.tanggal), photoUrl,
        data.latitude, data.longitude, data.timestamp]);
      SpreadsheetApp.flush();
    }
    return reply({ success: true, recordId: data.recordId });
  } catch (err) {
    // Logged here only -- visible to you in the Apps Script editor's
    // "Executions" view (Extensions > Apps Script > Executions), never sent
    // back to the Android client. This is what lets you find out WHY storage
    // failed (bad SPREADSHEET_ID / PHOTO_FOLDER_ID, no Drive permission, lock
    // timeout, etc.) without exposing that detail publicly.
    console.error('doPost failed: ' + (err && err.message ? err.message : err));
    // Do not expose tokens, personal data, or internal spreadsheet identifiers.
    return reply({ success: false, error: 'Storage failed' });
  } finally {
    if (lock.hasLock()) lock.releaseLock();
  }
}

function sheetText(value) {
  return "'" + String(value == null ? '' : value);
}
// Every response carries the server's own clock reading. The Android app anchors its
// "trusted time" to this value (see util/TrustedTime.kt) precisely because it comes from
// Google's infrastructure and cannot be edited from the device the way the system clock can.
// It is not sensitive -- the current time is not a secret -- so it is safe to include on
// every reply, success or failure, including doGet's unauthenticated ?action=time.
function reply(value) {
  var withServerTime = Object.assign({}, value, { serverTime: Date.now() });
  return ContentService.createTextOutput(JSON.stringify(withServerTime)).setMimeType(ContentService.MimeType.JSON);
}

/** Read-only recap endpoint: lets the admin export the combined, all-employee recap
 *  directly from the app, without opening Sheets manually. Auth uses the same shared
 *  SYNC_TOKEN as doPost, passed as a query parameter (?token=...). Never returns the
 *  token itself, and never writes anything.
 *
 *  ?action=time needs no token: it only returns the server's clock (via reply(), see above)
 *  so the app can anchor its trusted-time check even before it has a valid SYNC_TOKEN
 *  configured, and because the current time is not sensitive data. */
function doGet(e) {
  try {
    var action = e && e.parameter ? e.parameter.action : null;
    if (action === 'time') return reply({ success: true });
    var props = PropertiesService.getScriptProperties();
    var token = props.getProperty('SYNC_TOKEN');
    var requestToken = e && e.parameter ? e.parameter.token : null;
    if (!token || requestToken !== token) return reply({ success: false, error: 'Unauthorized' });
    var book = SpreadsheetApp.openById(props.getProperty('SPREADSHEET_ID'));
    var sheet = book.getSheetByName('Absensi');
    if (!sheet || sheet.getLastRow() < 2) return reply({ success: true, records: [] });
    var values = sheet.getRange(2, 1, sheet.getLastRow() - 1, 12).getValues();
    var records = values.map(function (row) {
      return {
        recordId: unwrap(row[0]), namaLengkap: unwrap(row[1]), nip: unwrap(row[2]), jabatan: unwrap(row[3]),
        jenisAbsensi: unwrap(row[4]), jamMasuk: unwrap(row[5]), jamPulang: unwrap(row[6]), tanggal: unwrap(row[7]),
        foto: unwrap(row[8]), latitude: row[9], longitude: row[10], timestamp: row[11]
      };
    });
    return reply({ success: true, records: records });
  } catch (err) {
    console.error('doGet failed: ' + (err && err.message ? err.message : err));
    // Do not expose tokens, personal data, or internal spreadsheet identifiers.
    return reply({ success: false, error: 'Fetch failed' });
  }
}

// Defensive: a leading apostrophe forces plain text on write and is not normally part of
// the stored value, but strip it if present so a mixed-history sheet reads cleanly either way.
function unwrap(value) {
  var text = String(value == null ? '' : value);
  return text.charAt(0) === "'" ? text.slice(1) : text;
}

// ================================================================================================
// Admin login (server-verified, multi-admin, password never shipped inside the APK)
// ================================================================================================
//
// Storage: Script Properties key 'ADMIN_ACCOUNTS' holds a JSON array, e.g.
//   [{ "nip": "196501011990031001", "name": "Budi (IT)", "hash": "sha256i:50000:<saltHex>:<hashHex>" }]
//
// The ONLY way to add, change, or remove an entry is to run setupAdminAccount() /
// removeAdminAccount() yourself from the Apps Script editor (select the function in the
// toolbar dropdown, click Run). Nothing in doPost/doGet ever writes to ADMIN_ACCOUNTS, so
// there is no request an app (or a decompiled/rebuilt copy of it) could ever send that
// creates or changes an admin credential. Multiple admins are supported by simply running
// setupAdminAccount() once per person.

/** UTF-8 encode a JS string to a plain byte array (no Utilities.newBlob dependency, so this
 *  also runs unmodified inside the Node test harness). */
function _utf8Bytes(str) {
  var bytes = [];
  for (var i = 0; i < str.length; i++) {
    var code = str.charCodeAt(i);
    if (code >= 0xd800 && code <= 0xdbff && i + 1 < str.length) {
      var low = str.charCodeAt(i + 1);
      code = 0x10000 + ((code - 0xd800) << 10) + (low - 0xdc00);
      i++;
    }
    if (code < 0x80) {
      bytes.push(code);
    } else if (code < 0x800) {
      bytes.push(0xc0 | (code >> 6), 0x80 | (code & 0x3f));
    } else if (code < 0x10000) {
      bytes.push(0xe0 | (code >> 12), 0x80 | ((code >> 6) & 0x3f), 0x80 | (code & 0x3f));
    } else {
      bytes.push(0xf0 | (code >> 18), 0x80 | ((code >> 12) & 0x3f), 0x80 | ((code >> 6) & 0x3f), 0x80 | (code & 0x3f));
    }
  }
  return bytes;
}
function _toUnsignedBytes(bytes) {
  var out = [];
  for (var i = 0; i < bytes.length; i++) out.push(bytes[i] < 0 ? bytes[i] + 256 : bytes[i]);
  return out;
}
function _bytesToHex(bytes) {
  var unsigned = _toUnsignedBytes(bytes);
  var hex = '';
  for (var i = 0; i < unsigned.length; i++) hex += ('0' + unsigned[i].toString(16)).slice(-2);
  return hex;
}
function _hexToBytes(hex) {
  var out = [];
  for (var i = 0; i + 1 < hex.length; i += 2) out.push(parseInt(hex.substr(i, 2), 16));
  return out;
}
function _sha256(byteArray) {
  return Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, byteArray);
}
// Apps Script has no SecureRandom; that's fine here because the salt only needs to be
// unique per admin, not unpredictable -- the secret is the password, not the salt.
function _randomSaltBytes(n) {
  var out = [];
  for (var i = 0; i < n; i++) out.push(Math.floor(Math.random() * 256));
  return out;
}

/** Salted, iterated SHA-256: h0 = SHA256(salt||password), hN = SHA256(salt||hN-1).
 *  Only ever called from setupAdminAccount() (offline, in the editor) and from
 *  verifyAdminPassword() below -- never reachable from doPost/doGet's normal request path. */
function hashAdminPassword(password, saltBytes, iterations) {
  var salt = saltBytes || _randomSaltBytes(16);
  var rounds = iterations || ADMIN_HASH_ITERATIONS_DEFAULT;
  var h = _sha256(salt.concat(_utf8Bytes(password)));
  for (var i = 1; i < rounds; i++) h = _sha256(salt.concat(h));
  return 'sha256i:' + rounds + ':' + _bytesToHex(salt) + ':' + _bytesToHex(h);
}

function verifyAdminPassword(password, stored) {
  var parts = String(stored || '').split(':');
  if (parts.length !== 4 || parts[0] !== 'sha256i') return false;
  var rounds = parseInt(parts[1], 10);
  if (!(rounds > 0)) return false;
  var salt = _hexToBytes(parts[2]);
  var computedHex = hashAdminPassword(password, salt, rounds).split(':')[3];
  var expectedHex = parts[3];
  if (computedHex.length !== expectedHex.length) return false;
  // Best-effort constant-time compare available in Apps Script.
  var diff = 0;
  for (var i = 0; i < computedHex.length; i++) diff |= computedHex.charCodeAt(i) ^ expectedHex.charCodeAt(i);
  return diff === 0;
}

function _isRateLimited(cache, key) {
  return !!cache.get(key + '_lock');
}
function _recordLoginFailure(cache, key, maxFails, lockSeconds, windowSeconds) {
  var fails = Number(cache.get(key + '_n') || '0') + 1;
  cache.put(key + '_n', String(fails), windowSeconds);
  if (fails >= maxFails) cache.put(key + '_lock', '1', lockSeconds);
}
function _clearLoginFailures(cache, key) {
  cache.remove(key + '_n');
  cache.remove(key + '_lock');
}

/** action: "adminLogin" -- POST { action: "adminLogin", nip, password }.
 *  Verified purely against Script Properties; no SYNC_TOKEN is required for this action
 *  because the admin password itself is the credential (and unlike SYNC_TOKEN, it is never
 *  baked into the APK). Rate-limited per-NIP and globally via the shared script cache so a
 *  compromised or malicious client cannot brute-force it online either. */
function handleAdminLogin(data) {
  var nip = String(data.nip || '').trim();
  var password = String(data.password || '');
  if (!nip || nip.length > 32 || !password || password.length > 200) {
    return reply({ success: false, error: 'NIP dan kata sandi admin wajib diisi' });
  }

  var cache = CacheService.getScriptCache();
  if (_isRateLimited(cache, 'adminlogin_global') || _isRateLimited(cache, 'adminlogin_nip_' + nip)) {
    return reply({ success: false, error: 'Terlalu banyak percobaan. Coba lagi beberapa menit lagi.' });
  }

  var props = PropertiesService.getScriptProperties();
  var accounts = JSON.parse(props.getProperty('ADMIN_ACCOUNTS') || '[]');
  var account = null;
  for (var i = 0; i < accounts.length; i++) {
    if (accounts[i].nip === nip) { account = accounts[i]; break; }
  }
  // Always pay the same hashing cost whether the NIP is real or not, so response timing
  // cannot be used to enumerate which NIPs are registered as admins.
  var hashToCheck = account ? account.hash : hashAdminPassword('unused-placeholder-password', _hexToBytes('00'.repeat(16)), ADMIN_HASH_ITERATIONS_DEFAULT);
  var passwordOk = verifyAdminPassword(password, hashToCheck);
  var ok = !!account && passwordOk;

  if (!ok) {
    _recordLoginFailure(cache, 'adminlogin_global', ADMIN_LOGIN_MAX_FAILS_GLOBAL, ADMIN_LOGIN_LOCK_SECONDS_GLOBAL, ADMIN_LOGIN_FAIL_WINDOW_SECONDS);
    _recordLoginFailure(cache, 'adminlogin_nip_' + nip, ADMIN_LOGIN_MAX_FAILS_PER_NIP, ADMIN_LOGIN_LOCK_SECONDS_PER_NIP, ADMIN_LOGIN_FAIL_WINDOW_SECONDS);
    return reply({ success: false, error: 'NIP atau kata sandi admin tidak sesuai' });
  }
  _clearLoginFailures(cache, 'adminlogin_nip_' + nip);
  return reply({ success: true, nip: account.nip, name: account.name || 'Administrator' });
}

/** Run this manually from the Apps Script editor (pick "setupAdminAccount" in the function
 *  dropdown, click Run) once per admin, the first time and whenever a password changes.
 *  Edit the three constants below first; after running, clear/replace the `password` value
 *  so it doesn't sit in the editor. This is never called over the network. */
function setupAdminAccount() {
  var nip = 'GANTI_DENGAN_NIP_ADMIN';
  var name = 'GANTI_DENGAN_NAMA_ADMIN';
  var password = 'GANTI_DENGAN_KATA_SANDI_BARU';

  if (!nip || nip === 'GANTI_DENGAN_NIP_ADMIN') throw new Error('Isi NIP admin dulu di kode ini sebelum Run');
  if (password.length < 8) throw new Error('Kata sandi admin minimal 8 karakter');

  var props = PropertiesService.getScriptProperties();
  var accounts = JSON.parse(props.getProperty('ADMIN_ACCOUNTS') || '[]');
  var idx = -1;
  for (var i = 0; i < accounts.length; i++) if (accounts[i].nip === nip) { idx = i; break; }
  var entry = { nip: nip, name: name, hash: hashAdminPassword(password) };
  if (idx >= 0) accounts[idx] = entry; else accounts.push(entry);
  props.setProperty('ADMIN_ACCOUNTS', JSON.stringify(accounts));
  console.log('Admin tersimpan untuk NIP ' + nip + '. Total admin terdaftar: ' + accounts.length);
}

/** Run manually to remove an admin (e.g. staff turnover). Edit `nip` first. */
function removeAdminAccount() {
  var nip = 'GANTI_DENGAN_NIP_ADMIN';
  var props = PropertiesService.getScriptProperties();
  var accounts = JSON.parse(props.getProperty('ADMIN_ACCOUNTS') || '[]');
  var filtered = accounts.filter(function (a) { return a.nip !== nip; });
  props.setProperty('ADMIN_ACCOUNTS', JSON.stringify(filtered));
  console.log('Admin dihapus (jika ada). Sisa admin terdaftar: ' + filtered.length);
}

/** Run manually to see who is currently configured. Never prints password hashes. */
function listAdminAccounts() {
  var props = PropertiesService.getScriptProperties();
  var accounts = JSON.parse(props.getProperty('ADMIN_ACCOUNTS') || '[]');
  if (accounts.length === 0) { console.log('Belum ada admin terdaftar.'); return; }
  accounts.forEach(function (a) { console.log(a.nip + ' - ' + a.name); });
}
