package com.stonx.camera.bridge;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

public class CaptureActivity extends Activity {
    public static final String ACTION_STAGE2_DONE = "com.stonx.camera.bridge.action.STAGE2_DONE";

    public static final String EXTRA_TYPE = RecordingForegroundService.EXTRA_TYPE;
    public static final String EXTRA_FACE = RecordingForegroundService.EXTRA_FACE;
    public static final String EXTRA_DURATION_SECONDS = RecordingForegroundService.EXTRA_DURATION_SECONDS;
    public static final String EXTRA_OUTPUT_DIR = RecordingForegroundService.EXTRA_OUTPUT_DIR;
    public static final String EXTRA_REQUEST_ID = RecordingForegroundService.EXTRA_REQUEST_ID;
    public static final String EXTRA_SHARED_PATH = "shared_path";

    private static final int REQ_PERMISSIONS = 2602;

    private String requestId = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        parseAndValidateOrFinish(getIntent());
        if (isFinishing()) {
            return;
        }
        if ("delete_shared".equals(valueOrEmpty(getIntent().getStringExtra(EXTRA_TYPE)))) {
            handleDeleteSharedAndFinish();
            return;
        }
        startWhenAllowed();
    }

    private void handleDeleteSharedAndFinish() {
        String req = valueOrEmpty(getIntent().getStringExtra(EXTRA_REQUEST_ID));
        if (req.isEmpty()) {
            req = "delete-" + System.currentTimeMillis();
        }
        String sharedPath = valueOrEmpty(getIntent().getStringExtra(EXTRA_SHARED_PATH));
        SharedMediaDeleter.Result result = SharedMediaDeleter.delete(this, sharedPath);
        Stage2StatusWriter.write(
                this,
                req,
                result.ok,
                result.reason,
                result.path,
                0L
        );
        finish();
    }

    private void parseAndValidateOrFinish(Intent intent) {
        if (intent == null) {
            Stage2StatusWriter.write(this, "", false, "missing_intent", "", 0L);
            finish();
            return;
        }
        requestId = valueOrEmpty(intent.getStringExtra(EXTRA_REQUEST_ID));
        String type = valueOrEmpty(intent.getStringExtra(EXTRA_TYPE));
        String face = valueOrEmpty(intent.getStringExtra(EXTRA_FACE));
        int duration = intent.getIntExtra(EXTRA_DURATION_SECONDS, 0);

        boolean isDeleteShared = "delete_shared".equals(type);
        boolean validType = "photo".equals(type)
                || "video".equals(type)
                || "audio".equals(type)
                || "stage2".equals(type)
                || isDeleteShared;
        boolean validFace = "back".equals(face) || "front".equals(face);
        boolean validDuration = duration >= 0 && duration <= 3600;
        if ("audio".equals(type) || "video".equals(type)) {
            validDuration = duration > 0 && duration <= 3600;
        }
        if (isDeleteShared) {
            validDuration = duration == 0;
            if (valueOrEmpty(intent.getStringExtra(EXTRA_SHARED_PATH)).isEmpty()) {
                Stage2StatusWriter.write(this, requestId, false, "missing_shared_path", "", 0L);
                finish();
                return;
            }
        }
        if (!validType || !validFace || !validDuration) {
            Stage2StatusWriter.write(this, requestId, false, "invalid_arguments", "", 0L);
            finish();
        }
    }

    private void startWhenAllowed() {
        String type = valueOrEmpty(getIntent().getStringExtra(EXTRA_TYPE));
        String[] required = requiredPermissionsForType(type);
        if (Build.VERSION.SDK_INT >= 23 && missingAnyPermission(required)) {
            requestPermissions(required, REQ_PERMISSIONS);
            return;
        }
        startServiceAndFinish();
    }

    private String[] requiredPermissionsForType(String type) {
        if ("photo".equals(type)) {
            return new String[]{Manifest.permission.CAMERA};
        }
        if ("audio".equals(type)) {
            return new String[]{Manifest.permission.RECORD_AUDIO};
        }
        return new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
    }

    private boolean missingAnyPermission(String[] permissions) {
        if (Build.VERSION.SDK_INT < 23) {
            return false;
        }
        for (String permission : permissions) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMISSIONS) {
            return;
        }
        boolean granted = grantResults.length == permissions.length && grantResults.length > 0;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                granted = false;
                break;
            }
        }
        if (granted) {
            startServiceAndFinish();
        } else {
            Stage2StatusWriter.write(this, requestId, false, "permissions_denied", "", 0L);
            finish();
        }
    }

    private void startServiceAndFinish() {
        try {
            Intent svc = new Intent(this, RecordingForegroundService.class);
            Intent incoming = getIntent();
            svc.putExtra(EXTRA_TYPE, valueOrEmpty(incoming.getStringExtra(EXTRA_TYPE)));
            svc.putExtra(EXTRA_FACE, valueOrEmpty(incoming.getStringExtra(EXTRA_FACE)));
            svc.putExtra(EXTRA_DURATION_SECONDS, incoming.getIntExtra(EXTRA_DURATION_SECONDS, 0));
            svc.putExtra(EXTRA_OUTPUT_DIR, valueOrEmpty(incoming.getStringExtra(EXTRA_OUTPUT_DIR)));
            svc.putExtra(EXTRA_REQUEST_ID, requestId);
            startForegroundService(svc);
        } catch (SecurityException e) {
            Stage2StatusWriter.write(this, requestId, false, "foreground_service_start_denied", "", 0L);
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
        finish();
    }

    private static String valueOrEmpty(String s) {
        return s == null ? "" : s;
    }
}

