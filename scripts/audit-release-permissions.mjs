import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const shared = ['INTERNET', 'ACCESS_NETWORK_STATE', 'CHANGE_WIFI_MULTICAST_STATE'];
const expected = {
  'android-phone': [...shared, 'FOREGROUND_SERVICE', 'FOREGROUND_SERVICE_DATA_SYNC',
    'FOREGROUND_SERVICE_CONNECTED_DEVICE', 'POST_NOTIFICATIONS', 'CAMERA', 'WAKE_LOCK'],
  'android-tv': shared,
};
let failed = false;
const apps = process.argv.slice(2);
for (const app of apps.length ? apps : Object.keys(expected)) {
  const permissions = expected[app];
  if (!permissions) throw new Error(`Unknown app: ${app}`);
  const path = resolve(`apps/${app}/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml`);
  const xml = readFileSync(path, 'utf8');
  const packageName = xml.match(/\bpackage="([^"]+)"/)?.[1];
  if (!packageName) throw new Error(`Missing package in ${path}`);
  const allowed = new Set([...permissions.map(name => `android.permission.${name}`),
    `${packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`]);
  const declared = [...xml.matchAll(/<uses-permission(?:-sdk-\d+)?\b[^>]*android:name="([^"]+)"/g)].map(match => match[1]);
  const unexpected = declared.filter(name => !allowed.has(name));
  const missing = [...allowed].filter(name => !declared.includes(name));
  const internalPermission = `${packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`;
  const signatureProtected = [...xml.matchAll(/<permission\b[^>]*>/g)].some(([tag]) =>
    tag.includes(`android:name="${internalPermission}"`) && tag.includes('android:protectionLevel="signature"'));
  console.log(JSON.stringify({ app, packageName, permissions: declared, unexpected, missing }));
  if (unexpected.length || missing.length || !signatureProtected) failed = true;
}
if (failed) process.exitCode = 1;
