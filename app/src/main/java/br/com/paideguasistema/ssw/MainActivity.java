package br.com.paideguasistema.ssw;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.text.InputType;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * App SSW — abre o sistema SSW em WebView, sem navegador.
 * - Mantém login (cookies persistentes)
 * - Janelas pop-up do SSW (window.open) abrem empilhadas dentro do app
 * - Downloads (PDF, XML, DACTE, relatórios) vão para a pasta Downloads
 * - Upload de arquivos, impressão, modo desktop e endereço configurável
 */
public class MainActivity extends Activity {

    private static final String PREFS = "ssw_prefs";
    private static final String KEY_URL = "url_inicial";
    private static final String KEY_DESKTOP = "modo_desktop";
    private static final int REQ_ARQUIVO = 1001;

    private FrameLayout container;
    private ProgressBar progresso;
    private final List<WebView> pilha = new ArrayList<>();
    private ValueCallback<Uri[]> callbackArquivo;
    private String uaPadrao;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        LinearLayout raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);

        progresso = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progresso.setMax(100);
        progresso.setIndeterminate(false);
        raiz.addView(progresso, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        container = new FrameLayout(this);
        raiz.addView(container, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(raiz);

        CookieManager.getInstance().setAcceptCookie(true);

        WebView principal = criarWebView();
        CookieManager.getInstance().setAcceptThirdPartyCookies(principal, true);
        empilhar(principal);

        if (savedInstanceState != null && principal.restoreState(savedInstanceState) != null) {
            return;
        }
        Uri dado = getIntent() != null ? getIntent().getData() : null;
        principal.loadUrl(dado != null ? dado.toString() : urlInicial());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.getData() != null) {
            topo().loadUrl(intent.getData().toString());
        }
    }

    // ------------------------------------------------------------------ WebView

    @SuppressLint("SetJavaScriptEnabled")
    private WebView criarWebView() {
        WebView wv = new WebView(this);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(true);
        s.setAllowFileAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (uaPadrao == null) uaPadrao = s.getUserAgentString();
        aplicarUserAgent(s);

        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true);
        wv.setWebViewClient(new Cliente());
        wv.setWebChromeClient(new Chrome());
        wv.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) ->
                baixar(url, userAgent, contentDisposition, mimeType));
        return wv;
    }

    private void aplicarUserAgent(WebSettings s) {
        if (prefs.getBoolean(KEY_DESKTOP, false)) {
            s.setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36");
        } else {
            s.setUserAgentString(uaPadrao);
        }
    }

    private void empilhar(WebView wv) {
        pilha.add(wv);
        container.addView(wv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        atualizarTitulo();
    }

    private void desempilhar(WebView wv) {
        if (pilha.size() <= 1 || !pilha.contains(wv)) return;
        pilha.remove(wv);
        container.removeView(wv);
        wv.stopLoading();
        wv.destroy();
        atualizarTitulo();
    }

    private WebView topo() {
        return pilha.get(pilha.size() - 1);
    }

    private void atualizarTitulo() {
        if (getActionBar() == null) return;
        String t = pilha.isEmpty() ? null : topo().getTitle();
        getActionBar().setTitle(t == null || t.isEmpty() || t.startsWith("http") ? getString(R.string.app_name) : t);
        getActionBar().setSubtitle(pilha.size() > 1 ? "Janela " + pilha.size() : null);
    }

    private class Cliente extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            Uri uri = req.getUrl();
            String esquema = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            if (esquema.equals("http") || esquema.equals("https")) return false;
            if (esquema.equals("about") || esquema.equals("javascript") || esquema.equals("blob") || esquema.equals("data")) return false;
            // tel:, mailto:, whatsapp:, intent: etc. -> app externo
            try {
                Intent it = esquema.equals("intent")
                        ? Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                        : new Intent(Intent.ACTION_VIEW, uri);
                startActivity(it);
            } catch (Exception e) {
                Toast.makeText(MainActivity.this, "Nenhum app para abrir este link", Toast.LENGTH_SHORT).show();
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
            CookieManager.getInstance().flush();
            atualizarTitulo();
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError erro) {
            if (!req.isForMainFrame()) return;
            String falha = req.getUrl().toString();
            String html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                    + "<style>body{font-family:sans-serif;text-align:center;padding:48px 20px;color:#333}"
                    + "h2{color:#0B3D91}button{background:#0B3D91;color:#fff;border:0;border-radius:8px;"
                    + "padding:14px 28px;font-size:16px;margin-top:16px}</style></head><body>"
                    + "<h2>Não foi possível abrir o SSW</h2>"
                    + "<p>" + (temInternet() ? "O servidor não respondeu." : "Sem conexão com a internet.") + "</p>"
                    + "<p style='font-size:12px;color:#888'>" + erro.getDescription() + "</p>"
                    + "<button onclick=\"location.href='" + falha.replace("'", "%27") + "'\">Tentar novamente</button>"
                    + "</body></html>";
            view.loadDataWithBaseURL(null, html, "text/html", "UTF-8", falha);
        }
    }

    private class Chrome extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int p) {
            if (view == topo()) {
                progresso.setProgress(p);
                progresso.setVisibility(p < 100 ? View.VISIBLE : View.GONE);
            }
        }

        @Override
        public void onReceivedTitle(WebView view, String title) {
            if (view == topo()) atualizarTitulo();
        }

        // Pop-ups do SSW (cada opção do menu abre numa janela nova)
        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
            WebView nova = criarWebView();
            empilhar(nova);
            WebView.WebViewTransport t = (WebView.WebViewTransport) resultMsg.obj;
            t.setWebView(nova);
            resultMsg.sendToTarget();
            return true;
        }

        @Override
        public void onCloseWindow(WebView window) {
            desempilhar(window);
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (callbackArquivo != null) callbackArquivo.onReceiveValue(null);
            callbackArquivo = callback;
            try {
                Intent it = params.createIntent();
                if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    it.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                startActivityForResult(it, REQ_ARQUIVO);
            } catch (ActivityNotFoundException e) {
                callbackArquivo = null;
                Toast.makeText(MainActivity.this, "Não foi possível abrir o seletor de arquivos", Toast.LENGTH_SHORT).show();
                return false;
            }
            return true;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_ARQUIVO && callbackArquivo != null) {
            Uri[] resultado = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    resultado = new Uri[n];
                    for (int i = 0; i < n; i++) resultado[i] = data.getClipData().getItemAt(i).getUri();
                } else if (data.getData() != null) {
                    resultado = new Uri[]{data.getData()};
                }
            }
            callbackArquivo.onReceiveValue(resultado);
            callbackArquivo = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    // ---------------------------------------------------------------- Downloads

    private void baixar(String url, String userAgent, String contentDisposition, String mimeType) {
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            Toast.makeText(this, "Este arquivo não pode ser baixado diretamente. Use Menu > Imprimir / PDF.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            String nome = URLUtil.guessFileName(url, contentDisposition, mimeType);
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            r.setMimeType(mimeType);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) r.addRequestHeader("Cookie", cookie);
            r.addRequestHeader("User-Agent", userAgent);
            r.setTitle(nome);
            r.setDescription("SSW");
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, nome);
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            dm.enqueue(r);
            Toast.makeText(this, "Baixando " + nome, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Falha no download: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // --------------------------------------------------------------------- Menu

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "Início");
        menu.add(0, 2, 1, "Recarregar");
        menu.add(0, 3, 2, "Fechar janela");
        menu.add(0, 4, 3, "Imprimir / Salvar PDF");
        menu.add(0, 5, 4, "Modo desktop").setCheckable(true);
        menu.add(0, 6, 5, "Alterar endereço");
        menu.add(0, 7, 6, "Sair (limpar login)");
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(3).setVisible(pilha.size() > 1);
        menu.findItem(5).setChecked(prefs.getBoolean(KEY_DESKTOP, false));
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case 1:
                while (pilha.size() > 1) desempilhar(topo());
                topo().loadUrl(urlInicial());
                return true;
            case 2:
                topo().reload();
                return true;
            case 3:
                desempilhar(topo());
                return true;
            case 4:
                imprimir();
                return true;
            case 5:
                boolean novo = !prefs.getBoolean(KEY_DESKTOP, false);
                prefs.edit().putBoolean(KEY_DESKTOP, novo).apply();
                for (WebView wv : pilha) aplicarUserAgent(wv.getSettings());
                topo().reload();
                return true;
            case 6:
                dialogoEndereco();
                return true;
            case 7:
                confirmarSair();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void imprimir() {
        PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
        String nome = "SSW - " + (topo().getTitle() == null ? "pagina" : topo().getTitle());
        PrintDocumentAdapter adapter = topo().createPrintDocumentAdapter(nome);
        pm.print(nome, adapter, new PrintAttributes.Builder().build());
    }

    private void dialogoEndereco() {
        EditText campo = new EditText(this);
        campo.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        campo.setText(urlInicial());
        campo.setSelectAllOnFocus(true);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(campo);
        new AlertDialog.Builder(this)
                .setTitle("Endereço inicial do SSW")
                .setView(box)
                .setPositiveButton("Salvar e abrir", (d, w) -> {
                    String u = campo.getText().toString().trim();
                    if (!u.startsWith("http")) u = "https://" + u;
                    prefs.edit().putString(KEY_URL, u).apply();
                    while (pilha.size() > 1) desempilhar(topo());
                    topo().loadUrl(u);
                })
                .setNeutralButton("Padrão", (d, w) -> {
                    prefs.edit().remove(KEY_URL).apply();
                    topo().loadUrl(urlInicial());
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void confirmarSair() {
        new AlertDialog.Builder(this)
                .setMessage("Encerrar a sessão e limpar o login salvo?")
                .setPositiveButton("Sair", (d, w) -> {
                    CookieManager.getInstance().removeAllCookies(ok -> {
                        CookieManager.getInstance().flush();
                        runOnUiThread(() -> {
                            while (pilha.size() > 1) desempilhar(topo());
                            topo().clearHistory();
                            topo().clearCache(true);
                            topo().loadUrl(urlInicial());
                        });
                    });
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ------------------------------------------------------------------ Voltar

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        WebView wv = topo();
        if (wv.canGoBack()) {
            wv.goBack();
        } else if (pilha.size() > 1) {
            desempilhar(wv);
        } else {
            new AlertDialog.Builder(this)
                    .setMessage("Fechar o SSW?")
                    .setPositiveButton("Fechar", (d, w) -> finish())
                    .setNegativeButton("Cancelar", null)
                    .show();
        }
    }

    // ------------------------------------------------------------------ Ciclo de vida

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (!pilha.isEmpty()) pilha.get(0).saveState(out);
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
        for (WebView wv : pilha) wv.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        for (WebView wv : pilha) wv.onResume();
    }

    @Override
    protected void onDestroy() {
        for (WebView wv : pilha) wv.destroy();
        pilha.clear();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ util

    private String urlInicial() {
        return prefs.getString(KEY_URL, BuildConfig.URL_PADRAO);
    }

    private boolean temInternet() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null || cm.getActiveNetwork() == null) return false;
        NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
