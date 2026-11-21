// দামের খাতা: Google Drive backup
// This script saves backups sent by the app into the "Dokan Backup" folder of your Drive.
// The first phone that sends a backup is remembered; other phones are refused.

const FOLDER_NAME = "Dokan Backup";
const KEEP = 30; // how many backup files to keep

function doPost(e) {
  try {
    const body = JSON.parse(e.postData.contents);
    const props = PropertiesService.getScriptProperties();
    const saved = props.getProperty("KEY");
    if (!body.key) return out({ ok: false, err: "nokey" });
    if (!saved) props.setProperty("KEY", body.key);
    else if (saved !== body.key) return out({ ok: false, err: "key" });

    const folder = getFolder();
    const name = "dokan-backup-" + Utilities.formatDate(new Date(), "Asia/Dhaka", "yyyy-MM-dd_HH-mm") + ".json";
    folder.createFile(name, JSON.stringify(body.data), "application/json");

    const files = [];
    const it = folder.getFiles();
    while (it.hasNext()) files.push(it.next());
    files.sort((a, b) => b.getDateCreated() - a.getDateCreated());
    files.slice(KEEP).forEach(f => f.setTrashed(true));

    return out({ ok: true, name: name });
  } catch (err) {
    return out({ ok: false, err: String(err) });
  }
}

function doGet() {
  return out({ ok: true, app: "dokan-backup" });
}

// Run this once from the editor if you change phones and want the new phone to be accepted.
function resetPhone() {
  PropertiesService.getScriptProperties().deleteProperty("KEY");
}

function getFolder() {
  const it = DriveApp.getFoldersByName(FOLDER_NAME);
  return it.hasNext() ? it.next() : DriveApp.createFolder(FOLDER_NAME);
}

function out(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}
