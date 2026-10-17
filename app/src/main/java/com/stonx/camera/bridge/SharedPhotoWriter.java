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

final class SharedPhotoWriter {
    static final class Result {
        final String path;
        final long size;
        final String sha256;

        Result(String path, long size, String sha256) {
            this.path = path;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    private SharedPhotoWriter() {
    }

    static Result writeJpeg(Context context, String requestId, byte[] jpeg) throws IOException {
        if (jpeg == null || jpeg.length == 0) {
            throw new IOException("empty jpeg payload");
        }
        String safeRequestId = safeRequestId(requestId);
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        String displayName = "STONX_" + timestamp + "_" + safeRequestId + ".jpg";
        String relativePath = Environment.DIRECTORY_PICTURES + "/STONX-Camera-Bridge";
        String sharedPath = "/sdcard/" + relativePath + "/" + displayName;
        String sha256 = sha256(jpeg);

        if (Build.VERSION.SDK_INT >= 29) {
            writeViaMediaStore(context, displayName, relativePath, jpeg);
        } else {
            writeLegacyPublicPictures(relativePath, displayName, jpeg);
        }
        return new Result(sharedPath, jpeg.length, sha256);
    }

    private static void writeViaMediaStore(Context context,
                                           String displayName,
                                           String relativePath,
                                           byte[] jpeg) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, relativePath);
        values.put(MediaStore.Images.Media.IS_PENDING, 1);

        Uri inserted = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (inserted == null) {
            throw new IOException("MediaStore image insert returned null");
        }
        try (OutputStream os = resolver.openOutputStream(inserted, "wt")) {
            if (os == null) {
                throw new IOException("image openOutputStream returned null");
            }
            os.write(jpeg);
            os.flush();
            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(inserted, done, null, null);
        } catch (IOException e) {
            resolver.delete(inserted, null, null);
            throw e;
        }
    }

    private static void writeLegacyPublicPictures(String relativePath,
                                                  String displayName,
                                                  byte[] jpeg) throws IOException {
        String child = relativePath.substring(Environment.DIRECTORY_PICTURES.length() + 1);
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), child);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("mkdirs failed: " + dir);
        }
        File out = new File(dir, displayName);
        try (FileOutputStream fos = new FileOutputStream(out, false)) {
            fos.write(jpeg);
            fos.flush();
        }
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
