package com.stonx.camera.bridge;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

final class AudioCaptureController {
    interface Callback {
        void onSuccess(String path, long sizeBytes, String sha256, long durationMs, int signalLevel);

        void onFailure(String reason);
    }

    private static final long STOP_GRACE_MS = 8000L;
    private static final long AMPLITUDE_POLL_MS = 200L;

    private final Context context;
    private final int requestedSeconds;
    private final Callback callback;
    private final HandlerThread thread;
    private final Handler handler;
    private final Object lock = new Object();

    private MediaRecorder recorder;
    private File tempFile;
    private boolean completed;
    private boolean recorderStarted;
    private long startMs;
    private int maxAmplitude;

    AudioCaptureController(Context context, int requestedSeconds, Callback callback) {
        this.context = context.getApplicationContext();
        this.requestedSeconds = requestedSeconds;
        this.callback = callback;
        this.thread = new HandlerThread("stonx-audio-capture");
        this.thread.start();
        this.handler = new Handler(thread.getLooper());
        long timeoutMs = requestedSeconds * 1000L + STOP_GRACE_MS;
        this.handler.postDelayed(timeoutRunnable, timeoutMs);
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
            releaseRecorder();
            deleteTempFile();
            finishFailure("audio_timeout");
        }
    };

    private final Runnable amplitudeRunnable = new Runnable() {
        @Override
        public void run() {
            if (isCompleted()) {
                return;
            }
            updateMaxAmplitude();
            handler.postDelayed(this, AMPLITUDE_POLL_MS);
        }
    };

    private void startRecording() {
        try {
            tempFile = new File(context.getCacheDir(), "stonx-audio-" + System.currentTimeMillis() + ".m4a");
            if (tempFile.exists() && !tempFile.delete()) {
                finishFailure("audio_temp_delete_failed");
                return;
            }

            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(44100);
            recorder.setAudioEncodingBitRate(128000);
            recorder.setOutputFile(tempFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            recorderStarted = true;
            startMs = SystemClock.elapsedRealtime();
            handler.post(amplitudeRunnable);
            handler.postDelayed(stopRunnable, requestedSeconds * 1000L);
        } catch (SecurityException e) {
            cleanupAfterStartFailure();
            finishFailure("audio_permission_denied");
        } catch (IOException e) {
            cleanupAfterStartFailure();
            finishFailure(microphoneReason(e, "audio_prepare_failed"));
        } catch (RuntimeException e) {
            cleanupAfterStartFailure();
            finishFailure(microphoneReason(e, "audio_record_start_failed"));
        } catch (Throwable t) {
            cleanupAfterStartFailure();
            finishFailure("audio_open_failed:" + t.getClass().getSimpleName());
        }
    }

    private void stopAndPublish() {
        if (isCompleted()) {
            return;
        }

        long measuredDurationMs;
        try {
            updateMaxAmplitude();
            measuredDurationMs = SystemClock.elapsedRealtime() - startMs;
            recorder.stop();
            recorderStarted = false;
        } catch (RuntimeException e) {
            releaseRecorder();
            deleteTempFile();
            finishFailure("audio_stop_failed");
            return;
        } finally {
            releaseRecorder();
        }

        byte[] m4a = readAllBytes(tempFile);
        deleteTempFile();
        if (m4a == null || m4a.length == 0) {
            finishFailure("audio_empty_file");
            return;
        }
        if (!isMp4Ftyp(m4a)) {
            finishFailure("audio_invalid_container");
            return;
        }

        try {
            SharedAudioWriter.Result result = SharedAudioWriter.writeM4a(
                    context,
                    "audio-" + System.currentTimeMillis(),
                    m4a
            );
            long actualDurationMs = readDurationMs(result.uri);
            if (actualDurationMs <= 0) {
                actualDurationMs = measuredDurationMs;
            }
            finishSuccess(result.path, result.size, result.sha256, actualDurationMs, maxAmplitude);
        } catch (Throwable t) {
            finishFailure("audio_publish_failed:" + t.getClass().getSimpleName());
        }
    }

    private void cleanupAfterStartFailure() {
        releaseRecorder();
        deleteTempFile();
    }

    private void releaseRecorder() {
        synchronized (lock) {
            if (recorder == null) {
                return;
            }
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
    }

    private void updateMaxAmplitude() {
        try {
            if (recorder != null) {
                int amplitude = recorder.getMaxAmplitude();
                if (amplitude > maxAmplitude) {
                    maxAmplitude = amplitude;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private byte[] readAllBytes(File file) {
        if (file == null || !file.exists()) {
            return null;
        }
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private boolean isMp4Ftyp(byte[] data) {
        return data.length >= 12
                && data[4] == 'f'
                && data[5] == 't'
                && data[6] == 'y'
                && data[7] == 'p';
    }

    private long readDurationMs(Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (duration == null) {
                return -1L;
            }
            return Long.parseLong(duration);
        } catch (Throwable t) {
            return -1L;
        } finally {
            try {
                retriever.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private String microphoneReason(Exception e, String fallback) {
        String message = e == null ? "" : String.valueOf(e.getMessage()).toLowerCase();
        if (message.contains("busy") || message.contains("in use") || message.contains("resource")) {
            return "microphone_busy_or_unavailable";
        }
        return fallback;
    }

    private void deleteTempFile() {
        try {
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    private boolean isCompleted() {
        synchronized (lock) {
            return completed;
        }
    }

    private void finishSuccess(String path, long sizeBytes, String sha256, long durationMs, int signalLevel) {
        synchronized (lock) {
            if (completed) {
                return;
            }
            completed = true;
            cleanupLocked();
        }
        callback.onSuccess(path, sizeBytes, sha256, durationMs, signalLevel);
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
        handler.removeCallbacks(amplitudeRunnable);
        thread.quitSafely();
    }
}
