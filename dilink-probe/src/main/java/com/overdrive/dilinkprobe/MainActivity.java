package com.overdrive.dilinkprobe;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;

import java.io.File;

public class MainActivity extends AppCompatActivity implements DiLinkDiagnosticsCollector.Listener {

    private static final int REQ_PERMISSIONS = 101;

    private TextView tvStatus;
    private TextView tvOutputPath;
    private TextView tvConsole;
    private TextView tvLineCount;
    private TextView tvFpsMonitor;
    private ProgressBar progressBar;

    private MaterialButton btnRun;
    private MaterialButton btnTsAvmProbe;
    private MaterialButton btnStartAvm;
    private MaterialButton btnStopAvm;
    private MaterialButton btnCamera1Probe;
    private MaterialButton btnCamera2Probe;
    private MaterialButton btnTsFrameworkProbe;
    private ScrollView scrollConsole;

    private int lineCount = 0;
    private File lastGeneratedZip = null;
    private boolean isRunning = false;

    private Camera1LegacyProbeRunner camera1Runner;
    private Camera2ProbeRunner camera2Runner;
    private TsAvmServiceProbeRunner tsAvmRunner;
    private TsFrameworkProbeRunner tsFrameworkRunner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tvStatus);
        tvOutputPath = findViewById(R.id.tvOutputPath);
        tvConsole = findViewById(R.id.tvConsole);
        tvLineCount = findViewById(R.id.tvLineCount);
        tvFpsMonitor = findViewById(R.id.tvFpsMonitor);
        progressBar = findViewById(R.id.progressBar);

        btnRun = findViewById(R.id.btnRun);
        btnTsAvmProbe = findViewById(R.id.btnTsAvmProbe);
        btnStartAvm = findViewById(R.id.btnStartAvm);
        btnStopAvm = findViewById(R.id.btnStopAvm);
        btnCamera1Probe = findViewById(R.id.btnCamera1Probe);
        btnCamera2Probe = findViewById(R.id.btnCamera2Probe);
        btnTsFrameworkProbe = findViewById(R.id.btnTsFrameworkProbe);
        scrollConsole = findViewById(R.id.scrollConsole);

        btnRun.setOnClickListener(v -> {
            if (!isRunning) {
                checkPermissionsAndRun();
            }
        });

        btnTsAvmProbe.setOnClickListener(v -> startTsAvmProbe());

        btnStartAvm.setOnClickListener(v -> {
            if (tsAvmRunner != null) {
                tsAvmRunner.testStartAvm();
            } else {
                Toast.makeText(this, "Esegui prima '1. Bind TS AVM'", Toast.LENGTH_SHORT).show();
            }
        });

        btnStopAvm.setOnClickListener(v -> {
            if (tsAvmRunner != null) {
                tsAvmRunner.testStopAvm();
            } else {
                Toast.makeText(this, "Esegui prima '1. Bind TS AVM'", Toast.LENGTH_SHORT).show();
            }
        });

        btnCamera1Probe.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQ_PERMISSIONS);
                return;
            }
            startCamera1Probe();
        });

        btnCamera2Probe.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQ_PERMISSIONS);
                return;
            }
            startCamera2Probe();
        });

        btnTsFrameworkProbe.setOnClickListener(v -> startTsFrameworkProbe());

        checkPermissions();
    }

    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception ignored) {}
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, REQ_PERMISSIONS);
        }
    }

    private void checkPermissionsAndRun() {
        checkPermissions();
        startCollection();
    }

    private void startCollection() {
        isRunning = true;
        setControlsEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        tvOutputPath.setVisibility(View.GONE);
        tvStatus.setText(R.string.status_running);
        tvStatus.setTextColor(ContextCompat.getColor(this, R.color.primary_accent));

        tvConsole.setText("");
        lineCount = 0;
        tvLineCount.setText("0 righe");

        DiLinkDiagnosticsCollector collector = new DiLinkDiagnosticsCollector(this, this);
        collector.runDiagnostics();
    }

    private void startCamera1Probe() {
        progressBar.setVisibility(View.VISIBLE);
        tvStatus.setText("Avvio Camera1 Legacy Probe...");
        tvStatus.setTextColor(ContextCompat.getColor(this, R.color.primary_accent));
        tvFpsMonitor.setText("Cam1: PROBING...");

        if (camera1Runner != null) {
            camera1Runner.stopProbe();
        }

        camera1Runner = new Camera1LegacyProbeRunner(new Camera1LegacyProbeRunner.Listener() {
            @Override
            public void onLog(String line) {
                MainActivity.this.onLog(line);
            }

            @Override
            public void onStatus(String status) {
                runOnUiThread(() -> tvStatus.setText(status));
            }

            @Override
            public void onFrameStats(int cameraId, int totalFrames, float fps, int width, int height) {
                runOnUiThread(() -> {
                    tvFpsMonitor.setText(String.format("Cam1 [%d]: %.1f FPS (%d frames) %dx%d", cameraId, fps, totalFrames, width, height));
                    tvFpsMonitor.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.console_text));
                });
            }

            @Override
            public void onFinished(String summary) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    tvStatus.setText("Camera1 probe completato");
                    tvStatus.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.success_green));
                    Toast.makeText(MainActivity.this, "Test Camera1 completato!", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    tvStatus.setText("Camera1 Errore: " + error);
                    tvStatus.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.error_red));
                    tvFpsMonitor.setText("Cam1: ERRORE");
                    onLog("[CAMERA1 ERROR] " + error);
                });
            }
        });

        camera1Runner.startProbe(0);
    }

    private void startCamera2Probe() {
        progressBar.setVisibility(View.VISIBLE);
        tvStatus.setText("Avvio Camera2 Probe...");
        tvStatus.setTextColor(ContextCompat.getColor(this, R.color.primary_accent));
        tvFpsMonitor.setText("Cam2: PROBING...");

        if (camera2Runner != null) {
            camera2Runner.stopProbe();
        }

        camera2Runner = new Camera2ProbeRunner(this, new Camera2ProbeRunner.Listener() {
            @Override
            public void onLog(String line) {
                MainActivity.this.onLog(line);
            }

            @Override
            public void onStatus(String status) {
                runOnUiThread(() -> tvStatus.setText(status));
            }

            @Override
            public void onFrameStats(int cameraId, int totalFrames, float fps, int width, int height) {
                runOnUiThread(() -> {
                    tvFpsMonitor.setText(String.format("Cam2 [%d]: %.1f FPS (%d frames) %dx%d", cameraId, fps, totalFrames, width, height));
                    tvFpsMonitor.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.console_text));
                });
            }

            @Override
            public void onFinished(String summary) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    tvStatus.setText("Camera2 probe completato");
                    tvStatus.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.success_green));
                    Toast.makeText(MainActivity.this, "Test Camera2 completato!", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    tvStatus.setText("Camera2 Errore: " + error);
                    tvStatus.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.error_red));
                    tvFpsMonitor.setText("Cam2: ERRORE");
                    onLog("[CAMERA2 ERROR] " + error);
                });
            }
        });

        camera2Runner.startProbe();
    }

    private void startTsAvmProbe() {
        progressBar.setVisibility(View.VISIBLE);
        tvStatus.setText("Connessione a com.ts.avm...");
        tvStatus.setTextColor(ContextCompat.getColor(this, R.color.primary_accent));

        if (tsAvmRunner != null) {
            tsAvmRunner.unbind();
        }

        tsAvmRunner = new TsAvmServiceProbeRunner(this, new TsAvmServiceProbeRunner.Listener() {
            @Override
            public void onLog(String line) {
                MainActivity.this.onLog(line);
            }

            @Override
            public void onStatus(String status) {
                runOnUiThread(() -> tvStatus.setText(status));
            }

            @Override
            public void onFinished(String summary) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    tvStatus.setText("TS AVM Connesso e Pronto");
                    tvStatus.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.success_green));
                    Toast.makeText(MainActivity.this, "TS AVM Service connesso!", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    tvStatus.setText("TS AVM Errore: " + error);
                    tvStatus.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.error_red));
                    onLog("[TS AVM ERROR] " + error);
                });
            }
        });

        tsAvmRunner.startProbe();
    }

    private void startTsFrameworkProbe() {
        progressBar.setVisibility(View.VISIBLE);
        tvStatus.setText("Scansione framework JAR...");
        tvStatus.setTextColor(ContextCompat.getColor(this, R.color.primary_accent));

        tsFrameworkRunner = new TsFrameworkProbeRunner(new TsFrameworkProbeRunner.Listener() {
            @Override
            public void onLog(String line) {
                MainActivity.this.onLog(line);
            }

            @Override
            public void onStatus(String status) {
                runOnUiThread(() -> tvStatus.setText(status));
            }

            @Override
            public void onFinished(String summary) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    tvStatus.setText("Scan framework terminato");
                    tvStatus.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.success_green));
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    tvStatus.setText("Scan Errore: " + error);
                    tvStatus.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.error_red));
                });
            }
        });

        tsFrameworkRunner.runProbe();
    }

    private void setControlsEnabled(boolean enabled) {
        btnRun.setEnabled(enabled);
        btnTsAvmProbe.setEnabled(enabled);
        btnStartAvm.setEnabled(enabled);
        btnStopAvm.setEnabled(enabled);
        btnCamera1Probe.setEnabled(enabled);
        btnCamera2Probe.setEnabled(enabled);
        btnTsFrameworkProbe.setEnabled(enabled);
    }

    @Override
    public void onLog(String line) {
        runOnUiThread(() -> {
            tvConsole.append(line + "\n");
            lineCount++;
            tvLineCount.setText(lineCount + " righe");
            scrollConsole.post(() -> scrollConsole.fullScroll(View.FOCUS_DOWN));
        });
    }

    @Override
    public void onProgress(int step, int totalSteps, String message) {
        runOnUiThread(() -> {
            tvStatus.setText(String.format("[%d/%d] %s", step, totalSteps, message));
        });
    }

    @Override
    public void onComplete(File zipFile, String summary) {
        runOnUiThread(() -> {
            isRunning = false;
            setControlsEnabled(true);
            progressBar.setVisibility(View.GONE);
            lastGeneratedZip = zipFile;

            tvStatus.setText(R.string.status_done);
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.success_green));

            tvOutputPath.setVisibility(View.VISIBLE);
            tvOutputPath.setText(summary);

            Toast.makeText(this, "Diagnostica completata! File salvato in Download", Toast.LENGTH_LONG).show();
        });
    }

    @Override
    public void onError(Exception e) {
        runOnUiThread(() -> {
            isRunning = false;
            setControlsEnabled(true);
            progressBar.setVisibility(View.GONE);

            tvStatus.setText(getString(R.string.status_error) + " " + e.getMessage());
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.error_red));
            onLog("[FATAL ERROR] " + e.getMessage());
            Toast.makeText(this, "Errore durante la scansione: " + e.getMessage(), Toast.LENGTH_LONG).show();
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (camera1Runner != null) {
            camera1Runner.stopProbe();
        }
        if (camera2Runner != null) {
            camera2Runner.stopProbe();
        }
        if (tsAvmRunner != null) {
            tsAvmRunner.unbind();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }
}
