package com.stonx.camera.bridge;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.JsonWriter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;

final class Stage2StatusWriter {
    private Stage2StatusWriter() {
    }

    static void write(Context context,
                      String requestId,
                      boolean ok,
                      String reason,
                      String path,
                      long sizeBytes) {
        write(context, requestId, ok, reason, path, sizeBytes, "");
    }

    static void write(Context context,
                      String requestId,
                      boolean ok,
                      String reason,
                      String path,
                      long sizeBytes,
                      String sha256) {
        write(context, requestId, ok, reason, path, sizeBytes, sha256, -1, "", -1L, -1);
    }

    static void write(Context context,
                      String requestId,
                      boolean ok,
                      String reason,
                      String path,
                      long sizeBytes,
                      String sha256,
                      int brightness,
                      String aeState,
                      long exposureNs,
                      int iso) {
        write(context, requestId, ok, reason, path, sizeBytes, sha256, brightness, aeState, exposureNs, iso, -1L, -1);
    }

    static void write(Context context,
                      String requestId,
                      boolean ok,
                      String reason,
                      String path,
                      long sizeBytes,
                      String sha256,
                      int brightness,
                      String aeState,
                      long exposureNs,
                      int iso,
                      long durationMs,
                      int signalLevel) {
        String originalRequestId = requestId == null ? "" : requestId.trim();
        String safeRequestId = originalRequestId;
        if (safeRequestId.isEmpty()) {
            safeRequestId = "req-" + System.currentTimeMillis();
        }
        if (!safeRequestId.matches("[A-Za-z0-9._-]{1,80}")) {
            safeRequestId = "req-" + Integer.toHexString(safeRequestId.hashCode());
        }

        String displayName = safeRequestId + ".json";
        String relativePath = Environment.DIRECTORY_DOWNLOADS + "/STONX-Camera-Bridge/inbox";
        String sharedPath = "/sdcard/" + relativePath + "/" + displayName;
        String effectivePath = path == null || path.isEmpty() ? sharedPath : path;
        String json = buildJson(
                originalRequestId,
                ok,
                reason,
                effectivePath,
                sizeBytes,
                sha256,
                brightness,
                aeState,
                exposureNs,
                iso,
                durationMs,
                signalLevel
        );

        try {
            if (Build.VERSION.SDK_INT >= 29) {
                writeViaMediaStore(context, displayName, relativePath, json);
            } else {
                writeLegacyPublicDownloads(relativePath, displayName, json);
            }
        } catch (Exception ignored) {
        }
    }

    private static String buildJson(String requestId,
                                    boolean ok,
                                    String reason,
                                    String path,
                                    long sizeBytes,
                                    String sha256,
                                    int brightness,
                                    String aeState,
                                    long exposureNs,
                                    int iso,
                                    long durationMs,
                                    int signalLevel) {
        StringWriter sw = new StringWriter();
        JsonWriter w = new JsonWriter(sw);
        try {
            w.beginObject();
            w.name("request_id").value(requestId);
            w.name("ok").value(ok);
            w.name("reason").value(reason == null ? "" : reason);
            w.name("path").value(path == null ? "" : path);
            w.name("size").value(sizeBytes);
            w.name("sha256").value(sha256 == null ? "" : sha256);
            w.name("brightness").value(brightness);
            w.name("ae_state").value(aeState == null ? "" : aeState);
            w.name("exposure_ns").value(exposureNs);
            w.name("iso").value(iso);
            w.name("duration_ms").value(durationMs);
            w.name("signal_level").value(signalLevel);
            w.name("ts").value(System.currentTimeMillis());
            w.endObject();
        } catch (IOException e) {
            return "{\"request_id\":\"\",\"ok\":false,\"reason\":\"json_error\",\"path\":\"\",\"size\":0,\"sha256\":\"\",\"brightness\":-1,\"ae_state\":\"\",\"exposure_ns\":-1,\"iso\":-1,\"duration_ms\":-1,\"signal_level\":-1,\"ts\":0}";
        } finally {
            try {
                w.close();
            } catch (IOException ignored) {
            }
        }
        return sw.toString();
    }

    private static void writeViaMediaStore(Context context,
                                           String displayName,
                                           String relativePath,
                                           String json) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;

        Uri existing = findExisting(resolver, collection, displayName, relativePath);
        if (existing != null) {
            try (OutputStream os = resolver.openOutputStream(existing, "wt")) {
                if (os == null) {
                    throw new IOException("openOutputStream returned null");
                }
                os.write(json.getBytes("UTF-8"));
                os.flush();
            }
            return;
        }

        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, displayName);
        values.put(MediaStore.Downloads.MIME_TYPE, "application/json");
        values.put(MediaStore.Downloads.RELATIVE_PATH, relativePath);
        values.put(MediaStore.Downloads.IS_PENDING, 1);

        Uri inserted = resolver.insert(collection, values);
        if (inserted == null) {
            throw new IOException("MediaStore insert returned null");
        }
        try (OutputStream os = resolver.openOutputStream(inserted, "wt")) {
            if (os == null) {
                throw new IOException("openOutputStream returned null");
            }
            os.write(json.getBytes("UTF-8"));
            os.flush();
            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(inserted, done, null, null);
        } catch (IOException e) {
            resolver.delete(inserted, null, null);
            throw e;
        }
    }

    private static Uri findExisting(ContentResolver resolver,
                                    Uri collection,
                                    String displayName,
                                    String relativePath) {
        String[] projection = new String[]{
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.RELATIVE_PATH
        };
        String selection = MediaStore.Downloads.DISPLAY_NAME + "=? AND " + MediaStore.Downloads.RELATIVE_PATH + "=?";
        String[] args = new String[]{displayName, relativePath};
        try (Cursor c = resolver.query(collection, projection, selection, args, null)) {
            if (c != null && c.moveToFirst()) {
                long id = c.getLong(0);
                return ContentUris.withAppendedId(collection, id);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static void writeLegacyPublicDownloads(String relativePath,
                                                   String displayName,
                                                   String json) throws IOException {
        String child = relativePath.substring(Environment.DIRECTORY_DOWNLOADS.length() + 1);
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), child);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("mkdirs failed: " + dir);
        }
        File out = new File(dir, displayName);
        try (FileOutputStream fos = new FileOutputStream(out, false)) {
            fos.write(json.getBytes("UTF-8"));
            fos.flush();
        }
    }
}
