package com.overdrive.dilinkprobe;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;

import com.ts.avm.IAvmServiceInterface;
import com.ts.avm.IAvmServiceListener;

public class TsAvmServiceProbeRunner {

    public interface Listener {
        void onLog(String line);
        void onStatus(String status);
        void onFinished(String summary);
        void onError(String error);
    }

    private final Context context;
    private final Listener listener;

    private IAvmServiceInterface avmService;
    private boolean isBound = false;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            isBound = true;
            avmService = IAvmServiceInterface.Stub.asInterface(service);
            listener.onLog("✓ Connesso con successo a com.ts.avm.AvmAndroidService (AIDL)!");
            listener.onStatus("Servizio TS AVM Connesso");

            try {
                int status = avmService.getAvmStatus();
                listener.onLog("Stato iniziale getAvmStatus(): " + status);

                int regResult = avmService.registerAvmStatusListener(new IAvmServiceListener.Stub() {
                    @Override
                    public void onAvmServiceStatusChanged(int newStatus, String extra) {
                        listener.onLog(String.format(">> Callback onAvmServiceStatusChanged: status=%d, extra='%s'", newStatus, extra));
                    }
                });
                listener.onLog("registerAvmStatusListener esito: " + regResult);

                String summary = String.format("Servizio TS AVM funzionante!\nStato AVM: %d\nListener registrato: %d", status, regResult);
                listener.onFinished(summary);

            } catch (Exception e) {
                listener.onError("Errore chiamata AIDL su TS AVM: " + e.getMessage());
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound = false;
            avmService = null;
            listener.onLog("Disconnesso da com.ts.avm.AvmAndroidService");
        }
    };

    public TsAvmServiceProbeRunner(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    public void startProbe() {
        listener.onLog("\n=== [TS AVM SERVICE AIDL PROBE] ===");
        listener.onLog("Tentativo di bindService a com.ts.avm.AvmAndroidService...");
        listener.onStatus("Connessione a com.ts.avm in corso...");

        Intent intent = new Intent();
        intent.setComponent(new ComponentName("com.ts.avm", "com.ts.avm.AvmAndroidService"));

        try {
            boolean ok = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
            if (!ok) {
                listener.onError("bindService ha restituito FALSE per com.ts.avm.AvmAndroidService");
            }
        } catch (Exception e) {
            listener.onError("Eccezione durante bindService: " + e.getMessage());
        }
    }

    public void testStartAvm() {
        if (avmService != null && isBound) {
            try {
                listener.onLog("Invocazione startAvm()...");
                avmService.startAvm();
                listener.onLog("✓ startAvm() eseguito.");
            } catch (Exception e) {
                listener.onError("startAvm() fallito: " + e.getMessage());
            }
        } else {
            listener.onError("Servizio TS AVM non connesso!");
        }
    }

    public void testStopAvm() {
        if (avmService != null && isBound) {
            try {
                listener.onLog("Invocazione stopAvm()...");
                avmService.stopAvm();
                listener.onLog("✓ stopAvm() eseguito.");
            } catch (Exception e) {
                listener.onError("stopAvm() fallito: " + e.getMessage());
            }
        } else {
            listener.onError("Servizio TS AVM non connesso!");
        }
    }

    public void unbind() {
        if (isBound) {
            try {
                context.unbindService(serviceConnection);
            } catch (Exception ignored) {}
            isBound = false;
            avmService = null;
        }
    }
}
