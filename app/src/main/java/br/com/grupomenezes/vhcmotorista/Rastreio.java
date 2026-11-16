package br.com.grupomenezes.vhcmotorista;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.BatteryManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Estado do rastreio (SharedPreferences) + envio dos pontos de GPS ao n8n,
 * com fila offline: o que não sai fica guardado e vai na próxima conexão.
 */
final class Rastreio {

    static final String PREFS = "vhc_rastreio";
    private static final String K_ATIVO = "ativo";
    private static final String K_URL = "url";
    private static final String K_MATRICULA = "matricula";
    private static final String K_NOME = "nome";
    private static final String K_CARREG = "numCarregamento";
    private static final String K_STATUS = "status";
    private static final String K_INTERVALO = "intervaloMs";
    private static final String K_FILA = "fila";
    private static final String K_ULTIMO_ENVIO = "ultimoEnvio";

    static final long INTERVALO_PADRAO = 5 * 60 * 1000L;
    private static final int FILA_MAX = 3000; // ~10 dias de pontos a cada 5 min

    private Rastreio() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------- configuração vinda do app web ----------

    static void salvarConfig(Context c, JSONObject cfg) {
        long intervalo = cfg.optLong("intervalMs", INTERVALO_PADRAO);
        if (intervalo < 60_000L) intervalo = 60_000L;
        sp(c).edit()
                .putBoolean(K_ATIVO, true)
                .putString(K_URL, cfg.optString("url", ""))
                .putString(K_MATRICULA, cfg.optString("matricula", ""))
                .putString(K_NOME, cfg.optString("nome", ""))
                .putString(K_CARREG, cfg.optString("numCarregamento", ""))
                .putString(K_STATUS, cfg.optString("status", "traveling"))
                .putLong(K_INTERVALO, intervalo)
                .apply();
    }

    static void desativar(Context c) {
        sp(c).edit().putBoolean(K_ATIVO, false).apply();
    }

    static void setStatus(Context c, String status) {
        sp(c).edit().putString(K_STATUS, status).apply();
    }

    static boolean ativo(Context c) { return sp(c).getBoolean(K_ATIVO, false); }
    static long intervalo(Context c) { return sp(c).getLong(K_INTERVALO, INTERVALO_PADRAO); }
    static String carregamento(Context c) { return sp(c).getString(K_CARREG, ""); }
    static String status(Context c) { return sp(c).getString(K_STATUS, "traveling"); }
    static long ultimoEnvio(Context c) { return sp(c).getLong(K_ULTIMO_ENVIO, 0); }

    /** Última posição obtida pelo aparelho (serviço de viagem ou pedido avulso do app). */
    static void salvarPosicao(Context c, double lat, double lng, float acc, long quando) {
        sp(c).edit().putString("ultPos", lat + ";" + lng + ";" + acc + ";" + quando).apply();
    }

    /** JSON {lat,lng,acc,ts} ou "" se ainda não há posição. */
    static String ultimaPosicao(Context c) {
        String v = sp(c).getString("ultPos", "");
        if (v.isEmpty()) return "";
        try {
            String[] p = v.split(";");
            JSONObject o = new JSONObject();
            o.put("lat", Double.parseDouble(p[0]));
            o.put("lng", Double.parseDouble(p[1]));
            o.put("acc", Math.round(Float.parseFloat(p[2])));
            o.put("ts", Long.parseLong(p[3]));
            return o.toString();
        } catch (Exception e) { return ""; }
    }

    static synchronized int pendentes(Context c) {
        return lerFila(c).length();
    }

    // ---------- envio ----------

    /** Monta o ponto no mesmo formato que o app web manda para /motorista/gps-track. */
    static JSONObject montarPonto(Context c, double lat, double lng, float acc, long quando) {
        SharedPreferences p = sp(c);
        JSONObject o = new JSONObject();
        try {
            o.put("matricula", p.getString(K_MATRICULA, ""));
            o.put("nome", p.getString(K_NOME, ""));
            o.put("numCarregamento", p.getString(K_CARREG, ""));
            o.put("lat", lat);
            o.put("lng", lng);
            o.put("acc", Math.round(acc));
            o.put("ts", isoUtc(quando));
            o.put("status", p.getString(K_STATUS, "traveling"));
            o.put("fonte", "android");
            o.put("bateria", bateria(c));
        } catch (Exception ignored) {}
        return o;
    }

    /**
     * Envia a fila pendente (mais antigo primeiro) e depois o ponto novo.
     * Roda em thread de fundo. Retorna true se o ponto novo saiu.
     */
    static synchronized boolean enviar(Context c, JSONObject novo) {
        String url = sp(c).getString(K_URL, "");
        if (url.isEmpty()) return false;

        JSONArray fila = lerFila(c);
        if (novo != null) fila.put(novo);

        int enviados = 0;
        for (int i = 0; i < fila.length(); i++) {
            JSONObject item = fila.optJSONObject(i);
            if (item == null) { enviados++; continue; }
            if (!post(url, item)) break;
            enviados++;
        }

        JSONArray resto = new JSONArray();
        for (int i = enviados; i < fila.length(); i++) resto.put(fila.opt(i));
        // limita o tamanho descartando os mais antigos
        while (resto.length() > FILA_MAX) resto.remove(0);
        SharedPreferences.Editor ed = sp(c).edit().putString(K_FILA, resto.toString());
        if (enviados > 0) ed.putLong(K_ULTIMO_ENVIO, System.currentTimeMillis());
        ed.apply();
        return resto.length() == 0;
    }

    private static JSONArray lerFila(Context c) {
        try { return new JSONArray(sp(c).getString(K_FILA, "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }

    private static boolean post(String url, JSONObject body) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setRequestMethod("POST");
            con.setConnectTimeout(15000);
            con.setReadTimeout(15000);
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = con.getOutputStream()) { os.write(bytes); }
            int code = con.getResponseCode();
            return code >= 200 && code < 300;
        } catch (Exception e) {
            return false;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    private static String isoUtc(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }

    private static int bateria(Context c) {
        try {
            BatteryManager bm = (BatteryManager) c.getSystemService(Context.BATTERY_SERVICE);
            return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        } catch (Exception e) { return -1; }
    }
}
