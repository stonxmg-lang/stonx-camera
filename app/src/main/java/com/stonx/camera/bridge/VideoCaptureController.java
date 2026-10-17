package com.stonx.camera.bridge;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.CamcorderProfile;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Size;
import android.view.Surface;
import android.view.WindowManager;

import java.io.IOException;
import java.util.Arrays;

final class VideoCaptureController {
    interface Callback {
        void onSuccess(String path,
                       long sizeBytes,
                       String sha256,
                       long durationMs,
                       int width,
                       int height,
                       boolean hasVideo,
                       boolean hasAudio,
                       int brightness);

        void onFailure(String reason);
    }

    private static final long STOP_GRACE_MS = 8000L;
    private static final long THREE_A_WAIT_TIMEOUT_MS = 3000L;
    private static final long TARGET_PREVIEW_AREA = 1280L * 720L;

    private final Context context;
    private final String face;
    private final int requestedSeconds;
    private final Callback callback;
    private final HandlerThread thread;
    private final Handler handler;
    private final Object lock = new Object();

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader previewReader;
    private Surface previewSurface;
    private CameraCharacteristics characteristics;
    private MediaRecorder recorder;
    private ParcelFileDescriptor pendingFd;
    private SharedVideoWriter.PendingVideo pendingVideo;
    private boolean completed;
    private boolean recorderStarted;
    private long recordStartMs;

    private volatile Integer lastAeState;
    private volatile Integer lastAfState;
    private long threeAStartMs;

    VideoCaptureController(Context context, String face, int requestedSeconds, Callback callback) {
        this.context = context.getApplicationContext();
        this.face = face;
        this.requestedSeconds = requestedSeconds;
        this.callback = callback;
        this.thread = new HandlerThread("stonx-video-capture");
        this.thread.start();
        this.handler = new Handler(thread.getLooper());
        this.handler.postDelayed(timeoutRunnable, requestedSeconds * 1000L + STOP_GRACE_MS);
        this.handler.post(startRunnable);
    }

    private final Runnable startRunnable = new Runnable() {
        @Override
        public void run() {
            startRecording();
        }
    };

    private final Runnable stopRunnable = new Runnable() {
        @Override
        public void run() {
            stopAndPublish();
        }
    };

    private final Runnable timeoutRunnable = new Runnable() {
        @Override
        public void run() {
            cleanupRecorder(true);
            if (pendingVideo != null) {
                SharedVideoWriter.deleteVideo(context, pendingVideo.uri);
            }
            finishFailure("video_timeout");
        }
    };

    private final Runnable checkThreeARunnable = new Runnable() {
        @Override
        public void run() {
            if (isCompleted()) {
                return;
            }
            long elapsed = SystemClock.elapsedRealtime() - threeAStartMs;
            if ((aeSettled(lastAeState) && afSettled(lastAfState)) || elapsed >= THREE_A_WAIT_TIMEOUT_MS) {
                startRecorderNow();
            } else {
                handler.postDelayed(this, 100L);
            }
        }
    };

    private final ImageReader.OnImageAvailableListener previewImageListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Image image = reader.acquireLatestImage();
            if (image != null) {
                image.close();
            }
        }
    };

    private void startRecording() {
        try {
            CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            String cameraId = findCameraId(cameraManager);
            if (cameraId == null) {
                finishFailure("camera_not_found_" + ("front".equals(face) ? "front" : "back"));
                return;
            }
            characteristics = cameraManager.getCameraCharacteristics(cameraId);
            Size previewSize = choosePreviewSize(characteristics);
            CamcorderProfile profile = chooseProfile();

            pendingVideo = SharedVideoWriter.createPendingVideo(context, face + "-" + System.currentTimeMillis());
            pendingFd = SharedVideoWriter.openWriteFd(context, pendingVideo.uri);

            previewReader = ImageReader.newInstance(previewSize.getWidth(), previewSize.getHeight(), ImageFormat.YUV_420_888, 2);
            previewReader.setOnImageAvailableListener(previewImageListener, handler);
            previewSurface = previewReader.getSurface();

            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            if (profile != null) {
                recorder.setProfile(profile);
            } else {
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                recorder.setVideoEncodingBitRate(8000000);
                recorder.setVideoFrameRate(30);
                recorder.setVideoSize(1280, 720);
                recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
                recorder.setAudioEncodingBitRate(128000);
                recorder.setAudioSamplingRate(44100);
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            }
            recorder.setOutputFile(pendingFd.getFileDescriptor());
            recorder.setOrientationHint(computeOrientationHint(characteristics));
            recorder.prepare();

            Surface recorderSurface = recorder.getSurface();
            cameraManager.openCamera(cameraId, cameraStateCallback(recorderSurface), handler);
        } catch (SecurityException e) {
            cleanupRecorder(true);
            finishFailure("camera_or_microphone_permission_denied");
        } catch (CameraAccessException e) {
            cleanupRecorder(true);
            finishFailure(cameraAccessReason(e));
        } catch (IOException e) {
            cleanupRecorder(true);
            finishFailure(microphoneReason(e, "video_prepare_failed"));
        } catch (RuntimeException e) {
            cleanupRecorder(true);
            finishFailure(microphoneReason(e, "video_record_start_failed"));
        } catch (Throwable t) {
            cleanupRecorder(true);
            finishFailure("video_open_failed:" + t.getClass().getSimpleName());
        }
    }

    private String findCameraId(CameraManager cameraManager) throws CameraAccessException {
        int wanted = "front".equals(face)
                ? CameraCharacteristics.LENS_FACING_FRONT
                : CameraCharacteristics.LENS_FACING_BACK;
        for (String id : cameraManager.getCameraIdList()) {
            CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing.intValue() == wanted) {
                return id;
            }
        }
        return null;
    }

    private Size choosePreviewSize(CameraCharacteristics characteristics) {
        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] sizes = map == null ? null : map.getOutputSizes(ImageFormat.YUV_420_888);
        if (sizes == null || sizes.length == 0) {
            return new Size(1280, 720);
        }
        Size best = sizes[0];
        long bestDelta = Math.abs(best.getWidth() * (long) best.getHeight() - TARGET_PREVIEW_AREA);
        for (Size s : sizes) {
            long area = s.getWidth() * (long) s.getHeight();
            long delta = Math.abs(area - TARGET_PREVIEW_AREA);
            if (delta < bestDelta) {
                best = s;
                bestDelta = delta;
            }
        }
        return best;
    }

    private CamcorderProfile chooseProfile() {
        try {
            if (CamcorderProfile.hasProfile(CamcorderProfile.QUALITY_1080P)) {
                CamcorderProfile p = CamcorderProfile.get(CamcorderProfile.QUALITY_1080P);
                if (p.videoCodec == MediaRecorder.VideoEncoder.H264 && p.audioCodec == MediaRecorder.AudioEncoder.AAC) {
                    return p;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            if (CamcorderProfile.hasProfile(CamcorderProfile.QUALITY_720P)) {
                CamcorderProfile p = CamcorderProfile.get(CamcorderProfile.QUALITY_720P);
                if (p.videoCodec == MediaRecorder.VideoEncoder.H264 && p.audioCodec == MediaRecorder.AudioEncoder.AAC) {
                    return p;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private CameraDevice.StateCallback cameraStateCallback(final Surface recorderSurface) {
        return new CameraDevice.StateCallback() {
            @Override
            public void onOpened(CameraDevice device) {
                if (isCompleted()) {
                    try {
                        device.close();
                    } catch (Throwable ignored) {
                    }
                    return;
                }
                cameraDevice = device;
                try {
                    device.createCaptureSession(
                            Arrays.asList(previewSurface, recorderSurface),
                            sessionStateCallback(),
                            handler
                    );
                } catch (CameraAccessException e) {
                    finishFailure(cameraAccessReason(e));
                } catch (Throwable t) {
                    finishFailure("video_session_create_failed:" + t.getClass().getSimpleName());
                }
            }

            @Override
            public void onDisconnected(CameraDevice device) {
                finishFailure("camera_disconnected");
            }

            @Override
            public void onError(CameraDevice device, int error) {
                finishFailure("camera_error_" + error);
            }
        };
    }

    private CameraCaptureSession.StateCallback sessionStateCallback() {
        return new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(CameraCaptureSession session) {
                if (isCompleted()) {
                    try {
                        session.close();
                    } catch (Throwable ignored) {
                    }
                    return;
                }
                captureSession = session;
                try {
                    CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                    builder.addTarget(previewSurface);
                    builder.addTarget(recorder.getSurface());
                    builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                    builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                    builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
                    captureSession.setRepeatingRequest(builder.build(), previewCaptureCallback, handler);
                    threeAStartMs = SystemClock.elapsedRealtime();
                    handler.post(checkThreeARunnable);
                } catch (Throwable t) {
                    finishFailure("video_repeating_failed:" + t.getClass().getSimpleName());
                }
            }

            @Override
            public void onConfigureFailed(CameraCaptureSession session) {
                finishFailure("video_session_config_failed");
            }
        };
    }

    private final CameraCaptureSession.CaptureCallback previewCaptureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
            lastAeState = result.get(CaptureResult.CONTROL_AE_STATE);
            lastAfState = result.get(CaptureResult.CONTROL_AF_STATE);
        }
    };

    private boolean aeSettled(Integer state) {
        if (state == null) {
            return false;
        }
        return state == CaptureResult.CONTROL_AE_STATE_CONVERGED
                || state == CaptureResult.CONTROL_AE_STATE_LOCKED;
    }

    private boolean afSettled(Integer state) {
        if (state == null) {
            return false;
        }
        return state == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED
                || state == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                || state == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED;
    }

    private void startRecorderNow() {
        if (isCompleted()) {
            return;
        }
        try {
            recorder.start();
            recorderStarted = true;
            recordStartMs = SystemClock.elapsedRealtime();
            handler.postDelayed(stopRunnable, requestedSeconds * 1000L);
        } catch (Throwable t) {
            cleanupRecorder(true);
            if (pendingVideo != null) {
                SharedVideoWriter.deleteVideo(context, pendingVideo.uri);
            }
            finishFailure("video_recorder_start_failed:" + t.getClass().getSimpleName());
        }
    }

    private void stopAndPublish() {
        if (isCompleted()) {
            return;
        }
        try {
            recorder.stop();
            recorderStarted = false;
        } catch (RuntimeException e) {
            cleanupRecorder(true);
            if (pendingVideo != null) {
                SharedVideoWriter.deleteVideo(context, pendingVideo.uri);
            }
            finishFailure("video_stop_failed");
            return;
        } finally {
            cleanupRecorder(false);
        }

        try {
            SharedVideoWriter.finalizeVideo(context, pendingVideo.uri);
            SharedVideoWriter.VideoInfo info = SharedVideoWriter.inspectVideo(context, pendingVideo.uri);
            if (!info.hasVideo || !info.hasAudio || info.width <= 0 || info.height <= 0) {
                SharedVideoWriter.deleteVideo(context, pendingVideo.uri);
                finishFailure("video_missing_video_or_audio_track");
                return;
            }
            byte[] mp4 = SharedVideoWriter.readAllBytes(context, pendingVideo.uri);
            if (mp4 == null || mp4.length == 0 || !(mp4.length > 12 && mp4[4] == 'f' && mp4[5] == 't' && mp4[6] == 'y' && mp4[7] == 'p')) {
                SharedVideoWriter.deleteVideo(context, pendingVideo.uri);
                finishFailure("video_invalid_container");
                return;
            }
            String sha256 = SharedVideoWriter.sha256(mp4);
            long durationMs = info.durationMs > 0 ? info.durationMs : Math.max(1L, SystemClock.elapsedRealtime() - recordStartMs);
            finishSuccess(
                    pendingVideo.sharedPath,
                    mp4.length,
                    sha256,
                    durationMs,
                    info.width,
                    info.height,
                    info.hasVideo,
                    info.hasAudio,
                    info.brightness
            );
        } catch (Throwable t) {
            SharedVideoWriter.deleteVideo(context, pendingVideo.uri);
            finishFailure("video_publish_failed:" + t.getClass().getSimpleName());
        }
    }

    private void cleanupRecorder(boolean deletePending) {
        synchronized (lock) {
            if (recorder != null) {
                try {
                    if (recorderStarted) {
                        try {
                            recorder.stop();
                        } catch (Throwable ignored) {
                        }
                    }
                } finally {
                    try {
                        recorder.reset();
                    } catch (Throwable ignored) {
                    }
                    try {
                        recorder.release();
                    } catch (Throwable ignored) {
                    }
                    recorder = null;
                    recorderStarted = false;
                }
            }
            try {
                if (pendingFd != null) {
                    pendingFd.close();
                }
            } catch (Throwable ignored) {
            }
            pendingFd = null;
            if (deletePending && pendingVideo != null) {
                SharedVideoWriter.deleteVideo(context, pendingVideo.uri);
            }
        }
    }

    private String cameraAccessReason(CameraAccessException e) {
        String message = e == null ? "" : String.valueOf(e.getMessage()).toLowerCase();
        if (message.contains("busy") || message.contains("conflict") || message.contains("in use")) {
            return "camera_busy";
        }
        return "camera_access_failed";
    }

    private String microphoneReason(Exception e, String fallback) {
        String message = e == null ? "" : String.valueOf(e.getMessage()).toLowerCase();
        if (message.contains("busy") || message.contains("in use") || message.contains("resource")) {
            return "microphone_busy_or_unavailable";
        }
        return fallback;
    }

    @SuppressWarnings("deprecation")
    private int computeOrientationHint(CameraCharacteristics characteristics) {
        Integer sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        if (sensorOrientation == null) {
            sensorOrientation = 90;
        }
        int rotation = Surface.ROTATION_0;
        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null && wm.getDefaultDisplay() != null) {
                rotation = wm.getDefaultDisplay().getRotation();
            }
        } catch (Throwable ignored) {
        }
        int deviceDegrees;
        switch (rotation) {
            case Surface.ROTATION_90:
                deviceDegrees = 90;
                break;
            case Surface.ROTATION_180:
                deviceDegrees = 180;
                break;
            case Surface.ROTATION_270:
                deviceDegrees = 270;
                break;
            default:
                deviceDegrees = 0;
                break;
        }
        Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
        boolean front = facing != null && facing.intValue() == CameraCharacteristics.LENS_FACING_FRONT;
        int orientation = front
                ? (sensorOrientation + deviceDegrees) % 360
                : (sensorOrientation - deviceDegrees + 360) % 360;
        return (orientation + 360) % 360;
    }

    private boolean isCompleted() {
        synchronized (lock) {
            return completed;
        }
    }

    private void finishSuccess(String path,
                               long sizeBytes,
                               String sha256,
                               long durationMs,
                               int width,
                               int height,
                               boolean hasVideo,
                               boolean hasAudio,
                               int brightness) {
        synchronized (lock) {
            if (completed) {
                return;
            }
            completed = true;
            cleanupLocked();
        }
        callback.onSuccess(path, sizeBytes, sha256, durationMs, width, height, hasVideo, hasAudio, brightness);
    }

    private void finishFailure(String reason) {
        synchronized (lock) {
            if (completed) {
                return;
            }
            completed = true;
            cleanupLocked();
        }
        callback.onFailure(reason);
    }

    private void cleanupLocked() {
        handler.removeCallbacks(startRunnable);
        handler.removeCallbacks(stopRunnable);
        handler.removeCallbacks(timeoutRunnable);
        handler.removeCallbacks(checkThreeARunnable);
        try {
            if (captureSession != null) {
                captureSession.close();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (cameraDevice != null) {
                cameraDevice.close();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (previewSurface != null) {
                previewSurface.release();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (previewReader != null) {
                previewReader.close();
            }
        } catch (Throwable ignored) {
        }
        thread.quitSafely();
    }
}

