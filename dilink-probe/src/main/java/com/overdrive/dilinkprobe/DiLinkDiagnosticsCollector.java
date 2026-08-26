package com.overdrive.dilinkprobe;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class DiLinkDiagnosticsCollector {

    public interface Listener {
        void onLog(String line);
        void onProgress(int step, int totalSteps, String message);
        void onComplete(File zipFile, String summary);
        void onError(Exception e);
    }

    private final Context context;
    private final Listener listener;

    public DiLinkDiagnosticsCollector(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    public void runDiagnostics() {
        new Thread(() -> {
            try {
                doCollect();
            } catch (Exception e) {
                listener.onError(e);
            }
        }).start();
    }

    private void doCollect() throws Exception {
        int totalSteps = 8;
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File workDir = new File(context.getCacheDir(), "dilink5_dump_" + timestamp);
        if (workDir.exists()) {
            deleteRecursive(workDir);
        }
        workDir.mkdirs();

        log("[INFO] Avvio Diagnostica DiLink 5 / Sealion 7");
        log("[INFO] Output temporaneo: " + workDir.getAbsolutePath());

        // Step 1: System Properties
        listener.onProgress(1, totalSteps, "Raccolta Proprietà di Sistema (getprop)...");
        log("\n--- [STEP 1/8] SYSTEM PROPERTIES ---");
        String fullGetprop = runShellCommand("getprop");
        writeFile(new File(workDir, "01_getprop_full.txt"), fullGetprop);

        StringBuilder summaryProps = new StringBuilder();
        summaryProps.append("=== DILINK & CAMERA FILTERED PROPS ===\n");
        String[] lines = fullGetprop.split("\n");
        for (String line : lines) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("camera") || lower.contains("cam") || lower.contains("video") ||
                lower.contains("byd") || lower.contains("soc") || lower.contains("model") ||
                lower.contains("dilink") || lower.contains("platform") || lower.contains("hardware") ||
                lower.contains("qcarcam") || lower.contains("evs") || lower.contains("bmm")) {
                summaryProps.append(line).append("\n");
            }
        }

        // Direct reflection probe on android.os.SystemProperties
        summaryProps.append("\n=== DIRECT SYSTEMPROPERTIES GET PROBE ===\n");
        String[] probedKeys = {
            "ro.product.model", "ro.product.brand", "ro.product.manufacturer",
            "ro.build.version.release", "ro.build.version.sdk", "ro.board.platform",
            "ro.soc.manufacturer", "ro.soc.model", "ro.hardware", "ro.boot.hardware",
            "vehicle.config.cam_sort", "vehicle.config.camInfo.avm", "persist.vendor.camera.autostudy.avm",
            "persist.vendor.camera.pano", "persist.vendor.qcarcam.enable", "vendor.camera.sensor.number",
            "vendor.sys.dilink.version", "ro.byd.device.name", "persist.byd.country"
        };
        for (String key : probedKeys) {
            String val = getSystemProperty(key);
            summaryProps.append(key).append(" = ").append(val != null ? val : "<null/empty>").append("\n");
        }
        writeFile(new File(workDir, "01_getprop_summary.txt"), summaryProps.toString());
        log("✓ Proprietà di sistema raccolte con successo.");

        // Step 2: ServiceManager & Dumpsys
        listener.onProgress(2, totalSteps, "Dump Servizi di Sistema (dumpsys)...");
        log("\n--- [STEP 2/8] SERVICEMANAGER & DUMPSYS ---");
        writeFile(new File(workDir, "02_dumpsys_list.txt"), runShellCommand("dumpsys -l"));
        writeFile(new File(workDir, "02_dumpsys_media_camera.txt"), runShellCommand("dumpsys media.camera"));
        writeFile(new File(workDir, "02_dumpsys_evs.txt"), runShellCommand("dumpsys android.hardware.automotive.evs@1.1::IEvsEnumerator || dumpsys android.hardware.automotive.evs@1.0::IEvsEnumerator"));
        
        StringBuilder serviceProbe = new StringBuilder();
        serviceProbe.append("=== SERVICE MANAGER DIRECT BINDER PROBE ===\n");
        String[] servicesToTest = {
            "bydcameramanager", "bmmcameraserver", "byd.camera", "media.camera",
            "android.hardware.automotive.evs.IEvsEnumerator/default",
            "android.hardware.automotive.evs.IEvsEnumerator/evs/default",
            "vendor.qti.automotive.qcarcam@1.0::IQCarCam/default",
            "byd_auto_service", "byd_hardware_service", "car_service"
        };
        for (String sName : servicesToTest) {
            boolean available = checkServiceManagerService(sName);
            serviceProbe.append(sName).append(" -> ").append(available ? "PRESENT / ACCESSIBLE" : "NULL / NOT FOUND").append("\n");
            log("Service probe: " + sName + " -> " + (available ? "PRESENTE" : "NON TROVATO"));
        }
        writeFile(new File(workDir, "02_service_binder_probe.txt"), serviceProbe.toString());

        // Step 3: HAL Inspection (lshal)
        listener.onProgress(3, totalSteps, "Ispezione HAL (lshal)...");
        log("\n--- [STEP 3/8] LSHAL INSPECTION ---");
        String lshalAll = runShellCommand("lshal");
        writeFile(new File(workDir, "03_lshal_all.txt"), lshalAll);
        
        String lshalFiltered = runShellCommand("lshal | grep -iE 'camera|evs|qcarcam|automotive|byd'");
        writeFile(new File(workDir, "03_lshal_camera_filtered.txt"), lshalFiltered);
        log("lshal camera matches:\n" + (lshalFiltered.isEmpty() ? "<nessun match>" : lshalFiltered));

        writeFile(new File(workDir, "03_lshal_evs11_debug.txt"), runShellCommand("lshal --debug=android.hardware.automotive.evs@1.1::IEvsEnumerator"));
        writeFile(new File(workDir, "03_lshal_evs10_debug.txt"), runShellCommand("lshal --debug=android.hardware.automotive.evs@1.0::IEvsEnumerator"));
        writeFile(new File(workDir, "03_lshal_qcarcam_debug.txt"), runShellCommand("lshal --debug=vendor.qti.automotive.qcarcam@1.0::IQCarCam"));

        // Step 4: Video Nodes & Kernel Info
        listener.onProgress(4, totalSteps, "Scansione Nodi V4L2 (/dev/video*)...");
        log("\n--- [STEP 4/8] V4L2 & KERNEL NODES ---");
        writeFile(new File(workDir, "04_dev_video_list.txt"), runShellCommand("ls -la /dev/video* 2>/dev/null"));
        writeFile(new File(workDir, "04_dev_media_list.txt"), runShellCommand("ls -la /dev/media* 2>/dev/null"));
        writeFile(new File(workDir, "04_v4l2_ctl_devices.txt"), runShellCommand("v4l2-ctl --list-devices 2>/dev/null"));
        
        StringBuilder kernelInfo = new StringBuilder();
        kernelInfo.append("=== KERNEL & CMDLINE ===\n");
        kernelInfo.append("VERSION: ").append(runShellCommand("cat /proc/version")).append("\n");
        kernelInfo.append("CMDLINE: ").append(runShellCommand("cat /proc/cmdline")).append("\n");
        writeFile(new File(workDir, "04_kernel_info.txt"), kernelInfo.toString());

        // Step 5: Vendor XML & Configs
        listener.onProgress(5, totalSteps, "Raccolta Configurazioni Vendor XML...");
        log("\n--- [STEP 5/8] VENDOR CONFIGS & XMLs ---");
        File vendorDir = new File(workDir, "vendor_configs");
        vendorDir.mkdirs();
        
        String[] configPaths = {
            "/vendor/etc/camera",
            "/vendor/etc/qcarcam",
            "/vendor/etc/evs",
            "/vendor/etc/automotive",
            "/vendor/etc/sensors"
        };
        for (String path : configPaths) {
            File srcDir = new File(path);
            if (srcDir.exists() && srcDir.isDirectory()) {
                log("Copia configurazioni da " + path + "...");
                copyDirectory(srcDir, new File(vendorDir, srcDir.getName()));
            }
        }
        writeFile(new File(workDir, "05_vendor_etc_listing.txt"), runShellCommand("ls -laR /vendor/etc/camera /vendor/etc/qcarcam /vendor/etc/evs* 2>/dev/null"));

        // Step 6: Framework & Reflection Class Probe
        listener.onProgress(6, totalSteps, "Analisi Framework & Classloader Probe...");
        log("\n--- [STEP 6/8] FRAMEWORK & CLASSLOADER PROBE ---");
        writeFile(new File(workDir, "06_framework_jars.txt"), runShellCommand("ls -la /system/framework /vendor/framework /system_ext/framework /product/framework 2>/dev/null"));

        StringBuilder reflectionReport = new StringBuilder();
        reflectionReport.append("=== CLASSLOADER PROBE REPORT ===\n");
        String[] candidateClasses = {
            "android.hardware.AVMCamera",
            "android.hardware.AVMCamera$IPreviewCallback",
            "android.hardware.AVMCamera$IEventCallback",
            "android.hardware.BmmCameraInfo",
            "android.hardware.IBYDCameraService",
            "android.hardware.IBYDCameraUser",
            "android.hardware.camera2.CameraManager",
            "android.hardware.automotive.evs.V1_0.IEvsEnumerator",
            "android.hardware.automotive.evs.V1_1.IEvsEnumerator",
            "android.hardware.automotive.evs.V1_0.IEvsCamera",
            "android.hardware.automotive.evs.V1_1.IEvsCamera",
            "android.hardware.automotive.evs.IEvsEnumerator",
            "vendor.qti.automotive.qcarcam.V1_0.IQCarCam",
            "com.byd.avm.MainActivity",
            "com.byd.camera.CameraManager",
            "com.byd.cameramanager.BYDCameraManager"
        };

        for (String className : candidateClasses) {
            try {
                Class<?> clazz = Class.forName(className);
                reflectionReport.append("[FOUND] ").append(className).append(" (Methods: ")
                    .append(clazz.getDeclaredMethods().length).append(", Fields: ")
                    .append(clazz.getDeclaredFields().length).append(")\n");
                log("[CL-FOUND] " + className);
                for (Method m : clazz.getDeclaredMethods()) {
                    reflectionReport.append("   -> Method: ").append(m.toString()).append("\n");
                }
            } catch (ClassNotFoundException e) {
                reflectionReport.append("[MISSING] ").append(className).append("\n");
                log("[CL-MISS] " + className);
            } catch (Throwable t) {
                reflectionReport.append("[ERROR] ").append(className).append(": ").append(t.getMessage()).append("\n");
            }
        }
        writeFile(new File(workDir, "06_class_reflection_probe.txt"), reflectionReport.toString());

        // Step 7: OEM Packages & APK Extraction
        listener.onProgress(7, totalSteps, "Individuazione ed Estrazione APK OEM...");
        log("\n--- [STEP 7/8] OEM APKS EXTRACTION ---");
        writeFile(new File(workDir, "07_pm_packages_all.txt"), runShellCommand("pm list packages -f"));

        File apkOutDir = new File(workDir, "oem_apks");
        apkOutDir.mkdirs();

        PackageManager pm = context.getPackageManager();
        List<PackageInfo> installedPackages = pm.getInstalledPackages(PackageManager.GET_META_DATA);
        List<String> targetPackages = Arrays.asList(
            "com.byd.avm",
            "com.ts.avm",
            "com.byd.cameramanager",
            "com.byd.dvr",
            "com.byd.panoramic",
            "com.ts.camera",
            "com.byd.evs",
            "com.byd.service.camera",
            "com.byd.panoramicevent"
        );

        int extractedApkCount = 0;
        for (PackageInfo pkg : installedPackages) {
            String pkgName = pkg.packageName;
            boolean match = targetPackages.contains(pkgName);
            if (!match) {
                String lower = pkgName.toLowerCase(Locale.ROOT);
                if ((lower.contains("byd") || lower.contains("ts")) &&
                    (lower.contains("avm") || lower.contains("camera") || lower.contains("dvr") || lower.contains("pano"))) {
                    match = true;
                }
            }

            if (match) {
                log("Trovato package OEM rilevante: " + pkgName);
                try {
                    ApplicationInfo appInfo = pkg.applicationInfo;
                    if (appInfo != null && appInfo.sourceDir != null) {
                        File srcApk = new File(appInfo.sourceDir);
                        if (srcApk.exists() && srcApk.canRead()) {
                            File destApk = new File(apkOutDir, pkgName + ".apk");
                            copyFile(srcApk, destApk);
                            log("✓ Estratto APK: " + destApk.getName() + " (" + (destApk.length() / 1024) + " KB)");
                            extractedApkCount++;
                        } else {
                            log("⚠ Non leggibile sourceDir: " + appInfo.sourceDir);
                        }

                        // Inspect native libs
                        if (appInfo.nativeLibraryDir != null) {
                            File libDir = new File(appInfo.nativeLibraryDir);
                            if (libDir.exists() && libDir.isDirectory()) {
                                File destLibDir = new File(apkOutDir, pkgName + "_libs");
                                destLibDir.mkdirs();
                                copyDirectory(libDir, destLibDir);
                                log("✓ Copiate librerie native .so per " + pkgName);
                            }
                        }
                    }
                } catch (Exception ex) {
                    log("Errore durante estrazione APK per " + pkgName + ": " + ex.getMessage());
                }
            }
        }
        log("Totale APK OEM estratti: " + extractedApkCount);

        // Step 8: Package into ZIP
        listener.onProgress(8, totalSteps, "Creazione Archivio ZIP finale...");
        log("\n--- [STEP 8/8] PACKAGING ZIP ---");

        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!downloadDir.exists()) {
            downloadDir.mkdirs();
        }

        File targetZip = new File(downloadDir, "dilink5_camera_dump_" + timestamp + ".zip");
        File genericZip = new File(downloadDir, "dilink5_camera_dump.zip");
        File sdcardRootZip = new File(Environment.getExternalStorageDirectory(), "dilink5_camera_dump.zip");
        File appPrivateZip = new File(context.getExternalFilesDir(null), "dilink5_camera_dump.zip");

        zipDirectory(workDir, targetZip);
        copyFile(targetZip, genericZip);
        try { copyFile(targetZip, sdcardRootZip); } catch (Exception ignored) {}
        try { copyFile(targetZip, appPrivateZip); } catch (Exception ignored) {}

        List<String> savedPaths = new ArrayList<>();
        savedPaths.add(targetZip.getAbsolutePath());
        savedPaths.add(genericZip.getAbsolutePath());
        savedPaths.add(sdcardRootZip.getAbsolutePath());

        // Copy to all removable SD cards / USB OTG drives
        try {
            File[] extDirs = context.getExternalFilesDirs(null);
            if (extDirs != null) {
                for (File ext : extDirs) {
                    if (ext != null) {
                        try {
                            File usbZip = new File(ext, "dilink5_camera_dump.zip");
                            copyFile(targetZip, usbZip);
                            savedPaths.add(usbZip.getAbsolutePath());
                            log("✓ Copiato su storage esterno: " + usbZip.getAbsolutePath());

                            // Try parent root if writable
                            File root = ext.getParentFile();
                            while (root != null && !root.getName().equals("storage") && !root.getName().equals("mnt")) {
                                File rootZip = new File(root, "dilink5_camera_dump.zip");
                                if (root.canWrite()) {
                                    try {
                                        copyFile(targetZip, rootZip);
                                        savedPaths.add(rootZip.getAbsolutePath());
                                        log("✓ Copiato su root USB: " + rootZip.getAbsolutePath());
                                        break;
                                    } catch (Exception ignored) {}
                                }
                                root = root.getParentFile();
                            }
                        } catch (Exception ex) {
                            log("Nota storage esterno: " + ex.getMessage());
                        }
                    }
                }
            }

            // Scan /storage/ and /mnt/media_rw/
            File storageDir = new File("/storage");
            if (storageDir.exists() && storageDir.isDirectory()) {
                File[] volumes = storageDir.listFiles();
                if (volumes != null) {
                    for (File vol : volumes) {
                        String name = vol.getName();
                        if (!name.equals("emulated") && !name.equals("self") && vol.isDirectory()) {
                            File usbRootZip = new File(vol, "dilink5_camera_dump.zip");
                            File usbDownloadZip = new File(new File(vol, "Download"), "dilink5_camera_dump.zip");
                            try {
                                if (vol.canWrite()) {
                                    copyFile(targetZip, usbRootZip);
                                    savedPaths.add(usbRootZip.getAbsolutePath());
                                    log("✓ Copiato su volume USB/SD: " + usbRootZip.getAbsolutePath());
                                }
                            } catch (Exception ignored) {}
                            try {
                                if (usbDownloadZip.getParentFile().exists() && usbDownloadZip.getParentFile().canWrite()) {
                                    copyFile(targetZip, usbDownloadZip);
                                    savedPaths.add(usbDownloadZip.getAbsolutePath());
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }
        } catch (Exception ex) {
            log("Scansione volumi USB terminata con nota: " + ex.getMessage());
        }

        log("\n========================================");
        log("✓ DIAGNOSTICA COMPLETATA CON SUCCESSO!");
        log("File salvato in: " + targetZip.getAbsolutePath());
        log("Dimensione archivio: " + (targetZip.length() / 1024) + " KB");
        log("========================================");

        StringBuilder sb = new StringBuilder();
        sb.append("Archivio generato: ").append(targetZip.getName())
          .append(" (").append(targetZip.length() / 1024).append(" KB)\n")
          .append("Percorsi salvati:\n");
        for (int i = 0; i < savedPaths.size(); i++) {
            sb.append(i + 1).append(". ").append(savedPaths.get(i)).append("\n");
        }

        listener.onComplete(targetZip, sb.toString().trim());
    }

    private void log(String message) {
        listener.onLog(message);
    }

    private String runShellCommand(String cmd) {
        StringBuilder output = new StringBuilder();
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append("[STDERR] ").append(line).append("\n");
                }
            }
            process.waitFor();
        } catch (Exception e) {
            output.append("[EXEC ERROR] ").append(e.getMessage()).append("\n");
        }
        return output.toString().trim();
    }

    private String getSystemProperty(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class);
            return (String) get.invoke(null, key);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean checkServiceManagerService(String serviceName) {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method checkService = sm.getMethod("checkService", String.class);
            Object binder = checkService.invoke(null, serviceName);
            return binder != null;
        } catch (Exception e) {
            return false;
        }
    }

    private void writeFile(File file, String content) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.getBytes("UTF-8"));
        }
    }

    private void copyFile(File src, File dst) throws IOException {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buffer = new byte[65536];
            int len;
            while ((len = in.read(buffer)) > 0) {
                out.write(buffer, 0, len);
            }
        }
    }

    private void copyDirectory(File srcDir, File dstDir) throws IOException {
        if (!dstDir.exists()) {
            dstDir.mkdirs();
        }
        File[] files = srcDir.listFiles();
        if (files != null) {
            for (File f : files) {
                File target = new File(dstDir, f.getName());
                if (f.isDirectory()) {
                    copyDirectory(f, target);
                } else {
                    copyFile(f, target);
                }
            }
        }
    }

    private void deleteRecursive(File fileOrDir) {
        if (fileOrDir.isDirectory()) {
            File[] children = fileOrDir.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        fileOrDir.delete();
    }

    private void zipDirectory(File sourceDir, File zipFile) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            addDirToZip(sourceDir, sourceDir, zos);
        }
    }

    private void addDirToZip(File rootDir, File currentDir, ZipOutputStream zos) throws IOException {
        File[] files = currentDir.listFiles();
        if (files == null) return;
        byte[] buffer = new byte[65536];

        for (File file : files) {
            if (file.isDirectory()) {
                addDirToZip(rootDir, file, zos);
            } else {
                String relativePath = rootDir.toURI().relativize(file.toURI()).getPath();
                ZipEntry entry = new ZipEntry(relativePath);
                entry.setTime(file.lastModified());
                zos.putNextEntry(entry);

                try (FileInputStream fis = new FileInputStream(file)) {
                    int length;
                    while ((length = fis.read(buffer)) > 0) {
                        zos.write(buffer, 0, length);
                    }
                }
                zos.closeEntry();
            }
        }
    }
}
