package com.stonx.camera.bridge;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Surface;
import android.view.WindowManager;

import java.nio.ByteBuffer;
import java.util.Collections;

final class PhotoCaptureController {
    interface Callback {
        void onSuccess(String path, long sizeBytes, String sha256);

        void onFailure(String reason);
    }

    private static final long CAPTURE_TIMEOUT_MS = 20000L;
    private static final long TARGET_AREA = 1920L * 1080L;

    private final Context context;
    private final String face;
    private final Callback callback;
    private final HandlerThread thread;
    private final Handler handler;
    private final Object lock = new Object();

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private boolean completed;

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

    private void startCapture() {
        try {
            CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            String cameraId = findCameraId(cameraManager);
            if (cameraId == null) {
                finishFailure("camera_not_found_" + ("front".equals(face) ? "front" : "back"));
                return;
            }
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            Size jpegSize = chooseJpegSize(characteristics);
            imageReader = ImageReader.newInstance(jpegSize.getWidth(), jpegSize.getHeight(), ImageFormat.JPEG, 1);
            imageReader.setOnImageAvailableListener(imageAvailableListener, handler);
            cameraManager.openCamera(cameraId, cameraStateCallback(characteristics), handler);
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
        Size best = sizes[0];
        long bestDelta = Math.abs(best.getWidth() * (long) best.getHeight() - TARGET_AREA);
        for (Size s : sizes) {
            long area = s.getWidth() * (long) s.getHeight();
            long delta = Math.abs(area - TARGET_AREA);
            if (delta < bestDelta) {
                best = s;
                bestDelta = delta;
            }
        }
        return best;
    }

    private CameraDevice.StateCallback cameraStateCallback(final CameraCharacteristics characteristics) {
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
                            Collections.singletonList(imageReader.getSurface()),
                            sessionStateCallback(characteristics),
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

    private CameraCaptureSession.StateCallback sessionStateCallback(final CameraCharacteristics characteristics) {
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
                    CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                    builder.addTarget(imageReader.getSurface());
                    builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                    builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                    builder.set(CaptureRequest.JPEG_ORIENTATION, computeJpegOrientation(characteristics));
                    session.capture(builder.build(), captureCallback, handler);
                } catch (CameraAccessException e) {
                    finishFailure(cameraAccessReason(e));
                } catch (Throwable t) {
                    finishFailure("camera_capture_start_failed:" + t.getClass().getSimpleName());
                }
            }

            @Override
            public void onConfigureFailed(CameraCaptureSession session) {
                finishFailure("camera_session_config_failed");
            }
        };
    }

    private final CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request, CaptureFailure failure) {
            finishFailure("camera_capture_failed");
        }
    };

    private final ImageReader.OnImageAvailableListener imageAvailableListener = new ImageReader.OnImageAvailableListener() {
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
            try {
                SharedPhotoWriter.Result result = SharedPhotoWriter.writeJpeg(context, face + "-" + System.currentTimeMillis(), jpeg);
                finishSuccess(result.path, result.size, result.sha256);
            } catch (Throwable t) {
                finishFailure("camera_image_write_failed:" + t.getClass().getSimpleName());
            }
        }
    };

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

    private void finishSuccess(String path, long sizeBytes, String sha256) {
        synchronized (lock) {
            if (completed) {
                return;
            }
            completed = true;
            cleanupLocked();
        }
        callback.onSuccess(path, sizeBytes, sha256);
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
        thread.quitSafely();
    }
}
