package com.stonx.camera.bridge;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

final class SharedMediaDeleter {
    static final class Result {
        final boolean ok;
        final String reason;
        final String path;

        Result(boolean ok, String reason, String path) {
            this.ok = ok;
            this.reason = reason == null ? "" : reason;
            this.path = path == null ? "" : path;
        }
    }

    private enum Kind {
        IMAGE,
        VIDEO,
        AUDIO
    }

    private static final class ParsedPath {
        final Kind kind;
        final String relativePath;
        final String displayName;

        ParsedPath(Kind kind, String relativePath, String displayName) {
            this.kind = kind;
            this.relativePath = relativePath;
            this.displayName = displayName;
        }
    }

    private SharedMediaDeleter() {
    }

    static Result delete(Context context, String sharedPath) {
        String normalized = normalizePath(sharedPath);
        if (normalized == null) {
            return new Result(false, "outside_allowed_dir", sharedPath == null ? "" : sharedPath);
        }
        if (Build.VERSION.SDK_INT < 29) {
            return new Result(false, "unsupported_android_version", normalized);
        }

        ParsedPath parsed = parseAllowedPath(normalized);
        if (parsed == null) {
            return new Result(false, "outside_allowed_dir", normalized);
        }

        ContentResolver resolver = context.getContentResolver();
        Uri collection;
        if (parsed.kind == Kind.IMAGE) {
            collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        } else if (parsed.kind == Kind.VIDEO) {
            collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        } else {
            collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        }

        String[] projection = new String[]{MediaStore.MediaColumns._ID};
        String selection = MediaStore.MediaColumns.DISPLAY_NAME + "=? AND "
                + MediaStore.MediaColumns.RELATIVE_PATH + "=?";
        String[] args = new String[]{parsed.displayName, parsed.relativePath};

        long id;
        try (Cursor cursor = resolver.query(collection, projection, selection, args, null)) {
            if (cursor == null) {
                return new Result(false, "query_failed", normalized);
            }
            if (!cursor.moveToFirst()) {
                return new Result(false, "not_found", normalized);
            }
            id = cursor.getLong(0);
            if (cursor.moveToNext()) {
                return new Result(false, "ambiguous", normalized);
            }
        } catch (SecurityException e) {
            return new Result(false, "query_failed_security", normalized);
        } catch (Throwable t) {
            return new Result(false, "query_failed", normalized);
        }

        Uri target = android.content.ContentUris.withAppendedId(collection, id);
        try {
            int deleted = resolver.delete(target, null, null);
            if (deleted > 0) {
                return new Result(true, "deleted", normalized);
            }
            return new Result(false, "delete_failed", normalized);
        } catch (SecurityException e) {
            return new Result(false, "delete_failed_security", normalized);
        } catch (Throwable t) {
            return new Result(false, "delete_failed", normalized);
        }
    }

    private static String normalizePath(String sharedPath) {
        if (sharedPath == null) {
            return null;
        }
        String s = sharedPath.trim();
        if (s.isEmpty()) {
            return null;
        }
        if (s.contains("\\")) {
            return null;
        }
        s = s.replaceAll("/+", "/");
        if (!s.startsWith("/sdcard/")) {
            return null;
        }
        String body = s.substring("/sdcard/".length());
        if (body.isEmpty()) {
            return null;
        }
        String[] parts = body.split("/");
        StringBuilder sb = new StringBuilder("/sdcard");
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                return null;
            }
            if (!part.matches("[A-Za-z0-9._-]+")) {
                return null;
            }
            sb.append('/').append(part);
        }
        return sb.toString();
    }

    private static ParsedPath parseAllowedPath(String normalized) {
        String picturesPrefix = "/sdcard/Pictures/STONX-Camera-Bridge/";
        String moviesPrefix = "/sdcard/Movies/STONX-Camera-Bridge/";
        String musicPrefix = "/sdcard/Music/STONX-Camera-Bridge/";

        if (normalized.startsWith(picturesPrefix) && normalized.length() > picturesPrefix.length()) {
            String name = normalized.substring(picturesPrefix.length());
            if (validGeneratedName(name, ".jpg")) {
                return new ParsedPath(Kind.IMAGE, "Pictures/STONX-Camera-Bridge", name);
            }
            return null;
        }
        if (normalized.startsWith(moviesPrefix) && normalized.length() > moviesPrefix.length()) {
            String name = normalized.substring(moviesPrefix.length());
            if (validGeneratedName(name, ".mp4")) {
                return new ParsedPath(Kind.VIDEO, "Movies/STONX-Camera-Bridge", name);
            }
            return null;
        }
        if (normalized.startsWith(musicPrefix) && normalized.length() > musicPrefix.length()) {
            String name = normalized.substring(musicPrefix.length());
            if (validGeneratedName(name, ".m4a")) {
                return new ParsedPath(Kind.AUDIO, "Music/STONX-Camera-Bridge", name);
            }
            return null;
        }
        return null;
    }

    private static boolean validGeneratedName(String name, String extension) {
        return name != null
                && name.startsWith("STONX_")
                && name.endsWith(extension)
                && name.length() <= 140
                && name.matches("[A-Za-z0-9._-]+");
    }
}

