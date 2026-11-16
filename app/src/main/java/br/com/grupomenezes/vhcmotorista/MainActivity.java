package br.com.grupomenezes.vhcmotorista;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.webkit.ValueCallback;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.content.FileProvider;

import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final int REQ_PERMISSOES = 10;
    private static final int REQ_FOTO = 20;
    private static final String HOST_APP = Uri.parse(BuildConfig.URL_APP).getHost();

    private WebView web;
    private GeolocationPermissions.Callback geoCallback;
    private String geoOrigin;
    private boolean pendenteIniciarRastreio = false;
    private ValueCallback<Uri[]> fotoCallback;
    private Uri fotoUri;
    private long ultimoPedidoPosicao = 0;

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0D0D0F"));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setGeolocationEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(online() ? WebSettings.LOAD_DEFAULT : WebSettings.LOAD_CACHE_ELSE_NETWORK);
        s.setUserAgentString(s.getUserAgentString() + " VHCMotoristaApp/" + BuildConfig.VERSION_NAME);

        web.addJavascriptInterface(new Ponte(), "VHCNative");

        web.setWebChromeClient(new WebChromeClient() {
            // <input type="file">: abre a câmera (foto do comprovante) ou a galeria
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fotoCallback != null) fotoCallback.onReceiveValue(null);
                fotoCallback = callback;
                try {
                    File dir = new File(getCacheDir(), "fotos");
                    dir.mkdirs();
                    File[] antigas = dir.listFiles();
                    if (antigas != null) for (File f : antigas) if (System.currentTimeMillis() - f.lastModified() > 86_400_000L) f.delete();
                    File arq = new File(dir, "foto_" + System.currentTimeMillis() + ".jpg");
                    fotoUri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fotos", arq);
                    Intent camera = new Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                            .putExtra(MediaStore.EXTRA_OUTPUT, fotoUri)
                            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    Intent abrir;
                    if (params.isCaptureEnabled()) {
                        abrir = camera;
                    } else {
                        Intent galeria = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
                        abrir = Intent.createChooser(galeria, "Foto");
                        abrir.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{camera});
                    }
                    startActivityForResult(abrir, REQ_FOTO);
                    return true;
                } catch (Exception e) {
                    fotoCallback = null;
                    callback.onReceiveValue(null);
                    return true;
                }
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (temLocalizacao()) {
                    callback.invoke(origin, true, true);
                } else {
                    geoOrigin = origin;
                    geoCallback = callback;
                    pedirPermissoes();
                }
            }
        });

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                Uri u = req.getUrl();
                String scheme = u.getScheme() == null ? "" : u.getScheme();
                if ((scheme.equals("https") || scheme.equals("http")) && HOST_APP.equals(u.getHost())) {
                    return false; // fica no app
                }
                // WhatsApp, telefone, Maps/Waze etc. abrem no app certo
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                return true;
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) {
                    v.loadUrl("file:///android_asset/offline.html");
                }
            }
        });

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(BuildConfig.URL_APP);
        }

        pedirPermissoes();
        // viagem em andamento e app reaberto: garante que o rastreio está rodando
        if (Rastreio.ativo(this) && temLocalizacao()) TrackingService.iniciar(this);
    }

    // ---------- ponte JavaScript: window.VHCNative ----------

    private class Ponte {
        @JavascriptInterface
        public boolean isNative() { return true; }

        @JavascriptInterface
        public String version() { return BuildConfig.VERSION_NAME; }

        /** cfg: {url, matricula, nome, numCarregamento, status, intervalMs} */
        @JavascriptInterface
        public void startTracking(String json) {
            try {
                Rastreio.salvarConfig(MainActivity.this, new JSONObject(json));
            } catch (Exception e) { return; }
            runOnUiThread(() -> {
                if (temLocalizacao()) {
                    TrackingService.iniciar(MainActivity.this);
                    avisarGpsDesligado();
                    pedirSemOtimizacaoBateria();
                } else {
                    pendenteIniciarRastreio = true;
                    pedirPermissoes();
                }
            });
        }

        @JavascriptInterface
        public void updateStatus(String status) {
            Rastreio.setStatus(MainActivity.this, status);
            if (Rastreio.ativo(MainActivity.this)) {
                runOnUiThread(() -> TrackingService.iniciar(MainActivity.this));
            }
        }

        @JavascriptInterface
        public void stopTracking() {
            Rastreio.desativar(MainActivity.this);
            runOnUiThread(() -> TrackingService.parar(MainActivity.this));
        }

        @JavascriptInterface
        public boolean isTracking() { return Rastreio.ativo(MainActivity.this); }

        @JavascriptInterface
        public int pendingCount() { return Rastreio.pendentes(MainActivity.this); }

        @JavascriptInterface
        public void reload() { runOnUiThread(() -> web.loadUrl(BuildConfig.URL_APP)); }

        @JavascriptInterface
        public int versionCode() { return BuildConfig.VERSION_CODE; }

        // ── armazenamento do aparelho (sessão e filas offline), independente do navegador ──
        @JavascriptInterface
        public String kvGet(String chave) {
            File f = arquivoKv(chave);
            if (!f.exists()) return "";
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] b = new byte[(int) f.length()];
                int lido = 0;
                while (lido < b.length) { int n = in.read(b, lido, b.length - lido); if (n < 0) break; lido += n; }
                return new String(b, 0, lido, StandardCharsets.UTF_8);
            } catch (Exception e) { return ""; }
        }

        @JavascriptInterface
        public boolean kvSet(String chave, String valor) {
            File f = arquivoKv(chave), tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(valor.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) { return false; }
            return tmp.renameTo(f);
        }

        @JavascriptInterface
        public void kvRemove(String chave) { arquivoKv(chave).delete(); }

        // ── GPS do aparelho (não depende da permissão de localização do navegador) ──
        /** JSON {lat,lng,acc,ts} da última posição, ou "" se ainda não houver. */
        @JavascriptInterface
        public String lastLocation() { return Rastreio.ultimaPosicao(MainActivity.this); }

        /** Pede uma posição nova agora (no máximo 1 pedido a cada 10 s). */
        @JavascriptInterface
        public void requestLocation() { runOnUiThread(MainActivity.this::pedirPosicao); }
    }

    private File arquivoKv(String chave) {
        File dir = new File(getFilesDir(), "kv");
        dir.mkdirs();
        return new File(dir, chave.replaceAll("[^A-Za-z0-9_-]", "_") + ".json");
    }

    @SuppressWarnings("MissingPermission")
    private void pedirPosicao() {
        if (!temLocalizacao()) return;
        long agora = System.currentTimeMillis();
        if (agora - ultimoPedidoPosicao < 10_000L) return;
        ultimoPedidoPosicao = agora;
        try {
            LocationServices.getFusedLocationProviderClient(this)
                    .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener(loc -> {
                        if (loc != null) Rastreio.salvarPosicao(this, loc.getLatitude(), loc.getLongitude(), loc.getAccuracy(), loc.getTime());
                    });
        } catch (Exception ignored) {}
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FOTO || fotoCallback == null) return;
        Uri[] resultado = null;
        if (resultCode == RESULT_OK) {
            Uri escolhida = data != null ? data.getData() : null;
            resultado = new Uri[]{escolhida != null ? escolhida : fotoUri};
        }
        fotoCallback.onReceiveValue(resultado);
        fotoCallback = null;
    }

    // ---------- permissões ----------

    private boolean temLocalizacao() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void pedirPermissoes() {
        List<String> faltam = new ArrayList<>();
        if (!temLocalizacao()) {
            faltam.add(Manifest.permission.ACCESS_FINE_LOCATION);
            faltam.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            faltam.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!faltam.isEmpty()) requestPermissions(faltam.toArray(new String[0]), REQ_PERMISSOES);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code != REQ_PERMISSOES) return;
        boolean ok = temLocalizacao();
        if (geoCallback != null) {
            geoCallback.invoke(geoOrigin, ok, ok);
            geoCallback = null;
        }
        if (ok && (pendenteIniciarRastreio || Rastreio.ativo(this))) {
            pendenteIniciarRastreio = false;
            TrackingService.iniciar(this);
            avisarGpsDesligado();
            pedirSemOtimizacaoBateria();
        } else if (!ok) {
            new AlertDialog.Builder(this)
                    .setTitle("Localização necessária")
                    .setMessage("O app precisa da localização para registrar a viagem e enviar o GPS ao painel da logística.\n\nPermita em Configurações → Permissões → Localização.")
                    .setPositiveButton("Abrir configurações", (d, w) -> startActivity(
                            new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))))
                    .setNegativeButton("Agora não", null)
                    .show();
        }
    }

    private void avisarGpsDesligado() {
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm != null && !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            new AlertDialog.Builder(this)
                    .setTitle("GPS desligado")
                    .setMessage("Ligue a localização do celular para o rastreio da viagem funcionar.")
                    .setPositiveButton("Ligar", (d, w) -> startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)))
                    .setNegativeButton("Depois", null)
                    .show();
        }
    }

    /** Samsung/Xiaomi/Motorola matam serviços em segundo plano; pede exceção uma vez. */
    @SuppressLint("BatteryLife")
    private void pedirSemOtimizacaoBateria() {
        SharedPreferences p = getSharedPreferences(Rastreio.PREFS, Context.MODE_PRIVATE);
        if (p.getBoolean("pediuBateria", false)) return;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
            p.edit().putBoolean("pediuBateria", true).apply();
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) {}
        }
    }

    // ---------- ciclo de vida ----------

    private boolean online() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        web.getSettings().setCacheMode(online() ? WebSettings.LOAD_DEFAULT : WebSettings.LOAD_CACHE_ELSE_NETWORK);
    }

    @Override
    protected void onPause() {
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (web.canGoBack()) {
            web.goBack();
        } else {
            moveTaskToBack(true); // não fecha o app: o rastreio continua
        }
    }
}
