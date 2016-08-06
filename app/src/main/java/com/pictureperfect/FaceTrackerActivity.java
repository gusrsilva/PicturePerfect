/*
 * Copyright (C) The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.pictureperfect;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.hardware.Camera;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.support.design.widget.Snackbar;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.res.ResourcesCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.util.SparseArray;
import android.view.Display;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.vision.Frame;
import com.google.android.gms.vision.MultiProcessor;
import com.google.android.gms.vision.Tracker;
import com.google.android.gms.vision.face.Face;
import com.google.android.gms.vision.face.FaceDetector;
import com.pictureperfect.ui.camera.CameraSource;
import com.pictureperfect.ui.camera.CameraSourcePreview;
import com.pictureperfect.ui.camera.GraphicOverlay;
import com.squareup.picasso.Picasso;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;

//import com.google.android.gms.vision.CameraSource;

/**
 * Activity for the face tracker app.  This app detects faces with the rear facing camera, and draws
 * overlay graphics to indicate the position, size, and ID of each face.
 */
public final class FaceTrackerActivity extends AppCompatActivity {
    private CameraSource mCameraSource = null;
    private CameraSourcePreview mPreview;
    private GraphicOverlay mGraphicOverlay;
    private Button mCameraButton;
    private Button addMinSmile;
    private Button subMinSmile;
    private ImageView mThumbnail;
    private ImageButton flipButton;
    private ImageButton settingsButton;
    private ImageView flash;

    private final int RC_HANDLE_GMS = 9001;
    // permission request codes need to be < 256
    private final int RC_HANDLE_CAMERA_PERM = 2;
    private final int RC_HANDLE_STORAGE_PERM = 3;

    private final String IMAGE_DIRECTORY = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES) + "/calHacks/";

    private int numPics = 1;
    private float eyeProb = (float) 0.5;
    private float smileThreshold = (float) 0.8;

    private volatile int smilers = 0;
    private boolean captureSmilers = false;
    private boolean blinkProof = true;
    private boolean retake = false;
    private boolean showOverlay = true;
    private volatile int faces = 0;
    private int minSmiles = 1;
    private int minFaces = 1;
    private volatile int count = 0;
    private AnimatorSet mAnimationSet;

    private long global_time = System.currentTimeMillis();
    private long TIME_BETWEEN_THRESHOLD = 2000;
    private int CAMERA_FACING_BACK = CameraSource.CAMERA_FACING_BACK;
    private int CAMERA_FACING_FRONT = CameraSource.CAMERA_FACING_FRONT;
    private String TAG = "CalHacks";

    private File[] mImagesTaken;

    /**
     * Initializes the UI and initiates the creation of a face detector.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        final TextView title = (TextView) findViewById(R.id.title_text);
        title.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                if (title.getWidth() != 0) {
                    int gradientWidth = title.getWidth();
                    Log.d(TAG, "gradientWidth: " + gradientWidth);
                    title.getPaint().setShader(
                            new LinearGradient(0, 0, gradientWidth, 0
                                    , ResourcesCompat.getColor(getResources(), R.color.camera_button_start, null)
                                    , ResourcesCompat.getColor(getResources(), R.color.camera_button_end, null)
                                    , Shader.TileMode.REPEAT));
                    title.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                }
            }
        });


        updateSettings();
        initializeDrawables();
        initializeAnimation();

        addMinSmile = (Button) findViewById(R.id.addMinFaces);
        if (addMinSmile != null) {
            addMinSmile.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    minSmiles++;
                    Log.d("Calhacks", "minFaces: " + minSmiles);
                }
            });
        }
        subMinSmile = (Button) findViewById(R.id.subMinFaces);
        if (subMinSmile != null) {
            subMinSmile.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (minSmiles > 1) {
                        minSmiles--;
                        Log.d("Calhacks", "minFaces: " + minSmiles);
                    }
                }
            });
        }

        // Check for the camera permission before accessing the camera.  If the
        // permission is not granted yet, request permission.
        int rc = ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA);
        if (rc == PackageManager.PERMISSION_GRANTED) {
            createCameraSource(CameraSource.CAMERA_FACING_FRONT);
        } else {
            requestCameraPermission();
        }

        rc = ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE);
        if (rc != PackageManager.PERMISSION_GRANTED) {
            requestStoragePermission();
        }

    }

    public void setCaptureSmilers(boolean bool) {
        captureSmilers = bool;
        mCameraButton.setActivated(bool);
    }

    private void updateSettings() {
        SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (sharedPrefs != null) {
            blinkProof = sharedPrefs.getBoolean("blinkProof", true);
            minFaces = sharedPrefs.getInt("minFaces", 1);
            smileThreshold = (float) sharedPrefs.getInt("smileThreshold", 80) / (float) 100.;
            eyeProb = (float) sharedPrefs.getInt("blinkThreshold", 50) / (float) 100.;
            showOverlay = sharedPrefs.getBoolean("showOverlay", true);
        }
    }

    public void initializeDrawables() {
        mPreview = (CameraSourcePreview) findViewById(R.id.preview);
        mGraphicOverlay = (GraphicOverlay) findViewById(R.id.faceOverlay);
        mCameraButton = (Button) findViewById(R.id.camera_button);
        mThumbnail = (ImageView) findViewById(R.id.thumbnail);
        flipButton = (ImageButton) findViewById(R.id.flipButton);
        flash = (ImageView) findViewById(R.id.flash);
        flash.setVisibility(View.INVISIBLE);
        settingsButton = (ImageButton) findViewById(R.id.settingsButton);

        if (settingsButton != null) {
            settingsButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent intent = new Intent(getApplicationContext(), SettingsActivity.class);
                    startActivity(intent);
                }
            });
        }
        initializeThumbnail();
        if (mCameraButton != null) {
            mCameraButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (captureSmilers) {
                        setCaptureSmilers(false);
                    } else {
                        setCaptureSmilers(true);
                    }
                }
            });
        }
        if (mThumbnail != null) {
            mThumbnail.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (getImagesTaken() != null) {
                        Intent intent = new Intent();
                        intent.setAction(android.content.Intent.ACTION_VIEW);
                        intent.setDataAndType(Uri.fromFile(new File(IMAGE_DIRECTORY)), "image/*");
                        startActivity(intent);
                    } else
                        Toast.makeText(getApplicationContext(), "Take a picture!", Toast.LENGTH_SHORT).show();
                }
            });
        }
        if (flipButton != null) {
            flipButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    flipCamera();
                }
            });
        }
    }

    private void initializeThumbnail() {
        File[] files = getImagesTaken();
        if (files == null) {
            Log.d(TAG, "file list is null");
            return;
        }

        File latestImage = files[files.length - 1];
        String latestImagePath = latestImage.getAbsolutePath();
        Log.d(TAG, "latestImagePath: " + latestImagePath);
        Picasso.with(this).load("file://" + latestImagePath).transform(new CircleTransform()).into(mThumbnail);

    }

    public void initializeAnimation() {
        if (flash != null) {
            ObjectAnimator fadeOut = ObjectAnimator.ofFloat(flash, "alpha", 7f, 0f);
            fadeOut.setDuration(250);
            ObjectAnimator fadeIn = ObjectAnimator.ofFloat(flash, "alpha", 0f, 7f);
            fadeIn.setDuration(250);

            mAnimationSet = new AnimatorSet();

            mAnimationSet.play(fadeOut).after(fadeIn);

            mAnimationSet.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationStart(Animator animation) {
                    flash.setVisibility(View.VISIBLE);
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    super.onAnimationEnd(animation);
                    Toast.makeText(getApplicationContext(), "Picture Taken", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    public void flipCamera() {
        resetVars();
        if (mCameraSource == null) {
            Toast.makeText(getApplicationContext(), "Null CameraSource", Toast.LENGTH_SHORT).show();
            return;
        }

        int facing = mCameraSource.getCameraFacing();
        mCameraSource.release();
        mCameraSource = null;
        if (facing == CAMERA_FACING_BACK)
            createCameraSource(CAMERA_FACING_FRONT);
        else
            createCameraSource(CAMERA_FACING_BACK);

        startCameraSource();
    }

    public void resetVars() {
        numPics = 1;
        smilers = 0;
        setCaptureSmilers(false);
        retake = false;
        faces = 0;
        minSmiles = 1;
        count = 0;
        updateSettings();
    }

    public void takePicture() {

        mCameraSource.takePicture(new CameraSource.ShutterCallback() {
            @Override
            public void onShutter() {
                mAnimationSet.start();
            }
        }, new CameraSource.PictureCallback() {
            @Override
            public void onPictureTaken(byte[] bytes) {
                SavePictureTask save = new SavePictureTask();
                save.execute(bytes);
            }
        });
    }

    /**
     * Handles the requesting of the camera permission.  This includes
     * showing a "Snackbar" message of why the permission is needed then
     * sending the request.
     */
    private void requestCameraPermission() {
        Log.w(TAG, "Camera permission is not granted. Requesting permission");

        final String[] permissions = new String[]{Manifest.permission.CAMERA};

        if (!ActivityCompat.shouldShowRequestPermissionRationale(this,
                Manifest.permission.CAMERA)) {
            ActivityCompat.requestPermissions(this, permissions, RC_HANDLE_CAMERA_PERM);
            return;
        }

        final Activity thisActivity = this;

        View.OnClickListener listener = new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ActivityCompat.requestPermissions(thisActivity, permissions,
                        RC_HANDLE_CAMERA_PERM);
            }
        };

        Snackbar.make(mGraphicOverlay, R.string.permission_camera_rationale,
                Snackbar.LENGTH_INDEFINITE)
                .setAction(R.string.ok, listener)
                .show();
    }

    /**
     * Handles the requesting of the storage permission.  This includes
     * showing a "Snackbar" message of why the permission is needed then
     * sending the request.
     */
    private void requestStoragePermission() {
        Log.w(TAG, "Storage permission is not granted. Requesting permission");

        final String[] permissions = new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};

        if (!ActivityCompat.shouldShowRequestPermissionRationale(this,
                Manifest.permission.READ_EXTERNAL_STORAGE)) {
            ActivityCompat.requestPermissions(this, permissions, RC_HANDLE_STORAGE_PERM);
            return;
        }

        final Activity thisActivity = this;

        View.OnClickListener listener = new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ActivityCompat.requestPermissions(thisActivity, permissions,
                        RC_HANDLE_STORAGE_PERM);
            }
        };

        Snackbar.make(mGraphicOverlay, R.string.permission_camera_rationale,
                Snackbar.LENGTH_INDEFINITE)
                .setAction(R.string.ok, listener)
                .show();
    }

    /**
     * Creates and starts the camera.  Note that this uses a higher resolution in comparison
     * to other detection examples to enable the barcode detector to detect small barcodes
     * at long distances.
     */
    private void createCameraSource(int facing) {

        Context context = getApplicationContext();
        FaceDetector detector = new FaceDetector.Builder(context)
                .setClassificationType(FaceDetector.ALL_CLASSIFICATIONS)
                .setMode(FaceDetector.FAST_MODE)
                .build();

        detector.setProcessor(
                new MultiProcessor.Builder<>(new GraphicFaceTrackerFactory())
                        .build());

        if (!detector.isOperational()) {
            // Note: The first time that an app using face API is installed on a device, GMS will
            // download a native library to the device in order to do detection.  Usually this
            // completes before the app is run for the first time.  But if that download has not yet
            // completed, then the above call will not detect any faces.
            //
            // isOperational() can be used to check if the required native library is currently
            // available.  The detector will automatically become operational once the library
            // download completes on device.
            Log.w(TAG, "Face detector dependencies are not yet available.");
        }

        Display display = getWindowManager().getDefaultDisplay();
        int width = display.getWidth();  // deprecated
        int height = display.getHeight();

        mCameraSource = new CameraSource.Builder(context, detector)
                .setRequestedPreviewSize(width, height)
                .setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)
                .setFacing(facing)
                .setRequestedFps(32.0f)
                .build();
    }

    /**
     * Restarts the camera.
     */
    @Override
    protected void onResume() {
        super.onResume();

        startCameraSource();
    }

    /**
     * Stops the camera.
     */
    @Override
    protected void onPause() {
        super.onPause();
        mPreview.stop();
    }

    /**
     * Releases the resources associated with the camera source, the associated detector, and the
     * rest of the processing pipeline.
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mCameraSource != null) {
            mCameraSource.release();
        }
    }

    /**
     * Callback for the result from requesting permissions. This method
     * is invoked for every call on {@link #requestPermissions(String[], int)}.
     * <p>
     * <strong>Note:</strong> It is possible that the permissions request interaction
     * with the user is interrupted. In this case you will receive empty permissions
     * and results arrays which should be treated as a cancellation.
     * </p>
     *
     * @param requestCode  The request code passed in {@link #requestPermissions(String[], int)}.
     * @param permissions  The requested permissions. Never null.
     * @param grantResults The grant results for the corresponding permissions
     *                     which is either {@link PackageManager#PERMISSION_GRANTED}
     *                     or {@link PackageManager#PERMISSION_DENIED}. Never null.
     * @see #requestPermissions(String[], int)
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != RC_HANDLE_CAMERA_PERM) {
            Log.d(TAG, "Got unexpected permission result: " + requestCode);
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            return;
        }

        if (grantResults.length != 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Camera permission granted - initialize the camera source");
            // we have permission, so create the camerasource
            createCameraSource(CameraSource.CAMERA_FACING_FRONT);
            return;
        }

        Log.e(TAG, "Permission not granted: results len = " + grantResults.length +
                " Result code = " + (grantResults.length > 0 ? grantResults[0] : "(empty)"));

        DialogInterface.OnClickListener listener = new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
                finish();
            }
        };

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Face Tracker sample")
                .setMessage(R.string.no_camera_permission)
                .setPositiveButton(R.string.ok, listener)
                .show();
    }

    //==============================================================================================
    // Camera Source Preview
    //==============================================================================================

    /**
     * Starts or restarts the camera source, if it exists.  If the camera source doesn't exist yet
     * (e.g., because onResume was called before the camera source was created), this will be called
     * again when the camera source is created.
     */
    private void startCameraSource() {

        // check that the device has play services available.
        int code = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(
                getApplicationContext());
        if (code != ConnectionResult.SUCCESS) {
            Dialog dlg =
                    GoogleApiAvailability.getInstance().getErrorDialog(this, code, RC_HANDLE_GMS);
            dlg.show();
        }

        if (mCameraSource != null) {
            try {
                mPreview.start(mCameraSource, mGraphicOverlay);
            } catch (IOException e) {
                Log.e(TAG, "Unable to start camera source.", e);
                mCameraSource.release();
                mCameraSource = null;
            }
        }
    }

    private File[] getImagesTaken()
    {
        if(mImagesTaken == null) {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "/calHacks");
            mImagesTaken = dir.listFiles();
        }
        return mImagesTaken;
    }

    //==============================================================================================
    // Graphic Face Tracker
    //==============================================================================================

    /**
     * Factory for creating a face tracker to be associated with a new face.  The multiprocessor
     * uses this factory to create face trackers as needed -- one for each individual.
     */
    private class GraphicFaceTrackerFactory implements MultiProcessor.Factory<Face> {
        @Override
        public Tracker<Face> create(Face face) {
            return new GraphicFaceTracker(mGraphicOverlay);
        }
    }

    /**
     * Face tracker for each detected individual. This maintains a face graphic within the app's
     * associated face overlay.
     */
    private class GraphicFaceTracker extends Tracker<Face> {
        private GraphicOverlay mOverlay;
        private FaceGraphic mFaceGraphic;
        private boolean smiling = false;

        GraphicFaceTracker(GraphicOverlay overlay) {
            mOverlay = overlay;
            mFaceGraphic = new FaceGraphic(overlay);
        }

        /**
         * Start tracking the detected face instance within the face overlay.
         */
        @Override
        public void onNewItem(int faceId, Face item) {
            mFaceGraphic.setId(faceId);
            faces++;
            count = 0;
        }

        /**
         * Update the position/characteristics of the face within the overlay.
         */
        @Override
        public void onUpdate(FaceDetector.Detections<Face> detectionResults, Face face) {
            if (showOverlay) {
                mOverlay.add(mFaceGraphic);
                mFaceGraphic.updateFace(face);
            } else if (mOverlay != null) {
                mOverlay.clear();
                mOverlay = null;
            }

            if (face.getIsSmilingProbability() > smileThreshold && !smiling) {
                smilers++;
                smiling = true;
            } else if (face.getIsSmilingProbability() < smileThreshold && smiling) {
                smilers--;
                count = 0;
                smiling = false;
            }

            if (checkConditions()) {
                Log.d("Calhacks", "Smilers: " + smilers + " Faces: " + faces + " Count = " + ++count);
                takePicture();
            }
        }

        public boolean checkConditions() {
            if (retake && System.currentTimeMillis() > global_time + TIME_BETWEEN_THRESHOLD && smilers >= minSmiles) {
                return true;
            }
            return smilers >= minSmiles && captureSmilers && count < numPics && faces >= minFaces;
        }

        /**
         * Hide the graphic when the corresponding face was not detected.  This can happen for
         * intermediate frames temporarily (e.g., if the face was momentarily blocked from
         * view).
         */
        @Override
        public void onMissing(FaceDetector.Detections<Face> detectionResults) {
            mOverlay.remove(mFaceGraphic);
        }

        /**
         * Called when the face is assumed to be gone for good. Remove the graphic annotation from
         * the overlay.
         */
        @Override
        public void onDone() {
            mOverlay.remove(mFaceGraphic);
            faces--;
            count = 0;
        }
    }

    public class SavePictureTask extends AsyncTask<byte[], Integer, ArrayList<Object>> {

        @Override
        protected void onPreExecute() {
            //Do Nothing, A placeholder for potential code
        }

        @Override
        protected ArrayList<Object> doInBackground(byte[]... params) {
            byte[] bytes = params[0];
            ArrayList<Object> objs = new ArrayList<>();

            //Toast.makeText(getApplicationContext(), "Picture taken...", Toast.LENGTH_SHORT).show();
            if (bytes == null) {
                //Toast.makeText(getApplicationContext(), "Null bytes", Toast.LENGTH_SHORT).show();
                objs.add("Failed");
                return objs;
            }
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bitmap == null) {
                //Toast.makeText(getApplicationContext(), "Null bitmap", Toast.LENGTH_SHORT).show();
                objs.add("Failed");
                return objs;
            }

            // If someone is blinking with both eyes, take another picture.
            if (blinkProof) {
                FaceDetector detector = new FaceDetector.Builder(getApplicationContext())
                        .setTrackingEnabled(false)
                        .setClassificationType(FaceDetector.ALL_CLASSIFICATIONS)
                        .setMode(FaceDetector.ACCURATE_MODE)
                        .setLandmarkType(FaceDetector.NO_LANDMARKS)
                        .build();

                Frame frame = new Frame.Builder().setBitmap(bitmap).build();
                SparseArray<Face> faces = detector.detect(frame);
                for (int i = 0; i < faces.size(); ++i) {
                    Face face = faces.valueAt(i);
                    float leftEyeProb = face.getIsLeftEyeOpenProbability();
                    float rightEyeProb = face.getIsRightEyeOpenProbability();
                    float smileProb = face.getIsSmilingProbability();
                    Log.d("Calhacks", "Left: " + leftEyeProb + " Right: " + rightEyeProb + " Smile: " + smileProb);
                    if (leftEyeProb < eyeProb && rightEyeProb < eyeProb && leftEyeProb >= 0) {
                        retake = true;
                        //count--;
                        global_time = System.currentTimeMillis();
                        Log.d("Calhacks", "Don't blink!");
                        objs.add("Blinked");
                        return objs;
                    }
                }
            }

            File newdir = new File(IMAGE_DIRECTORY);
            if (!newdir.isDirectory()) {
                newdir.mkdirs();
            }
            String filePath = IMAGE_DIRECTORY + System.currentTimeMillis() + ".jpg";
            File file = new File(filePath);
            try {
                FileOutputStream mFileOutputStream = new FileOutputStream(file);
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, mFileOutputStream);

                mFileOutputStream.flush();
                mFileOutputStream.close();

                objs.add(filePath);
                objs.add(bitmap);

                retake = false;
                return objs;
            } catch (IOException e) {
                e.printStackTrace();
                return (new ArrayList<Object>());
            }
        }

        @Override
        protected void onPostExecute(ArrayList<Object> results) {
            if (results == null || results.size() == 0) {
                Log.d(TAG, "Async Failed");
                return;
            } else if (results.size() == 1) {
                String s = (String) results.get(0);
                Log.d(TAG, "Async Result: " + s);
            } else {
                String filePath = (String) results.get(0);
                if (mThumbnail != null) {
                    Log.d(TAG, "Updating thumbnail: " + filePath);
                    Picasso.with(FaceTrackerActivity.this).load(filePath).transform(new CircleTransform()).into(mThumbnail);
                    setCaptureSmilers(false);
                } else {
                    Log.d(TAG, "null thumbnail");
                }
                Log.d("Calhacks", "Image saved");
            }
        }
    }
}
