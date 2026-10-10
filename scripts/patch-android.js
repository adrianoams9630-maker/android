// Ajusta o projeto Android gerado pelo Capacitor. Idempotente: pode
// rodar a cada build sem duplicar nada.
//  - Permissões de câmera (foto pelo <input capture>) e localização
//    (navigator.geolocation dentro do WebView). O Capacitor só pede
//    essas permissões em tempo de execução se elas estiverem no manifest.
//  - versionCode/versionName vindos do CI (APP_VERSION_CODE / APP_VERSION_NAME),
//    para o Android aceitar o APK novo como atualização do antigo.
const fs = require('fs');
const path = require('path');

const manifest = path.join(__dirname, '..', 'android', 'app', 'src', 'main', 'AndroidManifest.xml');
let xml = fs.readFileSync(manifest, 'utf8');

const permissoes = [
  'android.permission.INTERNET',
  'android.permission.ACCESS_NETWORK_STATE',
  'android.permission.CAMERA',
  'android.permission.ACCESS_COARSE_LOCATION',
  'android.permission.ACCESS_FINE_LOCATION',
  // localização a cada 30 min com a tela apagada: serviço em primeiro
  // plano do tipo "location" + notificação fixa (Android 13+)
  'android.permission.FOREGROUND_SERVICE',
  'android.permission.FOREGROUND_SERVICE_LOCATION',
  'android.permission.POST_NOTIFICATIONS'
];
const faltando = permissoes.filter(p => !xml.includes(`"${p}"`));
if (faltando.length) {
  const linhas = faltando.map(p => `    <uses-permission android:name="${p}" />`).join('\n');
  xml = xml.replace(/<\/manifest>\s*$/, `${linhas}\n</manifest>\n`);
}
if (!xml.includes('android.hardware.camera"')) {
  xml = xml.replace(/<\/manifest>\s*$/,
    '    <uses-feature android:name="android.hardware.camera" android:required="false" />\n</manifest>\n');
}
fs.writeFileSync(manifest, xml);
console.log('AndroidManifest: permissões OK', faltando.length ? `(adicionadas: ${faltando.join(', ')})` : '');

const gradle = path.join(__dirname, '..', 'android', 'app', 'build.gradle');
if (fs.existsSync(gradle) && process.env.APP_VERSION_CODE) {
  let g = fs.readFileSync(gradle, 'utf8');
  g = g.replace(/versionCode\s+\d+/, `versionCode ${parseInt(process.env.APP_VERSION_CODE, 10)}`);
  if (process.env.APP_VERSION_NAME) g = g.replace(/versionName\s+"[^"]*"/, `versionName "${process.env.APP_VERSION_NAME}"`);
  fs.writeFileSync(gradle, g);
  console.log('build.gradle: versionCode', process.env.APP_VERSION_CODE);
}
