package com.stonx.camera.bridge;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class SharedVideoWriter {
    static final class PendingVideo {
        final Uri uri;
        final String sharedPath;

        PendingVideo(Uri uri, String sharedPath) {
            this.uri = uri;
            this.sharedPath = sharedPath;
        }
    }

    static final class VideoInfo {
        final long durationMs;
        final int width;
        final int height;
        final boolean hasVideo;
        final boolean hasAudio;
        final int brightness;

        VideoInfo(long durationMs, int width, int height, boolean hasVideo, boolean hasAudio, int brightness) {
            this.durationMs = durationMs;
            this.width = width;
            this.height = height;
            this.hasVideo = hasVideo;
            this.hasAudio = hasAudio;
            this.brightness = brightness;
        }
    }

    private SharedVideoWriter() {
    }

    static PendingVideo createPendingVideo(Context context, String requestId) throws IOException {
        String safeRequestId = safeRequestId(requestId);
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        String displayName = "STONX_" + timestamp + "_" + safeRequestId + ".mp4";
        String relativePath = Environment.DIRECTORY_MOVIES + "/STONX-Camera-Bridge";
        String sharedPath = "/sdcard/" + relativePath + "/" + displayName;

        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        if (Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.Video.Media.RELATIVE_PATH, relativePath);
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
        }

        Uri uri = context.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IOException("MediaStore video insert returned null");
        }
        return new PendingVideo(uri, sharedPath);
    }

    static ParcelFileDescriptor openWriteFd(Context context, Uri uri) throws IOException {
        ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(uri, "w");
        if (fd == null) {
            throw new IOException("openFileDescriptor returned null");
        }
        return fd;
    }

    static void finalizeVideo(Context context, Uri uri) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.IS_PENDING, 0);
                context.getContentResolver().update(uri, values, null, null);
            }
        } catch (Throwable ignored) {
        }
    }

    static void deleteVideo(Context context, Uri uri) {
        try {
            context.getContentResolver().delete(uri, null, null);
        } catch (Throwable ignored) {
        }
    }

    static byte[] readAllBytes(Context context, Uri uri) throws IOException {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new IOException("openInputStream returned null");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    static String sha256(byte[] data) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(data);
            StringBuilder sb = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                sb.append(String.format(Locale.US, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IOException("sha256 failed", e);
        }
    }

    static VideoInfo inspectVideo(Context context, Uri uri) {
        long durationMs = -1L;
        int width = -1;
        int height = -1;
        boolean hasVideo = false;
        boolean hasAudio = false;

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            String d = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d != null) {
                durationMs = Long.parseLong(d);
            }
            String w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            if (w != null) {
                width = Integer.parseInt(w);
            }
            String h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            if (h != null) {
                height = Integer.parseInt(h);
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                retriever.release();
            } catch (Throwable ignored) {
            }
        }

        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(context, uri, null);
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null) {
                    continue;
                }
                if (mime.startsWith("video/")) {
                    hasVideo = true;
                    if (width < 0 && format.containsKey(MediaFormat.KEY_WIDTH)) {
                        width = format.getInteger(MediaFormat.KEY_WIDTH);
                    }
                    if (height < 0 && format.containsKey(MediaFormat.KEY_HEIGHT)) {
                        height = format.getInteger(MediaFormat.KEY_HEIGHT);
                    }
                } else if (mime.startsWith("audio/")) {
                    hasAudio = true;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                extractor.release();
            } catch (Throwable ignored) {
            }
        }

        int brightness = computeFirstFrameBrightness(context, uri);
        return new VideoInfo(durationMs, width, height, hasVideo, hasAudio, brightness);
    }

    private static int computeFirstFrameBrightness(Context context, Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        Bitmap bitmap = null;
        try {
            retriever.setDataSource(context, uri);
            bitmap = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (bitmap == null) {
                return -1;
            }
            int w = bitmap.getWidth();
            int h = bitmap.getHeight();
            int[] pixels = new int[w * h];
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
            long sum = 0L;
            for (int pixel : pixels) {
                int r = (pixel >> 16) & 0xff;
                int g = (pixel >> 8) & 0xff;
                int b = pixel & 0xff;
                sum += (299L * r + 587L * g + 114L * b) / 1000L;
            }
            long count = (long) w * (long) h;
            return count == 0 ? -1 : (int) (sum / count);
        } catch (Throwable t) {
            return -1;
        } finally {
            if (bitmap != null) {
                bitmap.recycle();
            }
            try {
                retriever.release();
            } catch (Throwable ignored) {
            }
        }
    }

    static void writeLegacyPlaceholder(Context context, String displayName, byte[] mp4) throws IOException {
        // Not used on Android 10+; kept only to make the pre-Q path explicit if ever enabled.
        throw new IOException("legacy video path is not enabled for targetSdk 34");
    }

    private static String safeRequestId(String requestId) {
        String s = requestId == null ? "request" : requestId.trim();
        if (s.isEmpty()) {
            s = "request";
        }
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}

