package com.stonx.camera.bridge;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

public class CaptureActivity extends Activity {
    public static final String ACTION_STAGE2_DONE = "com.stonx.camera.bridge.action.STAGE2_DONE";

    public static final String EXTRA_TYPE = RecordingForegroundService.EXTRA_TYPE;
    public static final String EXTRA_FACE = RecordingForegroundService.EXTRA_FACE;
    public static final String EXTRA_DURATION_SECONDS = RecordingForegroundService.EXTRA_DURATION_SECONDS;
    public static final String EXTRA_OUTPUT_DIR = RecordingForegroundService.EXTRA_OUTPUT_DIR;
    public static final String EXTRA_REQUEST_ID = RecordingForegroundService.EXTRA_REQUEST_ID;

    private static final int REQ_PERMISSIONS = 2602;
    private static final long WATCHDOG_MS = 15000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private BroadcastReceiver doneReceiver;
    private String requestId = "";
    private boolean completed = false;

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (!completed) {
                Stage2StatusWriter.write(
                        CaptureActivity.this,
                        requestId,
                        false,
                        "activity_watchdog_timeout",
                        "",
                        0L
                );
                finishAfterTransitionSafe();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        parseAndValidateOrFinish(getIntent());
        if (isFinishing()) {
            return;
        }
        registerDoneReceiver();
        handler.postDelayed(watchdog, WATCHDOG_MS);
        startDryRunWhenAllowed();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(watchdog);
        unregisterDoneReceiverSafe();
        super.onDestroy();
    }

    private void parseAndValidateOrFinish(Intent intent) {
        if (intent == null) {
            Stage2StatusWriter.write(this, "", false, "missing_intent", "", 0L);
            finishAfterTransitionSafe();
            return;
        }
        requestId = valueOrEmpty(intent.getStringExtra(EXTRA_REQUEST_ID));
        String type = valueOrEmpty(intent.getStringExtra(EXTRA_TYPE));
        String face = valueOrEmpty(intent.getStringExtra(EXTRA_FACE));
        int duration = intent.getIntExtra(EXTRA_DURATION_SECONDS, -1);

        boolean validType = "photo".equals(type)
                || "video".equals(type)
                || "audio".equals(type)
                || "stage2".equals(type);
        boolean validFace = "back".equals(face) || "front".equals(face);
        boolean validDuration = duration >= 0 && duration <= 3600;
        if (!validType || !validFace || !validDuration) {
            Stage2StatusWriter.write(this, requestId, false, "invalid_arguments", "", 0L);
            finishAfterTransitionSafe();
        }
    }

    private void startDryRunWhenAllowed() {
        if (Build.VERSION.SDK_INT >= 23 && missingRequiredRuntimePermissions()) {
            requestPermissions(new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
            }, REQ_PERMISSIONS);
            return;
        }
        startServiceDryRun();
    }

    private boolean missingRequiredRuntimePermissions() {
        if (Build.VERSION.SDK_INT < 23) {
            return false;
        }
        return checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMISSIONS) {
            return;
        }
        boolean granted = grantResults.length >= 2
                && grantResults[0] == PackageManager.PERMISSION_GRANTED
                && grantResults[1] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            startServiceDryRun();
        } else {
            Stage2StatusWriter.write(this, requestId, false, "permissions_denied", "", 0L);
            finishAfterTransitionSafe();
        }
    }

    private void startServiceDryRun() {
        try {
            Intent svc = new Intent(this, RecordingForegroundService.class);
            svc.setAction(RecordingForegroundService.ACTION_STAGE2_DRY_RUN);
            Intent incoming = getIntent();
            svc.putExtra(EXTRA_TYPE, valueOrEmpty(incoming.getStringExtra(EXTRA_TYPE)));
            svc.putExtra(EXTRA_FACE, valueOrEmpty(incoming.getStringExtra(EXTRA_FACE)));
            svc.putExtra(EXTRA_DURATION_SECONDS, incoming.getIntExtra(EXTRA_DURATION_SECONDS, 0));
            svc.putExtra(EXTRA_OUTPUT_DIR, valueOrEmpty(incoming.getStringExtra(EXTRA_OUTPUT_DIR)));
            svc.putExtra(EXTRA_REQUEST_ID, requestId);
            startForegroundService(svc);
        } catch (SecurityException e) {
            Stage2StatusWriter.write(this, requestId, false, "foreground_service_start_denied", "", 0L);
            finishAfterTransitionSafe();
        } catch (RuntimeException e) {
            Stage2StatusWriter.write(
                    this,
                    requestId,
                    false,
                    "foreground_service_start_failed:" + e.getClass().getSimpleName(),
                    "",
                    0L
            );
            finishAfterTransitionSafe();
        }
    }

    private void registerDoneReceiver() {
        doneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || !ACTION_STAGE2_DONE.equals(intent.getAction())) {
                    return;
                }
                String doneRequestId = valueOrEmpty(intent.getStringExtra(EXTRA_REQUEST_ID));
                if (requestId.isEmpty() || doneRequestId.isEmpty() || requestId.equals(doneRequestId)) {
                    completed = true;
                    finishAfterTransitionSafe();
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_STAGE2_DONE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(doneReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(doneReceiver, filter);
        }
    }

    private void unregisterDoneReceiverSafe() {
        if (doneReceiver != null) {
            try {
                unregisterReceiver(doneReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            doneReceiver = null;
        }
    }

    private void finishAfterTransitionSafe() {
        completed = true;
        handler.removeCallbacks(watchdog);
        finish();
    }

    private static String valueOrEmpty(String s) {
        return s == null ? "" : s;
    }
}
