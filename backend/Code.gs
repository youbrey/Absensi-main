/** Apps Script receiver. Configure Script Properties before deploying; see docs/SETUP.md. */
function doPost(e) {
  var lock = LockService.getScriptLock();
  try {
    var data = JSON.parse(e.postData.contents);
    var props = PropertiesService.getScriptProperties();
    var token = props.getProperty('SYNC_TOKEN');
    if (!token || data.token !== token) return reply({ success: false, error: 'Unauthorized' });
    if (!/^[a-f0-9]{64}$/.test(data.recordId || '') || !data.nip || !data.namaLengkap ||
        !Number.isFinite(data.timestamp) || data.timestamp <= 0 ||
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
  } catch (_) {
    // Do not expose tokens, personal data, or internal spreadsheet identifiers.
    return reply({ success: false, error: 'Storage failed' });
  } finally {
    if (lock.hasLock()) lock.releaseLock();
  }
}

function sheetText(value) {
  return "'" + String(value == null ? '' : value);
}
function reply(value) {
  return ContentService.createTextOutput(JSON.stringify(value)).setMimeType(ContentService.MimeType.JSON);
}
