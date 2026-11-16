package br.com.grupomenezes.vhcmotorista;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Serviço em primeiro plano (notificação fixa) que pega a localização a cada
 * intervalo — 5 min por padrão — e manda ao n8n, mesmo com a tela apagada.
 */
public class TrackingService extends Service {

    static final String ACAO_PARAR = "br.com.grupomenezes.vhcmotorista.PARAR";
    private static final String CANAL = "rastreio";
    private static final int NOTIF_ID = 1001;

    private FusedLocationProviderClient fused;
    private ExecutorService rede;
    private PowerManager.WakeLock wake;
    private ConnectivityManager.NetworkCallback netCb;
    private long ultimoPontoMs = 0;

    private final LocationCallback callback = new LocationCallback() {
        @Override
        public void onLocationResult(LocationResult r) {
            Location loc = r.getLastLocation();
            if (loc != null) registrar(loc);
        }
    };

    static void iniciar(Context c) {
        Intent i = new Intent(c, TrackingService.class);
        c.startForegroundService(i);
    }

    static void parar(Context c) {
        c.stopService(new Intent(c, TrackingService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        fused = LocationServices.getFusedLocationProviderClient(this);
        rede = Executors.newSingleThreadExecutor();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vhc:envio-gps");
        wake.setReferenceCounted(false);
        criarCanal();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACAO_PARAR.equals(intent.getAction())) {
            Rastreio.desativar(this);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!Rastreio.ativo(this) || !temPermissao()) {
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            Notification n = notificacao();
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception e) {
            // Android não deixou iniciar em segundo plano: tenta de novo quando o app abrir
            stopSelf();
            return START_NOT_STICKY;
        }

        iniciarLocalizacao();
        observarRede();
        return START_STICKY;
    }

    private boolean temPermissao() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressWarnings("MissingPermission")
    private void iniciarLocalizacao() {
        long intervalo = Rastreio.intervalo(this);
        LocationRequest req = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalo)
                .setMinUpdateIntervalMillis(intervalo)
                .setMaxUpdateDelayMillis(intervalo)
                .setWaitForAccurateLocation(false)
                .build();
        try {
            fused.removeLocationUpdates(callback);
            fused.requestLocationUpdates(req, callback, Looper.getMainLooper());
            // primeiro ponto já na saída, sem esperar 5 min
            fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener(loc -> { if (loc != null) registrar(loc); });
        } catch (SecurityException ignored) {
            stopSelf();
        }
    }

    private void registrar(Location loc) {
        Rastreio.salvarPosicao(this, loc.getLatitude(), loc.getLongitude(), loc.getAccuracy(), loc.getTime());
        long agora = System.currentTimeMillis();
        // evita pontos duplicados quando o fused entrega dois seguidos
        if (agora - ultimoPontoMs < 60_000L) return;
        ultimoPontoMs = agora;

        final org.json.JSONObject ponto = Rastreio.montarPonto(
                this, loc.getLatitude(), loc.getLongitude(), loc.getAccuracy(), loc.getTime());
        wake.acquire(60_000L);
        rede.execute(() -> {
            try {
                Rastreio.enviar(this, ponto);
                atualizarNotificacao();
            } finally {
                if (wake.isHeld()) wake.release();
            }
        });
    }

    /** Quando a internet volta, descarrega a fila na hora. */
    private void observarRede() {
        if (netCb != null) return;
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        netCb = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                if (Rastreio.pendentes(TrackingService.this) == 0) return;
                rede.execute(() -> {
                    Rastreio.enviar(TrackingService.this, null);
                    atualizarNotificacao();
                });
            }
        };
        try { cm.registerDefaultNetworkCallback(netCb); } catch (Exception ignored) { netCb = null; }
    }

    // ---------- notificação ----------

    private void criarCanal() {
        NotificationChannel ch = new NotificationChannel(CANAL, "Rastreio da viagem", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Envio da localização durante a viagem");
        ch.setShowBadge(false);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
    }

    private Notification notificacao() {
        Intent abrir = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, abrir,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        String status = Rastreio.status(this);
        String titulo = "paused".equals(status) ? "Viagem pausada — rastreio ativo" : "Viagem em andamento";
        StringBuilder txt = new StringBuilder("Carregamento ").append(Rastreio.carregamento(this));
        long ult = Rastreio.ultimoEnvio(this);
        if (ult > 0) {
            txt.append(" · enviado às ")
               .append(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(ult)));
        }
        int pend = Rastreio.pendentes(this);
        if (pend > 0) txt.append(" · ").append(pend).append(" na fila (sem sinal)");

        return new Notification.Builder(this, CANAL)
                .setSmallIcon(R.drawable.ic_notif)
                .setColor(0xFFC0183C)
                .setContentTitle(titulo)
                .setContentText(txt.toString())
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void atualizarNotificacao() {
        try {
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_ID, notificacao());
        } catch (Exception ignored) {}
    }

    @Override
    public void onDestroy() {
        try { fused.removeLocationUpdates(callback); } catch (Exception ignored) {}
        if (netCb != null) {
            try { ((ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)).unregisterNetworkCallback(netCb); }
            catch (Exception ignored) {}
        }
        if (wake != null && wake.isHeld()) wake.release();
        if (rede != null) rede.shutdown();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
