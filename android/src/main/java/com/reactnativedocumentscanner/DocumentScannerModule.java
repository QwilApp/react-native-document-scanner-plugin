package com.reactnativedocumentscanner;

import android.content.Context;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableNativeArray;
import com.facebook.react.bridge.WritableNativeMap;
import com.facebook.react.module.annotations.ReactModule;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner;
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult.Page;
import java.io.ByteArrayOutputStream;
import java.io.BufferedOutputStream;
import java.io.FileNotFoundException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@ReactModule(name = DocumentScannerModule.NAME)
public class DocumentScannerModule extends ReactContextBaseJavaModule {
    public static final String NAME = "DocumentScanner";
    private Context context;

    public DocumentScannerModule(ReactApplicationContext reactContext) {
        super(reactContext);
        context = reactContext.getApplicationContext();
    }

    @Override
    @NonNull
    public String getName() {
        return NAME;
    }

    void compressImageToStream(Activity currentActivity, Uri imageUri, int compressionQuality, int maxImageSize, OutputStream stream) throws Exception {
        // First, decode image size without loading the full image into memory
        InputStream inStream = currentActivity.getContentResolver().openInputStream(imageUri);
        BitmapFactory.Options inOptions = new BitmapFactory.Options();
        inOptions.inJustDecodeBounds = true;
        BitmapFactory.decodeStream(inStream, null, inOptions);
        inStream.close();

        int imageWidth = inOptions.outWidth;
        int imageHeight = inOptions.outHeight;
        boolean resizeRequired = false;

        // Calculate required scaling
        float scale = Math.min((float)imageWidth / maxImageSize, (float)imageHeight / maxImageSize);
        int inSampleSize = 1;
        if (maxImageSize > 0 && scale > 1) {
            resizeRequired = true;
            imageWidth = Math.round(imageWidth / scale);
            imageHeight = Math.round(imageHeight / scale);
            inSampleSize = calculateInSampleSize(inOptions, imageWidth, imageHeight);
        }

        // now we can load in the full image, subsampling so we only load as much as we need to output at requested scale
        inStream = currentActivity.getContentResolver().openInputStream(imageUri);
        BitmapFactory.Options samplingOptions = new BitmapFactory.Options();
        samplingOptions.inSampleSize = inSampleSize;
        Bitmap bitmap = BitmapFactory.decodeStream(inStream, null, samplingOptions);
        inStream.close();

        // next scale down the dimensions
        if (resizeRequired) {
            bitmap = Bitmap.createScaledBitmap(bitmap, imageWidth, imageHeight, true);
        }

        // and finally write it out to stream as compressed JPEG
        bitmap.compress(Bitmap.CompressFormat.JPEG, compressionQuality, stream);
        bitmap.recycle();
    }

    int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        // From https://developer.android.com/topic/performance/graphics/load-bitmap
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;

            // Calculate the largest inSampleSize value that is a power of 2 and keeps both
            // height and width larger than the requested height and width.
            while ((halfHeight / inSampleSize) >= reqHeight
                    && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }

        return inSampleSize;
    }

    String compressImageToBase64(Activity currentActivity, Uri imageUri, int compressionQuality, int maxImageSize) throws Exception {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        compressImageToStream(currentActivity, imageUri, compressionQuality, maxImageSize, byteArrayOutputStream);
        byte[] byteArray = byteArrayOutputStream.toByteArray();
        return Base64.encodeToString(byteArray, Base64.DEFAULT);
    }

    File compressImageToFile(Activity currentActivity, Uri imageUri, int compressionQuality, int maxImageSize) throws Exception {
        File outDir = this.context.getCacheDir();
        File outFile = new File(outDir, UUID.randomUUID() + ".jpg");
        OutputStream os = new BufferedOutputStream(new FileOutputStream(outFile));

        compressImageToStream(currentActivity, imageUri, compressionQuality, maxImageSize, os);
        os.close();
        return outFile;
    }

    public String getImageInBase64(Activity currentActivity, Uri croppedImageUri, int quality, int maxImageSize) throws FileNotFoundException {
        Bitmap bitmap = BitmapFactory.decodeStream(
            currentActivity.getContentResolver().openInputStream(croppedImageUri)
        );
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, byteArrayOutputStream);
        byte[] byteArray = byteArrayOutputStream.toByteArray();
        return Base64.encodeToString(byteArray, Base64.DEFAULT);
    }

    @ReactMethod
    public void scanDocument(ReadableMap options, Promise promise) {
        Activity currentActivity = getCurrentActivity();
        WritableMap response = new WritableNativeMap();

        GmsDocumentScannerOptions.Builder documentScannerOptionsBuilder = new GmsDocumentScannerOptions.Builder()
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL);

        if (options.hasKey("maxNumDocuments")) {
            documentScannerOptionsBuilder.setPageLimit(
                options.getInt("maxNumDocuments")
            );
        }

        int croppedImageQuality;
        if (options.hasKey("croppedImageQuality")) {
            croppedImageQuality = options.getInt("croppedImageQuality");
        } else {
            croppedImageQuality = 100;
        }

        int maxImageSize;
        if (options.hasKey("maxImageSize")) {
            maxImageSize = options.getInt("maxImageSize");
        } else {
            maxImageSize = 0;  // don't resize
        }

        GmsDocumentScanner scanner = GmsDocumentScanning.getClient(documentScannerOptionsBuilder.build());
        ActivityResultLauncher<IntentSenderRequest> scannerLauncher = ((ComponentActivity) currentActivity).getActivityResultRegistry().register(
                "document-scanner",
                new ActivityResultContracts.StartIntentSenderForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK) {
                        GmsDocumentScanningResult documentScanningResult = GmsDocumentScanningResult.fromActivityResultIntent(
                            result.getData()
                        );
                        WritableArray docScanResults = new WritableNativeArray();

                        if (documentScanningResult != null) {
                            List<Page> pages = documentScanningResult.getPages();
                            if (pages != null) {
                                for (Page page : pages) {
                                    Uri croppedImageUri = page.getImageUri();
                                    try {
                                       String croppedImageResults;
                                       if (options.hasKey("responseType") && Objects.equals(options.getString("responseType"), "base64")) {
                                          croppedImageResults = this.compressImageToBase64(currentActivity, croppedImageUri, croppedImageQuality, maxImageSize);
                                       } else {
                                          File compressedFile = this.compressImageToFile(currentActivity, croppedImageUri, croppedImageQuality, maxImageSize);
                                          croppedImageResults = compressedFile.getPath();
                                       }
                                       docScanResults.pushString(croppedImageResults);
                                    } catch (Exception error) {
                                        promise.reject("document scan error", error.getMessage());
                                    }
                                }
                            }
                        }

                        response.putArray(
                            "scannedImages",
                            docScanResults
                        );
                        response.putString("status", "success");
                        promise.resolve(response);
                    } else if (result.getResultCode() == Activity.RESULT_CANCELED) {
                        // when user cancels document scan
                        response.putString("status", "cancel");
                        promise.resolve(response);
                    }
                }
        );

        scanner.getStartScanIntent(currentActivity)
            .addOnSuccessListener(intentSender ->
                scannerLauncher.launch(new IntentSenderRequest.Builder(intentSender).build()))
            .addOnFailureListener(error -> {
                // document scan error
                promise.reject("document scan error", error.getMessage());
            });
    }
}
