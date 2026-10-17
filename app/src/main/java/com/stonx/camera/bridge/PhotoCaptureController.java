package com.stonx.camera.bridge;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Size;
import android.view.Surface;
import android.view.WindowManager;

import java.nio.ByteBuffer;
import java.util.Arrays;

final class PhotoCaptureController {
    interface Callback {
        void onSuccess(String path,
                       long sizeBytes,
                       String sha256,
                       int brightness,
                       String aeState,
                       long exposureNs,
                       int iso);

        void onFailure(String reason);
    }

    private static final long CAPTURE_TIMEOUT_MS = 25000L;
    private static final long THREE_A_WAIT_TIMEOUT_MS = 3000L;
    private static final long LOW_BRIGHTNESS_RETRY_DELAY_MS = 1200L;
    private static final int LOW_BRIGHTNESS_THRESHOLD = 35;
    private static final long TARGET_STILL_AREA = 1920L * 1080L;
    private static final long TARGET_PREVIEW_AREA = 1280L * 720L;

    private final Context context;
    private final String face;
    private final Callback callback;
    private final HandlerThread thread;
    private final Handler handler;
    private final Object lock = new Object();

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private ImageReader previewReader;
    private Surface previewSurface;
    private CameraCharacteristics characteristics;
    private boolean completed;
    private boolean retriedAfterLowBrightness;

    private volatile Integer lastAeState;
    private volatile Integer lastAfState;
    private Integer stillAeState;
    private Long stillExposureNs;
    private Integer stillIso;
    private long threeAStartMs;

    PhotoCaptureController(Context context, String face, Callback callback) {
        this.context = context.getApplicationContext();
        this.face = face;
        this.callback = callback;
        this.thread = new HandlerThread("stonx-photo-capture");
        this.thread.start();
        this.handler = new Handler(thread.getLooper());
        this.handler.postDelayed(timeoutRunnable, CAPTURE_TIMEOUT_MS);
        this.handler.post(startRunnable);
    }

    private final Runnable startRunnable = new Runnable() {
        @Override
        public void run() {
            startCapture();
        }
    };

    private final Runnable timeoutRunnable = new Runnable() {
        @Override
        public void run() {
            finishFailure("camera_timeout");
        }
    };

    private final Runnable checkThreeARunnable = new Runnable() {
        @Override
        public void run() {
            if (isCompleted()) {
                return;
            }
            long elapsed = SystemClock.elapsedRealtime() - threeAStartMs;
            if ((threeASettled(lastAeState) && threeASettled(lastAfState)) || elapsed >= THREE_A_WAIT_TIMEOUT_MS) {
                captureStill();
            } else {
                handler.postDelayed(this, 100L);
            }
        }
    };

    private final Runnable retryCaptureRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isCompleted()) {
                captureStill();
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

    private void startCapture() {
        try {
            CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            String cameraId = findCameraId(cameraManager);
            if (cameraId == null) {
                finishFailure("camera_not_found_" + ("front".equals(face) ? "front" : "back"));
                return;
            }
            characteristics = cameraManager.getCameraCharacteristics(cameraId);
            Size jpegSize = chooseJpegSize(characteristics);
            Size previewSize = choosePreviewSize(characteristics);

            imageReader = ImageReader.newInstance(jpegSize.getWidth(), jpegSize.getHeight(), ImageFormat.JPEG, 1);
            imageReader.setOnImageAvailableListener(stillImageListener, handler);

            previewReader = ImageReader.newInstance(previewSize.getWidth(), previewSize.getHeight(), ImageFormat.YUV_420_888, 2);
            previewReader.setOnImageAvailableListener(previewImageListener, handler);
            previewSurface = previewReader.getSurface();

            cameraManager.openCamera(cameraId, cameraStateCallback(), handler);
        } catch (SecurityException e) {
            finishFailure("camera_permission_denied");
        } catch (CameraAccessException e) {
            finishFailure(cameraAccessReason(e));
        } catch (Throwable t) {
            finishFailure("camera_open_failed:" + t.getClass().getSimpleName());
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

    private Size chooseJpegSize(CameraCharacteristics characteristics) {
        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] sizes = map == null ? null : map.getOutputSizes(ImageFormat.JPEG);
        if (sizes == null || sizes.length == 0) {
            return new Size(1920, 1080);
        }
        return closestSize(sizes, TARGET_STILL_AREA);
    }

    private Size choosePreviewSize(CameraCharacteristics characteristics) {
        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] sizes = map == null ? null : map.getOutputSizes(ImageFormat.YUV_420_888);
        if (sizes == null || sizes.length == 0) {
            return new Size(1280, 720);
        }
        return closestSize(sizes, TARGET_PREVIEW_AREA);
    }

    private Size closestSize(Size[] sizes, long targetArea) {
        Size best = sizes[0];
        long bestDelta = Math.abs(best.getWidth() * (long) best.getHeight() - targetArea);
        for (Size s : sizes) {
            long area = s.getWidth() * (long) s.getHeight();
            long delta = Math.abs(area - targetArea);
            if (delta < bestDelta) {
                best = s;
                bestDelta = delta;
            }
        }
        return best;
    }

    private CameraDevice.StateCallback cameraStateCallback() {
        return new CameraDevice.StateCallback() {
            @Override
            public void onOpened(CameraDevice device) {
                if (isCompleted()) {
                    closeDevice(device);
                    return;
                }
                cameraDevice = device;
                try {
                    device.createCaptureSession(
                            Arrays.asList(previewSurface, imageReader.getSurface()),
                            sessionStateCallback(),
                            handler
                    );
                } catch (CameraAccessException e) {
                    finishFailure(cameraAccessReason(e));
                } catch (Throwable t) {
                    finishFailure("camera_session_create_failed:" + t.getClass().getSimpleName());
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
                    startRepeatingPreview();
                } catch (Throwable t) {
                    finishFailure("camera_preview_start_failed:" + t.getClass().getSimpleName());
                }
            }

            @Override
            public void onConfigureFailed(CameraCaptureSession session) {
                finishFailure("camera_session_config_failed");
            }
        };
    }

    private void startRepeatingPreview() throws CameraAccessException {
        CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        builder.addTarget(previewSurface);
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
        captureSession.setRepeatingRequest(builder.build(), previewCaptureCallback, handler);
        threeAStartMs = SystemClock.elapsedRealtime();
        handler.post(checkThreeARunnable);
    }

    private final CameraCaptureSession.CaptureCallback previewCaptureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
            lastAeState = result.get(CaptureResult.CONTROL_AE_STATE);
            lastAfState = result.get(CaptureResult.CONTROL_AF_STATE);
        }
    };

    private boolean threeASettled(Integer state) {
        if (state == null) {
            return false;
        }
        return state == CaptureResult.CONTROL_AE_STATE_CONVERGED
                || state == CaptureResult.CONTROL_AE_STATE_LOCKED
                || state == CaptureResult.CONTROL_AE_STATE_INACTIVE
                || state == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
                || state == CaptureResult.CONTROL_AF_STATE_CONVERGED
                || state == CaptureResult.CONTROL_AF_STATE_LOCKED
                || state == CaptureResult.CONTROL_AF_STATE_INACTIVE;
    }

    private void captureStill() {
        if (isCompleted()) {
            return;
        }
        try {
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            builder.addTarget(imageReader.getSurface());
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
            builder.set(CaptureRequest.JPEG_ORIENTATION, computeJpegOrientation(characteristics));
            captureSession.capture(builder.build(), stillCaptureCallback, handler);
        } catch (CameraAccessException e) {
            finishFailure(cameraAccessReason(e));
        } catch (Throwable t) {
            finishFailure("camera_capture_start_failed:" + t.getClass().getSimpleName());
        }
    }

    private final CameraCaptureSession.CaptureCallback stillCaptureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
            stillAeState = result.get(CaptureResult.CONTROL_AE_STATE);
            stillExposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            stillIso = result.get(CaptureResult.SENSOR_SENSITIVITY);
        }

        @Override
        public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request, CaptureFailure failure) {
            finishFailure("camera_capture_failed");
        }
    };

    private final ImageReader.OnImageAvailableListener stillImageListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Image image = null;
            byte[] jpeg = null;
            try {
                image = reader.acquireLatestImage();
                if (image != null) {
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    jpeg = new byte[buffer.remaining()];
                    buffer.get(jpeg);
                }
            } catch (Throwable t) {
                finishFailure("camera_image_read_failed:" + t.getClass().getSimpleName());
                return;
            } finally {
                if (image != null) {
                    image.close();
                }
            }
            if (jpeg == null || jpeg.length == 0) {
                finishFailure("camera_image_null");
                return;
            }

            int brightness = averageBrightness(jpeg);
            if (brightness >= 0 && brightness < LOW_BRIGHTNESS_THRESHOLD && !retriedAfterLowBrightness) {
                retriedAfterLowBrightness = true;
                handler.postDelayed(retryCaptureRunnable, LOW_BRIGHTNESS_RETRY_DELAY_MS);
                return;
            }

            try {
                SharedPhotoWriter.Result result = SharedPhotoWriter.writeJpeg(context, face + "-" + System.currentTimeMillis(), jpeg);
                finishSuccess(
                        result.path,
                        result.size,
                        result.sha256,
                        brightness,
                        aeStateName(stillAeState),
                        stillExposureNs == null ? -1L : stillExposureNs,
                        stillIso == null ? -1 : stillIso
                );
            } catch (Throwable t) {
                finishFailure("camera_image_write_failed:" + t.getClass().getSimpleName());
            }
        }
    };

    private int averageBrightness(byte[] jpeg) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 2;
        Bitmap bitmap = null;
        try {
            bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, options);
            if (bitmap == null) {
                return -1;
            }
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            long sum = 0L;
            for (int pixel : pixels) {
                int r = (pixel >> 16) & 0xff;
                int g = (pixel >> 8) & 0xff;
                int b = pixel & 0xff;
                sum += (299L * r + 587L * g + 114L * b) / 1000L;
            }
            long count = (long) width * (long) height;
            return count == 0 ? -1 : (int) (sum / count);
        } catch (Throwable t) {
            return -1;
        } finally {
            if (bitmap != null) {
                bitmap.recycle();
            }
        }
    }

    private String aeStateName(Integer state) {
        if (state == null) {
            return "UNKNOWN";
        }
        if (state.intValue() == CaptureResult.CONTROL_AE_STATE_INACTIVE) return "INACTIVE";
        if (state.intValue() == CaptureResult.CONTROL_AE_STATE_SEARCHING) return "SEARCHING";
        if (state.intValue() == CaptureResult.CONTROL_AE_STATE_CONVERGED) return "CONVERGED";
        if (state.intValue() == CaptureResult.CONTROL_AE_STATE_LOCKED) return "LOCKED";
        if (state.intValue() == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED) return "FLASH_REQUIRED";
        if (state.intValue() == CaptureResult.CONTROL_AE_STATE_PRECAPTURE) return "PRECAPTURE";
        return "UNKNOWN_" + state;
    }

    @SuppressWarnings("deprecation")
    private int computeJpegOrientation(CameraCharacteristics characteristics) {
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
            case Surface.ROTATION_0:
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

    private String cameraAccessReason(CameraAccessException e) {
        String message = e == null ? "" : String.valueOf(e.getMessage()).toLowerCase();
        if (message.contains("busy") || message.contains("conflict") || message.contains("in use")) {
            return "camera_busy";
        }
        return "camera_access_failed";
    }

    private void finishSuccess(String path,
                               long sizeBytes,
                               String sha256,
                               int brightness,
                               String aeState,
                               long exposureNs,
                               int iso) {
        synchronized (lock) {
            if (completed) {
                return;
            }
            completed = true;
            cleanupLocked();
        }
        callback.onSuccess(path, sizeBytes, sha256, brightness, aeState, exposureNs, iso);
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
        handler.removeCallbacks(timeoutRunnable);
        handler.removeCallbacks(checkThreeARunnable);
        handler.removeCallbacks(retryCaptureRunnable);
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
            if (imageReader != null) {
                imageReader.close();
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

    private static void closeDevice(CameraDevice device) {
        try {
            if (device != null) {
                device.close();
            }
        } catch (Throwable ignored) {
        }
    }
}
