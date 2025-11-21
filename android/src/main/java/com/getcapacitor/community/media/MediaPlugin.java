package com.getcapacitor.community.media;

import android.Manifest;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Base64;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.webkit.MimeTypeMap;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Size;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Logger;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.text.ParseException;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import org.json.JSONObject;

@CapacitorPlugin(
    name = "Media",
    permissions = {
        @Permission(
            strings = { Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE },
            alias = "publicStorage"
        ),
        @Permission(
            strings = { Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO },
            alias = "publicStorage13Plus"
        )
    }
)
public class MediaPlugin extends Plugin {

    private static final String PERMISSION_DENIED_ERROR = "Unable to access media, user denied permission request";

    private static final int API_LEVEL_29 = 29;
    private static final int API_LEVEL_33 = 33;

    public static final String EC_ACCESS_DENIED = "accessDenied";
    public static final String EC_ARG_ERROR = "argumentError";
    public static final String EC_DOWNLOAD_ERROR = "downloadError";
    public static final String EC_FS_ERROR = "filesystemError";

    @PluginMethod
    public void getMedias(PluginCall call) {
        if (isStoragePermissionGranted()) {
            _getMedias(call);
        } else {
            this.bridge.saveCall(call);
            requestAllPermissions(call, "permissionCallback");
        }
    }

    @PluginMethod
    public void getMediaByIdentifier(PluginCall call) {
        if (isStoragePermissionGranted()) {
            _getMediaByIdentifier(call);
        } else {
            this.bridge.saveCall(call);
            requestAllPermissions(call, "permissionCallback");
        }
    }

    @PluginMethod
    public void getAlbums(PluginCall call) {
        Log.d("DEBUG LOG", "GET ALBUMS");
        if (isStoragePermissionGranted()) {
            Log.d("DEBUG LOG", "HAS PERMISSION");
            _getAlbums(call);
        } else {
            Log.d("DEBUG LOG", "NOT ALLOWED");
            this.bridge.saveCall(call);
            requestAllPermissions(call, "permissionCallback");
        }
    }

    @PluginMethod
    public void savePhoto(PluginCall call) {
        Log.d("DEBUG LOG", "SAVE PHOTO TO ALBUM");
        if (isStoragePermissionGranted()) {
            Log.d("DEBUG LOG", "HAS PERMISSION");
            _saveMedia(call);
        } else {
            Log.d("DEBUG LOG", "NOT ALLOWED");
            this.bridge.saveCall(call);
            requestAllPermissions(call, "permissionCallback");
            Log.d("DEBUG LOG", "___SAVE PHOTO TO ALBUM AFTER PERMISSION REQUEST");
        }
    }

    @PluginMethod
    public void saveVideo(PluginCall call) {
        Log.d("DEBUG LOG", "SAVE VIDEO TO ALBUM");
        if (isStoragePermissionGranted()) {
            Log.d("DEBUG LOG", "HAS PERMISSION");
            _saveMedia(call);
        } else {
            Log.d("DEBUG LOG", "NOT ALLOWED");
            this.bridge.saveCall(call);
            requestAllPermissions(call, "permissionCallback");
        }
    }

    @PluginMethod
    public void createAlbum(PluginCall call) {
        Log.d("DEBUG LOG", "CREATE ALBUM");
        if (isStoragePermissionGranted()) {
            Log.d("DEBUG LOG", "HAS PERMISSION");
            _createAlbum(call);
        } else {
            Log.d("DEBUG LOG", "NOT ALLOWED");
            this.bridge.saveCall(call);
            requestAllPermissions(call, "permissionCallback");
        }
    }

    @PermissionCallback
    private void permissionCallback(PluginCall call) {
        if (!isStoragePermissionGranted()) {
            Logger.debug(getLogTag(), "User denied storage permission");
            call.reject("Unable to complete operation; user denied permission request.", EC_ACCESS_DENIED);
            return;
        }

        switch (call.getMethodName()) {
            case "getMedias" -> _getMedias(call);
            case "getMediaByIdentifier" -> _getMediaByIdentifier(call);
            case "getAlbums" -> _getAlbums(call);
            case "savePhoto", "saveVideo" -> _saveMedia(call);
            case "createAlbum" -> _createAlbum(call);
        }
    }

    private boolean isStoragePermissionGranted() {
        String permissionSet = "publicStorage";
        if (Build.VERSION.SDK_INT >= API_LEVEL_33) {
            permissionSet = "publicStorage13Plus";
        }

        return getPermissionState(permissionSet) == PermissionState.GRANTED;
    }

    private void _getAlbums(PluginCall call) {
        Log.d("DEBUG LOG", "___GET ALBUMS");

        JSObject response = new JSObject();
        JSArray albums = new JSArray();
        Set<String> bucketIds = new HashSet<String>();
        Set<String> identifiers = new HashSet<String>();

        String[] projection = new String[] {
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.DATA
        };
        Cursor[] curs = {
            getActivity().getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null, null),
            getActivity().getContentResolver().query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, null, null, null)
        };

        for (Cursor cur : curs) {
            while (cur.moveToNext()) {
                String albumName = cur.getString((Math.max(0, cur.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME))));
                String bucketId = cur.getString((Math.max(0, cur.getColumnIndex(MediaStore.MediaColumns.BUCKET_ID))));

                if (!bucketIds.contains(bucketId)) {
                    String path = cur.getString((Math.max(0, cur.getColumnIndex(MediaStore.MediaColumns.DATA))));
                    File fileForPath = new File(path);
                    JSObject album = new JSObject();

                    album.put("name", albumName);
                    album.put("identifier", fileForPath.getParent());
                    albums.put(album);

                    bucketIds.add(bucketId);
                    identifiers.add(fileForPath.getParent());
                }
            }

            cur.close();
        }

        File albumPath = new File(_getAlbumsPath());
        for (File sub : albumPath.listFiles()) {
            if (sub.isDirectory() && !identifiers.contains(sub.getAbsolutePath())) {
                JSObject album = new JSObject();

                album.put("name", sub.getName());
                album.put("identifier", sub.getAbsolutePath());
                identifiers.add(sub.getAbsolutePath());
                albums.put(album);
            }
        }

        response.put("albums", albums);
        Log.d("DEBUG LOG", String.valueOf(response));
        Log.d("DEBUG LOG", "___GET ALBUMS FINISHED");

        call.resolve(response);
    }

    private void _getMedias(PluginCall call) {
        Log.d("MediaPlugin", "___GET MEDIAS");

        // Parse parameters
        Integer quantity = call.getInt("quantity", 20);
        Integer offset = call.getInt("offset", 0);
        String types = call.getString("types", "photos");
        String sortParam = call.getString("sort", "creationDate");
        String startDateStr = call.getString("startDate");
        String endDateStr = call.getString("endDate");
        Boolean favoritesOnly = call.getBoolean("favoritesOnly", false);
        Integer thumbnailWidth = call.getInt("thumbnailWidth", 512);
        Integer thumbnailHeight = call.getInt("thumbnailHeight", 384);
        Integer thumbnailQuality = call.getInt("thumbnailQuality", 85);

        List<JSObject> mediaList = new ArrayList<>();

        // Determine which content URIs to query based on types
        List<Uri> contentUris = new ArrayList<>();
        if ("videos".equals(types)) {
            contentUris.add(MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
        } else if ("photos".equals(types)) {
            contentUris.add(MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        } else if ("all".equals(types)) {
            contentUris.add(MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            contentUris.add(MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
        } else {
            // Default to photos
            contentUris.add(MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        }

        // Set up projection (columns to retrieve)
        String[] projection = new String[] {
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE
        };

        // Add date columns if available
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection = new String[] {
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.DATE_TAKEN,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.RELATIVE_PATH
            };
        }

        // Build selection criteria
        String selection = null;
        List<String> selectionArgsList = new ArrayList<>();

        if (startDateStr != null && endDateStr != null) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                Date startDate = sdf.parse(startDateStr);
                Date endDate = sdf.parse(endDateStr);

                if (startDate != null && endDate != null) {
                    long startTimestamp = startDate.getTime() / 1000;
                    long endTimestamp = endDate.getTime() / 1000 + 86400;
                    // Add one day in seconds

                    selection = MediaStore.MediaColumns.DATE_ADDED + " >= ? AND " + MediaStore.MediaColumns.DATE_ADDED + " <= ?";
                    selectionArgsList.add(String.valueOf(startTimestamp));
                    selectionArgsList.add(String.valueOf(endTimestamp));
                }
            } catch (ParseException e) {
                Log.e("MediaPlugin", "Error parsing dates", e);
            }
        }

        String[] selectionArgs = selectionArgsList.isEmpty() ? null : selectionArgsList.toArray(new String[0]);

        // Build sort order
        String sortOrder;
        JSArray sortArray = call.getArray("sort");

        if (sortArray != null && sortArray.length() > 0) {
            // Handle array format: [{"key": "creationDate", "ascending": false}]
            List<String> sortDescriptors = new ArrayList<>();
            for (int i = 0; i < sortArray.length(); i++) {
                try {
                    JSONObject sortObj = sortArray.getJSONObject(i);
                    String key = sortObj.getString("key");
                    Boolean ascending = sortObj.getBoolean("ascending", false);

                    // Map the key to Android MediaStore column
                    String column = mapSortKeyToColumn(key);
                    sortDescriptors.add(column + (ascending ? " ASC" : " DESC"));
                } catch (Exception e) {
                    Log.w("MediaPlugin", "Invalid sort object, skipping: " + e.getMessage());
                }
            }
            sortOrder = sortDescriptors.isEmpty()
                ? MediaStore.MediaColumns.DATE_ADDED + " DESC"
                : String.join(", ", sortDescriptors);
        } else {
            // Handle string format: "creationDate"
            String column = mapSortKeyToColumn(sortParam);
            sortOrder = column + " DESC";
        }

        int totalCount = 0;
        int itemsProcessed = 0;

        // Query each content URI (photos, videos, or both)
        for (Uri contentUri : contentUris) {
            Cursor cursor = null;
            try {
                cursor = getActivity().getContentResolver().query(
                    contentUri,
                    projection,
                    selection,
                    selectionArgs,
                    sortOrder
                );

                if (cursor != null) {
                    totalCount += cursor.getCount();

                    if (cursor.getCount() > 0) {
                        // Determine media type based on content URI
                        String mediaType = contentUri.equals(MediaStore.Video.Media.EXTERNAL_CONTENT_URI) ? "video" : "photo";

                        // Skip offset items
                        int skipCount = Math.max(0, offset - itemsProcessed);
                        if (skipCount > 0 && cursor.getCount() > skipCount) {
                            cursor.moveToPosition(skipCount - 1);
                        } else if (skipCount >= cursor.getCount()) {
                            itemsProcessed += cursor.getCount();
                            cursor.close();
                            continue;
                        }

                        while (cursor.moveToNext() && mediaList.size() < quantity) {
                            try {
                                long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID));
                                long dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED));

                                // Build content URI for the media
                                Uri mediaUri = Uri.withAppendedPath(contentUri, String.valueOf(id));

                                // Get identifier - use content URI for all Android versions
                                String identifier = mediaUri.toString();

                                // Generate base64 thumbnail
                                String dataUrl = getThumbnailBase64(mediaUri, thumbnailWidth, thumbnailHeight, thumbnailQuality);

                                if (dataUrl == null) {
                                    Log.w("MediaPlugin", "Could not generate thumbnail for media: " + identifier);
                                    continue;
                                }

                                // Get original media dimensions
                                int[] dimensions = getImageDimensions(mediaUri);

                                // Create media object
                                JSObject media = new JSObject();
                                media.put("identifier", identifier);
                                media.put("dataUrl", dataUrl);
                                media.put("creationDate", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(new Date(dateAdded * 1000)));
                                media.put("fullWidth", dimensions[0]);
                                media.put("fullHeight", dimensions[1]);
                                media.put("thumbnailWidth", thumbnailWidth);
                                media.put("thumbnailHeight", thumbnailHeight);
                                media.put("type", mediaType);
                                media.put("isFavorite", false); // Android doesn't have a native favorites concept

                                // Add location (default to empty)
                                JSObject location = new JSObject();
                                location.put("latitude", 0);
                                location.put("longitude", 0);
                                location.put("heading", 0);
                                location.put("altitude", 0);
                                location.put("speed", 0);
                                media.put("location", location);

                                mediaList.add(media);
                                itemsProcessed++;
                            } catch (Exception e) {
                                Log.e("MediaPlugin", "Error processing media item", e);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.e("MediaPlugin", "Error querying media", e);
                call.reject("Error querying media: " + e.getMessage());
                return;
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }

            // Stop if we've collected enough items
            if (mediaList.size() >= quantity) {
                break;
            }
        }

        // Convert list to JSArray
        JSArray mediasArray = new JSArray();
        for (JSObject media : mediaList) {
            mediasArray.put(media);
        }

        JSObject response = new JSObject();
        response.put("medias", mediasArray);
        response.put("totalCount", totalCount);
        response.put("offset", offset);

        Log.d("MediaPlugin", "___GET MEDIAS FINISHED: " + mediaList.size() + " items of " + totalCount + " total");
        call.resolve(response);
    }

    private void _getMediaByIdentifier(PluginCall call) {
        Log.d("MediaPlugin", "___GET MEDIA BY IDENTIFIER");

        String identifier = call.getString("identifier");
        if (identifier == null) {
            call.reject("Must provide an identifier", EC_ARG_ERROR);
            return;
        }

        Integer width = call.getInt("width");
        Float compression = call.getFloat("compression", 1.0f);

        // Validate compression parameter
        if (compression < 0.0f || compression > 1.0f) {
            call.reject("Invalid compression parameter (must be between 0.0 and 1.0)", EC_ARG_ERROR);
            return;
        }

        try {
            Uri mediaUri = Uri.parse(identifier);

            // Determine if this is a video or image
            boolean isVideo = identifier.contains("video");

            if (isVideo) {
                // Handle video
                handleVideoByIdentifier(call, mediaUri);
            } else {
                // Handle image
                handleImageByIdentifier(call, mediaUri, width, compression);
            }
        } catch (Exception e) {
            Log.e("MediaPlugin", "Error getting media by identifier", e);
            call.reject("Error getting media: " + e.getMessage(), EC_ARG_ERROR);
        }
    }

    private void handleImageByIdentifier(PluginCall call, Uri mediaUri, Integer width, Float compression) {
        try {
            // Load the full image
            Bitmap fullImage = MediaStore.Images.Media.getBitmap(
                getActivity().getContentResolver(),
                mediaUri
            );

            if (fullImage == null) {
                call.reject("Failed to load image", EC_ARG_ERROR);
                return;
            }

            int originalWidth = fullImage.getWidth();
            int originalHeight = fullImage.getHeight();
            Bitmap processedImage = fullImage;

            // Resize if width parameter is provided and image is larger
            if (width != null && originalWidth > width) {
                float scale = (float) width / originalWidth;
                int newHeight = Math.round(originalHeight * scale);
                processedImage = Bitmap.createScaledBitmap(fullImage, width, newHeight, true);

                // Recycle original if we created a new one
                if (processedImage != fullImage) {
                    fullImage.recycle();
                }
            }

            // Compress to JPEG
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            int quality = Math.round(compression * 100);
            processedImage.compress(Bitmap.CompressFormat.JPEG, quality, outputStream);
            byte[] imageBytes = outputStream.toByteArray();

            // Create base64 data URL
            String base64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP);
            String dataUrl = "data:image/jpeg;base64," + base64;

            // Save to temporary file
            File tempFile = File.createTempFile(
                "image-" + System.currentTimeMillis(),
                ".jpg",
                getContext().getCacheDir()
            );

            FileOutputStream fos = new FileOutputStream(tempFile);
            fos.write(imageBytes);
            fos.close();

            // Clean up bitmap
            processedImage.recycle();

            // Return result
            JSObject result = new JSObject();
            result.put("identifier", mediaUri.toString());
            result.put("path", "file://" + tempFile.getAbsolutePath());
            result.put("dataUrl", dataUrl);

            Log.d("MediaPlugin", "___GET MEDIA BY IDENTIFIER FINISHED");
            call.resolve(result);

        } catch (IOException e) {
            Log.e("MediaPlugin", "Error processing image", e);
            call.reject("Error processing image: " + e.getMessage(), EC_FS_ERROR);
        }
    }

    private void handleVideoByIdentifier(PluginCall call, Uri mediaUri) {
        try {
            // For video, we'll just copy it to a temp file and return the path
            // We don't resize or compress videos

            String[] projection = {MediaStore.Video.Media.DATA};
            Cursor cursor = getActivity().getContentResolver().query(
                mediaUri,
                projection,
                null,
                null,
                null
            );

            if (cursor != null && cursor.moveToFirst()) {
                int columnIndex = cursor.getColumnIndex(MediaStore.Video.Media.DATA);
                String videoPath = cursor.getString(columnIndex);
                cursor.close();

                JSObject result = new JSObject();
                result.put("identifier", mediaUri.toString());
                result.put("path", "file://" + videoPath);

                Log.d("MediaPlugin", "___GET VIDEO BY IDENTIFIER FINISHED");
                call.resolve(result);
            } else {
                if (cursor != null) {
                    cursor.close();
                }
                call.reject("Failed to get video data", EC_ARG_ERROR);
            }
        } catch (Exception e) {
            Log.e("MediaPlugin", "Error processing video", e);
            call.reject("Error processing video: " + e.getMessage(), EC_FS_ERROR);
        }
    }

    @PluginMethod
    public void getAlbumsPath(PluginCall call) {
        JSObject data = new JSObject();
        data.put("path", _getAlbumsPath());
        call.resolve(data);
    }

    private String _getAlbumsPath() {
        if (Build.VERSION.SDK_INT >= API_LEVEL_29) {
            return getContext().getExternalMediaDirs()[0].getAbsolutePath();
        } else {
            return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).getAbsolutePath();
        }
    }

    private void _saveMedia(PluginCall call) {
        Log.d("DEBUG LOG", "___SAVE MEDIA TO ALBUM");
        String inputPath = call.getString("path");
        if (inputPath == null) {
            call.reject("Input file path is required", EC_ARG_ERROR);
            return;
        }

        File inputFile;

        if (inputPath.startsWith("data:")) {
            try {
                String base64EncodedString = inputPath.substring(inputPath.indexOf(",") + 1);
                byte[] decodedBytes = Base64.decode(base64EncodedString, Base64.DEFAULT);
                String mime = inputPath.split(";", 2)[0].split(":")[1];
                String extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
                if (extension == null || extension.isEmpty()) {
                    call.reject("Cannot identify media type to save image.", EC_ARG_ERROR);
                    return;
                }

                try {
                    inputFile = File.createTempFile(
                            "tmp",
                            "." + extension,
                            getContext().getCacheDir()
                    );
                    OutputStream os = new FileOutputStream(inputFile);
                    os.write(decodedBytes);
                    os.close();
                } catch (IOException e) {
                    call.reject("Temporary file creation from data URL failed", EC_FS_ERROR);
                    return;
                }
            } catch (Exception e) {
                call.reject("Data URL parsing failed.", EC_ARG_ERROR);
                return;
            }
        } else if (inputPath.startsWith("http://") || inputPath.startsWith("https://")) {
            OkHttpClient client = new OkHttpClient();
            Request okrequest = new Request.Builder().url(inputPath).build();
            try {
                // Download image
                Response response = client.newCall(okrequest).execute();
                if (!response.isSuccessful() || response.body() == null) {
                    throw new IOException();
                }

                // Get file extension from URL
                String extension = MimeTypeMap.getFileExtensionFromUrl(inputPath);
                // If it doesn't have it there,
                // attempt to pull extension from MIME type
                if (extension.isEmpty()) {
                    ResponseBody body = response.body();
                    if (body == null) {
                        call.reject("Download failed", EC_DOWNLOAD_ERROR);
                        return;
                    }

                    MediaType mt = body.contentType();
                    if (mt == null) {
                        call.reject("Cannot identify media type to save image.", EC_ARG_ERROR);
                        return;
                    }

                    String mime = mt.type() + "/" + mt.subtype();
                    extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
                }

                // Still no extension? reject
                if (extension == null || extension.isEmpty()) {
                    call.reject("Cannot identify media type to save image.", EC_ARG_ERROR);
                    return;
                }

                // Save to temp file
                try {
                    inputFile = File.createTempFile("tmp", "." + extension, getContext().getCacheDir());
                    OutputStream os = new FileOutputStream(inputFile);
                    os.write(response.body().bytes());
                    os.close();
                } catch (IOException e) {
                    call.reject("Saving download to device failed.", EC_FS_ERROR);
                    return;
                }
            } catch (IOException e) {
                call.reject("Download failed", EC_DOWNLOAD_ERROR);
                return;
            }
        } else {
            Uri inputUri = Uri.parse(inputPath);
            inputFile = new File(inputUri.getPath());
        }

        String album = call.getString("albumIdentifier");
        File albumDir = null;
        Log.d("SDK BUILD VERSION", String.valueOf(Build.VERSION.SDK_INT));

        if (album != null) {
            albumDir = new File(album);
        } else {
            call.reject("Album identifier required", EC_ARG_ERROR);
            return;
        }

        if (!albumDir.exists() || !albumDir.isDirectory()) {
            call.reject("Album identifier does not exist, use getAlbums() to get", EC_ARG_ERROR);
            return;
        }

        Log.d("ENV LOG - ALBUM DIR", String.valueOf(albumDir));

        try {
            // generate image file name using current date and time
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmssSSS").format(new Date());
            String fileName = call.getString("fileName", "IMG_" + timeStamp);
            File expFile = copyFile(inputFile, albumDir, fileName);
            scanPhoto(expFile);

            JSObject result = new JSObject();
            result.put("filePath", expFile.toString());
            call.resolve(result);
        } catch (RuntimeException e) {
            call.reject("Error occurred: " + e, EC_ARG_ERROR);
            return;
        }
    }

    private void _createAlbum(PluginCall call) {
        Log.d("DEBUG LOG", "___CREATE ALBUM");
        String folderName = call.getString("name");

        if (folderName == null) {
            call.reject("Album name must be given!", EC_ARG_ERROR);
            return;
        }

        File f = new File(_getAlbumsPath(), folderName);

        if (!f.exists()) {
            if (!f.mkdir()) {
                Log.d("DEBUG LOG", "___ERROR ALBUM");
                call.reject("Cant create album", EC_FS_ERROR);
            } else {
                Log.d("DEBUG LOG", "___SUCCESS ALBUM CREATED");
                call.resolve();
            }
        } else {
            Log.d("DEBUG LOG", "___ERROR ALBUM ALREADY EXISTS");
            call.reject("Album already exists", EC_FS_ERROR);
        }
    }

    private File copyFile(File inputFile, File albumDir, String fileName) {
        // if destination folder does not exist, create it
        if (!albumDir.exists()) {
            if (!albumDir.mkdir()) {
                throw new RuntimeException("Destination folder does not exist and cannot be created.");
            }
        }

        String absolutePath = inputFile.getAbsolutePath();
        String extension = absolutePath.substring(absolutePath.lastIndexOf("."));

        File newFile = new File(albumDir, fileName + extension);

        // Read and write image files
        FileChannel inChannel = null;
        FileChannel outChannel = null;

        try {
            inChannel = new FileInputStream(inputFile).getChannel();
        } catch (FileNotFoundException e) {
            throw new RuntimeException("Source file not found: " + inputFile + ", error: " + e.getMessage());
        }
        try {
            outChannel = new FileOutputStream(newFile).getChannel();
        } catch (FileNotFoundException e) {
            throw new RuntimeException("Copy file not found: " + newFile + ", error: " + e.getMessage());
        }

        try {
            inChannel.transferTo(0, inChannel.size(), outChannel);
        } catch (IOException e) {
            throw new RuntimeException("Error transfering file, error: " + e.getMessage());
        } finally {
            if (inChannel != null) {
                try {
                    inChannel.close();
                } catch (IOException e) {
                    Log.d("SaveImage", "Error closing input file channel: " + e.getMessage());
                    // does not harm, do nothing
                }
            }
            if (outChannel != null) {
                try {
                    outChannel.close();
                } catch (IOException e) {
                    Log.d("SaveImage", "Error closing output file channel: " + e.getMessage());
                    // does not harm, do nothing
                }
            }
        }

        return newFile;
    }

    private void scanPhoto(File imageFile) {
        Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
        Uri contentUri = Uri.fromFile(imageFile);
        mediaScanIntent.setData(contentUri);
        bridge.getActivity().sendBroadcast(mediaScanIntent);
    }

    private String getThumbnailBase64(Uri imageUri, int thumbnailWidth, int thumbnailHeight, int quality) {
        try {
            Bitmap thumbnail;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // For Android 10+ use loadThumbnail
                Size size = new Size(thumbnailWidth, thumbnailHeight);
                thumbnail = getActivity().getContentResolver().loadThumbnail(imageUri, size, null);
            } else {
                // For older versions, manually load and scale
                Bitmap fullImage = MediaStore.Images.Media.getBitmap(getActivity().getContentResolver(), imageUri);
                if (fullImage == null) {
                    return null;
                }

                // Calculate scaling
                float scale = Math.min(
                    (float) thumbnailWidth / fullImage.getWidth(),
                    (float) thumbnailHeight / fullImage.getHeight()
                );

                int scaledWidth = Math.round(fullImage.getWidth() * scale);
                int scaledHeight = Math.round(fullImage.getHeight() * scale);

                thumbnail = Bitmap.createScaledBitmap(fullImage, scaledWidth, scaledHeight, true);
                fullImage.recycle();
            }

            if (thumbnail == null) {
                return null;
            }

            // Convert to base64
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            thumbnail.compress(Bitmap.CompressFormat.JPEG, quality, byteArrayOutputStream);
            byte[] byteArray = byteArrayOutputStream.toByteArray();
            thumbnail.recycle();

            String base64 = Base64.encodeToString(byteArray, Base64.NO_WRAP);
            return "data:image/jpeg;base64," + base64;
        } catch (Exception e) {
            Log.e("MediaPlugin", "Error generating thumbnail", e);
            return null;
        }
    }

    private int[] getImageDimensions(Uri imageUri) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

                getActivity().getContentResolver().openInputStream(imageUri);
                BitmapFactory.decodeStream(getActivity().getContentResolver().openInputStream(imageUri), null, options);
            } else {
                BitmapFactory.decodeFile(imageUri.getPath(), options);
            }

            return new int[]{options.outWidth, options.outHeight};
        } catch (Exception e) {
            Log.e("MediaPlugin", "Error getting image dimensions", e);
            return new int[]{0, 0};
        }
    }

    private String mapSortKeyToColumn(String key) {
        switch (key) {
            case "creationDate":
                // Use DATE_TAKEN on Android Q+ for more accurate creation date
                return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? MediaStore.MediaColumns.DATE_TAKEN
                    : MediaStore.MediaColumns.DATE_ADDED;
            case "modificationDate":
                return MediaStore.MediaColumns.DATE_MODIFIED;
            case "width":
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    return MediaStore.MediaColumns.WIDTH;
                }
                return MediaStore.MediaColumns.DATE_ADDED; // Fallback for older versions
            case "height":
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    return MediaStore.MediaColumns.HEIGHT;
                }
                return MediaStore.MediaColumns.DATE_ADDED; // Fallback for older versions
            default:
                return MediaStore.MediaColumns.DATE_ADDED;
        }
    }
}
