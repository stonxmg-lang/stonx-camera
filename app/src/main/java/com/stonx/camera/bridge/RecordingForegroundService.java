package com.stonx.camera.bridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

public class RecordingForegroundService extends Service {
    public static final String ACTION_STAGE2_DRY_RUN = "com.stonx.camera.bridge.action.STAGE2_DRY_RUN";

    public static final String EXTRA_TYPE = "type";
    public static final String EXTRA_FACE = "face";
    public static final String EXTRA_DURATION_SECONDS = "duration_seconds";
    public static final String EXTRA_OUTPUT_DIR = "output_dir";
    public static final String EXTRA_REQUEST_ID = "request_id";

    private static final int NOTIFICATION_ID = 2601;
    private static final String CHANNEL_ID = "stonx_temp_recording";
    private static final String CHANNEL_NAME = "Temporary STONX recording";

    private PowerManager.WakeLock operationWakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String requestId = intent == null ? "" : intent.getStringExtra(EXTRA_REQUEST_ID);
        String type = intent == null ? "" : intent.getStringExtra(EXTRA_TYPE);
        String face = intent == null ? "" : intent.getStringExtra(EXTRA_FACE);
        int durationSeconds = intent == null ? 0 : intent.getIntExtra(EXTRA_DURATION_SECONDS, 0);
        if (type == null || type.isEmpty()) {
            type = "stage2";
        }
        if (face == null || face.isEmpty()) {
            face = "back";
        }

        boolean foregroundStarted = startForegroundForType(type, requestId, durationSeconds);
        if (!foregroundStarted) {
            return START_NOT_STICKY;
        }

        writeOperationStarted(type, requestId);

        if ("photo".equals(type)) {
            startPhotoCapture(requestId, face);
            return START_NOT_STICKY;
        }

        if ("audio".equals(type)) {
            startAudioCapture(requestId, durationSeconds);
            return START_NOT_STICKY;
        }

        if ("video".equals(type)) {
            startVideoCapture(requestId, face, durationSeconds);
            return START_NOT_STICKY;
        }

        Stage2StatusWriter.write(
                this,
                requestId,
                true,
                "stage2_dry_run_ok",
                "simulated://stage2/" + safeRequestIdForPath(requestId),
                0L
        );
        removeNotificationAndStop(true);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        releaseWakeLockSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private boolean startForegroundForType(String type, String requestId, int durationSeconds) {
        try {
            Notification notification = buildNotification(type, requestId);
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, foregroundServiceTypes(type));
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            acquireOperationWakeLock(type, durationSeconds);
            return true;
        } catch (SecurityException e) {
            Stage2StatusWriter.write(this, requestId, false, "foreground_service_permission_denied", "", 0L);
        } catch (RuntimeException e) {
            Stage2StatusWriter.write(
                    this,
                    requestId,
                    false,
                    "foreground_service_start_failed:" + e.getClass().getSimpleName(),
                    "",
                    0L
            );
        }
        removeNotificationAndStop(false);
        return false;
    }

    private int foregroundServiceTypes(String type) {
        if (Build.VERSION.SDK_INT < 29) {
            return 0;
        }
        if ("photo".equals(type)) {
            return ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
        }
        if ("audio".equals(type)) {
            return ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        }
        return ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
    }

    private void acquireOperationWakeLock(String type, int durationSeconds) {
        try {
            long timeoutMs;
            if ("photo".equals(type)) {
                timeoutMs = 60000L;
            } else if ("audio".equals(type) || "video".equals(type)) {
                timeoutMs = durationSeconds * 1000L + 30000L;
            } else {
                timeoutMs = 30000L;
            }
            if (timeoutMs > 3650000L) {
                timeoutMs = 3650000L;
            }

            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) {
                return;
            }
            operationWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "stonx:operation");
            operationWakeLock.setReferenceCounted(false);
            operationWakeLock.acquire(timeoutMs);
        } catch (Throwable ignored) {
            operationWakeLock = null;
        }
    }

    private void releaseWakeLockSafely() {
        try {
            if (operationWakeLock != null && operationWakeLock.isHeld()) {
                operationWakeLock.release();
            }
        } catch (Throwable ignored) {
        } finally {
            operationWakeLock = null;
        }
    }

    private void writeOperationStarted(String type, String requestId) {
        Stage2StatusWriter.write(
                this,
                requestId,
                true,
                "operation_started",
                "pending://" + type + "/" + safeRequestIdForPath(requestId),
                0L
        );
    }

    private void startPhotoCapture(final String requestId, String face) {
        new PhotoCaptureController(this, face, new PhotoCaptureController.Callback() {
            @Override
            public void onSuccess(String path,
                                  long sizeBytes,
                                  String sha256,
                                  int brightness,
                                  String aeState,
                                  long exposureNs,
                                  int iso) {
                Stage2StatusWriter.write(
                        RecordingForegroundService.this,
                        requestId,
                        true,
                        "photo_ok",
                        path,
                        sizeBytes,
                        sha256,
                        brightness,
                        aeState,
                        exposureNs,
                        iso
                );
                removeNotificationAndStop(true);
            }

            @Override
            public void onFailure(String reason) {
                Stage2StatusWriter.write(
                        RecordingForegroundService.this,
                        requestId,
                        false,
                        reason == null || reason.isEmpty() ? "photo_failed" : reason,
                        "",
                        0L
                );
                removeNotificationAndStop(true);
            }
        });
    }

    private void startAudioCapture(final String requestId, int durationSeconds) {
        if (durationSeconds <= 0 || durationSeconds > 3600) {
            Stage2StatusWriter.write(this, requestId, false, "invalid_duration", "", 0L);
            removeNotificationAndStop(true);
            return;
        }
        new AudioCaptureController(this, durationSeconds, new AudioCaptureController.Callback() {
            @Override
            public void onSuccess(String path, long sizeBytes, String sha256, long durationMs, int signalLevel) {
                Stage2StatusWriter.write(
                        RecordingForegroundService.this,
                        requestId,
                        true,
                        "audio_ok",
                        path,
                        sizeBytes,
                        sha256,
                        -1,
                        "",
                        -1L,
                        -1,
                        durationMs,
                        signalLevel
                );
                removeNotificationAndStop(true);
            }

            @Override
            public void onFailure(String reason) {
                Stage2StatusWriter.write(
                        RecordingForegroundService.this,
                        requestId,
                        false,
                        reason == null || reason.isEmpty() ? "audio_failed" : reason,
                        "",
                        0L
                );
                removeNotificationAndStop(true);
            }
        });
    }

    private void startVideoCapture(final String requestId, String face, int durationSeconds) {
        if (durationSeconds <= 0 || durationSeconds > 3600) {
            Stage2StatusWriter.write(this, requestId, false, "invalid_duration", "", 0L);
            removeNotificationAndStop(true);
            return;
        }
        new VideoCaptureController(this, face, durationSeconds, new VideoCaptureController.Callback() {
            @Override
            public void onSuccess(String path,
                                  long sizeBytes,
                                  String sha256,
                                  long durationMs,
                                  int width,
                                  int height,
                                  boolean hasVideo,
                                  boolean hasAudio,
                                  int brightness) {
                Stage2StatusWriter.write(
                        RecordingForegroundService.this,
                        requestId,
                        true,
                        "video_ok",
                        path,
                        sizeBytes,
                        sha256,
                        brightness,
                        "",
                        -1L,
                        -1,
                        durationMs,
                        -1,
                        hasVideo,
                        hasAudio,
                        width,
                        height
                );
                removeNotificationAndStop(true);
            }

            @Override
            public void onFailure(String reason) {
                Stage2StatusWriter.write(
                        RecordingForegroundService.this,
                        requestId,
                        false,
                        reason == null || reason.isEmpty() ? "video_failed" : reason,
                        "",
                        0L
                );
                removeNotificationAndStop(true);
            }
        });
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_LOW
                );
                channel.setDescription("Temporary notification only while a STONX capture operation is active.");
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String type, String requestId) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        String text;
        if ("photo".equals(type)) {
            text = "Capturing photo";
        } else if ("video".equals(type)) {
            text = "Recording video";
        } else if ("audio".equals(type)) {
            text = "Recording audio";
        } else {
            text = "Preparing " + (requestId == null ? "request" : requestId);
        }
        return b
                .setSmallIcon(R.drawable.ic_stonx_notification)
                .setContentTitle("STONX temporary operation")
                .setContentText(text)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setPriority(Notification.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }

    private void removeNotificationAndStop(boolean foregroundStarted) {
        try {
            if (foregroundStarted) {
                stopForeground(true);
            }
        } finally {
            releaseWakeLockSafely();
            stopSelf();
        }
    }

    private static String safeRequestIdForPath(String requestId) {
        if (requestId == null || requestId.trim().isEmpty()) {
            return "request";
        }
        return requestId.trim().replaceAll("[^A-Za-z0-9._-]", "_");
    }
}

