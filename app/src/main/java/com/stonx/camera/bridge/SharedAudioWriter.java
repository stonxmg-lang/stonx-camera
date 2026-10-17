package com.stonx.camera.bridge;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class SharedAudioWriter {
    static final class Result {
        final String path;
        final Uri uri;
        final long size;
        final String sha256;

        Result(String path, Uri uri, long size, String sha256) {
            this.path = path;
            this.uri = uri;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    private SharedAudioWriter() {
    }

    static Result writeM4a(Context context, String requestId, byte[] m4a) throws IOException {
        if (m4a == null || m4a.length == 0) {
            throw new IOException("empty m4a payload");
        }
        String safeRequestId = safeRequestId(requestId);
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        String displayName = "STONX_" + timestamp + "_" + safeRequestId + ".m4a";
        String relativePath = Environment.DIRECTORY_MUSIC + "/STONX-Camera-Bridge";
        String sharedPath = "/sdcard/" + relativePath + "/" + displayName;
        String sha256 = sha256(m4a);

        Uri uri;
        if (Build.VERSION.SDK_INT >= 29) {
            uri = writeViaMediaStore(context, displayName, relativePath, m4a);
        } else {
            uri = writeLegacyPublicMusic(relativePath, displayName, m4a);
        }
        return new Result(sharedPath, uri, m4a.length, sha256);
    }

    private static Uri writeViaMediaStore(Context context,
                                          String displayName,
                                          String relativePath,
                                          byte[] m4a) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Audio.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4");
        values.put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath);
        values.put(MediaStore.Audio.Media.IS_PENDING, 1);

        Uri inserted = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
        if (inserted == null) {
            throw new IOException("MediaStore audio insert returned null");
        }
        try (OutputStream os = resolver.openOutputStream(inserted, "wt")) {
            if (os == null) {
                throw new IOException("audio openOutputStream returned null");
            }
            os.write(m4a);
            os.flush();
            ContentValues done = new ContentValues();
            done.put(MediaStore.Audio.Media.IS_PENDING, 0);
            resolver.update(inserted, done, null, null);
            return inserted;
        } catch (IOException e) {
            resolver.delete(inserted, null, null);
            throw e;
        }
    }

    private static Uri writeLegacyPublicMusic(String relativePath,
                                              String displayName,
                                              byte[] m4a) throws IOException {
        String child = relativePath.substring(Environment.DIRECTORY_MUSIC.length() + 1);
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), child);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("mkdirs failed: " + dir);
        }
        File out = new File(dir, displayName);
        try (FileOutputStream fos = new FileOutputStream(out, false)) {
            fos.write(m4a);
            fos.flush();
        }
        return Uri.fromFile(out);
    }

    private static String safeRequestId(String requestId) {
        String s = requestId == null ? "request" : requestId.trim();
        if (s.isEmpty()) {
            s = "request";
        }
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String sha256(byte[] data) throws IOException {
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
}
