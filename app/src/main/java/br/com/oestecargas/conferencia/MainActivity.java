package br.com.oestecargas.conferencia;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Conferência MDF-e — Oeste Cargas.
 * Abre o painel de conferência (n8n) em WebView e oferece ao painel, pela ponte "OesteApp":
 *  - scan(): leitor de código de barras do Google Play Services (DANFE / CODE-128)
 *  - salvarArquivo(): CSV na pasta Downloads + compartilhar
 *  - imprimir(): relatório A4 pelo serviço de impressão do Android
 *  - trocarServidor() / recarregar() / versao()
 * Tela sempre acesa durante o uso; leitor USB/Bluetooth funciona como teclado.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "conf_prefs";
    private static final String KEY_URL = "url_painel";
    private static final int REQ_ARQUIVO = 1001;
    private static final int REQ_CAMERA = 1002;

    private WebView web;
    private ProgressBar progresso;
    private SharedPreferences prefs;
    private ValueCallback<Uri[]> callbackArquivo;
    private PermissionRequest pedidoCamera;
    private long ultimoVoltar = 0;
    private boolean emErro = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout raiz = new FrameLayout(this);
        web = new WebView(this);
        raiz.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        progresso = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progresso.setMax(100);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3));
        lp.gravity = Gravity.TOP;
        raiz.addView(progresso, lp);
        setContentView(raiz);

        configurarWebView();

        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) {
            web.loadUrl(urlPainel());
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private String urlPainel() {
        String u = prefs.getString(KEY_URL, null);
        return (u == null || u.trim().isEmpty()) ? BuildConfig.URL_PADRAO : u.trim();
    }

    private String hostPainel() {
        String h = Uri.parse(urlPainel()).getHost();
        return h == null ? "" : h;
    }

    // ------------------------------------------------------------------ WebView

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void configurarWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setTextZoom(100);
        s.setUserAgentString(s.getUserAgentString() + " OesteConferencia/" + BuildConfig.VERSION_NAME);

        CookieManager.getInstance().setAcceptCookie(true);
        web.addJavascriptInterface(new Ponte(), "OesteApp");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                String esquema = u.getScheme() == null ? "" : u.getScheme();
                String host = u.getHost() == null ? "" : u.getHost();
                if ((esquema.equals("http") || esquema.equals("https")) && host.equalsIgnoreCase(hostPainel())) {
                    return false;
                }
                // Outros endereços (links externos, WhatsApp, e-mail) abrem fora do app
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (ActivityNotFoundException e) {
                    aviso("Não há aplicativo para abrir este link.");
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                progresso.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progresso.setVisibility(View.GONE);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError erro) {
                if (req.isForMainFrame()) {
                    mostrarErro("Sem conexão com o servidor (" + erro.getDescription() + ").");
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest req, WebResourceResponse resp) {
                if (req.isForMainFrame() && resp.getStatusCode() >= 400) {
                    mostrarErro(resp.getStatusCode() == 404
                            ? "Painel não encontrado (404). Confira se o workflow WF36 está ativo no n8n e o endereço do servidor."
                            : "O servidor respondeu com erro " + resp.getStatusCode() + ".");
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int p) {
                progresso.setProgress(p);
            }

            // Câmera pelo navegador (reserva, quando o leitor do Google não está disponível)
            @Override
            public void onPermissionRequest(final PermissionRequest r) {
                runOnUiThread(() -> {
                    boolean querVideo = false;
                    for (String res : r.getResources()) {
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res)) querVideo = true;
                    }
                    if (!querVideo) { r.deny(); return; }
                    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        r.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                    } else {
                        pedidoCamera = r;
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
                    }
                });
            }

            // Escolher os XML do manifesto (aceita vários de uma vez)
            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (callbackArquivo != null) callbackArquivo.onReceiveValue(null);
                callbackArquivo = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                try {
                    startActivityForResult(Intent.createChooser(i, "Escolher XML do manifesto"), REQ_ARQUIVO);
                } catch (ActivityNotFoundException e) {
                    callbackArquivo = null;
                    aviso("Nenhum gerenciador de arquivos encontrado.");
                    return false;
                }
                return true;
            }
        });
    }

    private void mostrarErro(String msg) {
        emErro = true;
        String html;
        try (InputStream in = getAssets().open("offline.html")) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            html = b.toString("UTF-8");
        } catch (Exception e) {
            html = "<h3>Sem conexão</h3><p>{{MSG}}</p><button onclick='OesteApp.recarregar()'>Tentar de novo</button>";
        }
        html = html.replace("{{MSG}}", escapar(msg)).replace("{{URL}}", escapar(urlPainel()))
                .replace("{{VERSAO}}", BuildConfig.VERSION_NAME);
        web.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null);
    }

    private static String escapar(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private void js(final String codigo) {
        runOnUiThread(() -> web.evaluateJavascript(codigo, null));
    }

    private void aviso(final String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }

    // ------------------------------------------------------------------ leitor

    private void iniciarLeitor() {
        GmsBarcodeScannerOptions opcoes = new GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_CODE_128, Barcode.FORMAT_ITF, Barcode.FORMAT_QR_CODE)
                .enableAutoZoom()
                .build();
        GmsBarcodeScanner leitor = GmsBarcodeScanning.getClient(this, opcoes);
        leitor.startScan()
                .addOnSuccessListener(b -> {
                    String v = b.getRawValue() == null ? "" : b.getRawValue();
                    js("window.onNativeScan&&window.onNativeScan(" + JSONObject.quote(v) + ")");
                })
                .addOnCanceledListener(() -> js("window.onNativeScan&&window.onNativeScan(null)"))
                .addOnFailureListener(e -> js("window.onNativeScanErro&&window.onNativeScanErro("
                        + JSONObject.quote(String.valueOf(e.getMessage())) + ")"));
    }

    // ------------------------------------------------------------------ arquivos / impressão

    private void salvar(String nome, String conteudo, String mime) {
        String nomeSeguro = nome == null || nome.trim().isEmpty() ? "conferencia.csv" : nome.replaceAll("[\\\\/:*?\"<>|]", "_");
        String tipo = mime == null || mime.isEmpty() ? "text/plain" : mime;
        byte[] dados = (conteudo == null ? "" : conteudo).getBytes(StandardCharsets.UTF_8);
        try {
            Uri uri = null;
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, nomeSeguro);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, tipo);
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new Exception("não foi possível criar o arquivo");
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    if (os == null) throw new Exception("não foi possível gravar");
                    os.write(dados);
                }
                aviso("Salvo em Downloads: " + nomeSeguro);
            } else {
                File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                File f = new File(dir, nomeSeguro);
                try (FileOutputStream os = new FileOutputStream(f)) {
                    os.write(dados);
                }
                aviso("Salvo em " + f.getAbsolutePath());
            }
            if (uri != null) {
                final Uri compartilhar = uri;
                runOnUiThread(() -> {
                    Intent s = new Intent(Intent.ACTION_SEND);
                    s.setType(tipo);
                    s.putExtra(Intent.EXTRA_STREAM, compartilhar);
                    s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try {
                        startActivity(Intent.createChooser(s, "Compartilhar " + nomeSeguro));
                    } catch (ActivityNotFoundException ignored) { }
                });
            }
        } catch (Exception e) {
            aviso("Não consegui salvar: " + e.getMessage());
        }
    }

    private void imprimir(String titulo) {
        String t = titulo == null || titulo.isEmpty() ? "Conferencia MDF-e" : titulo;
        PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
        PrintDocumentAdapter adapter = web.createPrintDocumentAdapter(t);
        pm.print(t, adapter, new PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build());
    }

    private void dialogoServidor() {
        final EditText campo = new EditText(this);
        campo.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        campo.setText(urlPainel());
        campo.setSelectAllOnFocus(true);
        FrameLayout caixa = new FrameLayout(this);
        caixa.setPadding(dp(20), dp(8), dp(20), 0);
        caixa.addView(campo);
        new AlertDialog.Builder(this)
                .setTitle("Endereço do painel")
                .setMessage("Endereço do painel de conferência no n8n.")
                .setView(caixa)
                .setPositiveButton("Salvar", (d, w) -> {
                    String u = campo.getText().toString().trim();
                    if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://" + u;
                    prefs.edit().putString(KEY_URL, u).apply();
                    emErro = false;
                    web.loadUrl(urlPainel());
                })
                .setNeutralButton("Padrão", (d, w) -> {
                    prefs.edit().remove(KEY_URL).apply();
                    emErro = false;
                    web.loadUrl(urlPainel());
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ------------------------------------------------------------------ ponte JS

    private class Ponte {
        @JavascriptInterface
        public void scan() {
            runOnUiThread(MainActivity.this::iniciarLeitor);
        }

        @JavascriptInterface
        public String versao() {
            return BuildConfig.VERSION_NAME;
        }

        @JavascriptInterface
        public void trocarServidor() {
            runOnUiThread(MainActivity.this::dialogoServidor);
        }

        @JavascriptInterface
        public void recarregar() {
            runOnUiThread(() -> {
                emErro = false;
                web.loadUrl(urlPainel());
            });
        }

        @JavascriptInterface
        public void salvarArquivo(String nome, String conteudo, String mime) {
            salvar(nome, conteudo, mime);
        }

        @JavascriptInterface
        public void imprimir(String titulo) {
            runOnUiThread(() -> MainActivity.this.imprimir(titulo));
        }
    }

    // ------------------------------------------------------------------ ciclo de vida

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_ARQUIVO || callbackArquivo == null) return;
        Uri[] r = null;
        if (res == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                r = new Uri[n];
                for (int i = 0; i < n; i++) r[i] = data.getClipData().getItemAt(i).getUri();
            } else if (data.getData() != null) {
                r = new Uri[]{data.getData()};
            }
        }
        callbackArquivo.onReceiveValue(r);
        callbackArquivo = null;
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == REQ_CAMERA && pedidoCamera != null) {
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) {
                pedidoCamera.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
            } else {
                pedidoCamera.deny();
                aviso("Sem permissão de câmera.");
            }
            pedidoCamera = null;
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (emErro) { sairOuAvisar(); return; }
        // O painel decide: fecha modal / volta para a lista. Se não tratou, sai do app.
        web.evaluateJavascript("(function(){try{return window.oesteVoltar?window.oesteVoltar()===true:false}catch(e){return false}})()",
                v -> {
                    if ("true".equals(v)) return;
                    sairOuAvisar();
                });
    }

    private void sairOuAvisar() {
        long agora = System.currentTimeMillis();
        if (agora - ultimoVoltar < 2000) {
            finish();
        } else {
            ultimoVoltar = agora;
            Toast.makeText(this, "Toque em voltar de novo para sair", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (!emErro) web.saveState(out);
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onPause() {
        web.onPause();
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
