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
import android.graphics.ImageFormat;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.media.ExifInterface;
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
import com.squareup.picasso.Callback;
import com.squareup.picasso.Picasso;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;

public final class MainActivity extends AppCompatActivity {
    private static final String TAG = "Perfect";
    private static final String IMAGE_DIRECTORY = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES) + "/Perfect/";

    private CameraSource mCameraSource = null;
    private CameraSourcePreview mPreview;
    private GraphicOverlay mGraphicOverlay;
    private ImageView mThumbnail;
    private ImageView mFlashView;
    private Button mCameraButton;

    private final int RC_HANDLE_GMS = 9001;
    private final int RC_HANDLE_CAMERA_PERM = 2;
    private final int RC_HANDLE_STORAGE_PERM = 3;

    private int mNumPics = 1;
    private float mEyeProbability = (float) 0.5;
    private float mSmileThreshold = (float) 0.8;

    private volatile int mSmilers = 0;
    private boolean mShouldCaptureSmilers = false;
    private boolean mIsBlinkProofOn = true;
    private boolean mShouldRetake = false;
    private boolean mShowOverlay = true;
    private boolean mIsSmartChooseEnabled = true;   // TODO: Make a setting
    private volatile int mFaces = 0;
    private int mMinSmiles = 1;
    private int mMinFaces = 1;
    private volatile int mCount = 0;
    private AnimatorSet mAnimationSet;
    private long mGlobalTime = System.currentTimeMillis();
    private File[] mImagesTaken;

    // Necessary global parameters for
    private ArrayList<byte[]> mSmartChooseByteList;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        final TextView title = (TextView) findViewById(R.id.title_text);
        title.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                if (title.getWidth() != 0) {
                    int gradientWidth = title.getWidth();
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
        initializeViews();
        initializeThumbnail();
        initializeAnimation();

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

    @Override
    protected void onResume() {
        super.onResume();
        updateSettings();
        startCameraSource();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mPreview.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mCameraSource != null) {
            mCameraSource.release();
        }
    }

    public void setShouldCaptureSmilers(boolean bool) {
        mShouldCaptureSmilers = bool;
        mCameraButton.setActivated(bool);
    }

    private void updateSettings() {
        SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (sharedPrefs != null) {
            mIsBlinkProofOn = sharedPrefs.getBoolean("mIsBlinkProofOn", true);
            mMinFaces = sharedPrefs.getInt("mMinFaces", 1);
            mSmileThreshold = (float) sharedPrefs.getInt("mSmileThreshold", 80) / (float) 100.;
            mEyeProbability = (float) sharedPrefs.getInt("blinkThreshold", 50) / (float) 100.;
            mShowOverlay = sharedPrefs.getBoolean("mShowOverlay", true);
            mIsSmartChooseEnabled = sharedPrefs.getBoolean("smartChoose", true);
        }
    }

    private void initializeViews() {
        mPreview = (CameraSourcePreview) findViewById(R.id.preview);
        mGraphicOverlay = (GraphicOverlay) findViewById(R.id.faceOverlay);
        mCameraButton = (Button) findViewById(R.id.camera_button);
        mThumbnail = (ImageView) findViewById(R.id.thumbnail);
        ImageButton flipButton = (ImageButton) findViewById(R.id.flipButton);
        mFlashView = (ImageView) findViewById(R.id.flash);
        mFlashView.setVisibility(View.INVISIBLE);
        ImageButton settingsButton = (ImageButton) findViewById(R.id.settingsButton);

        if (settingsButton != null) {
            settingsButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent intent = new Intent(getApplicationContext(), SettingsActivity.class);
                    startActivity(intent);
                }
            });
        }
        if (mCameraButton != null) {
            mCameraButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if(mIsSmartChooseEnabled)
                    {
                        Log.d(TAG, "smart choose enabled");
                        takeSmartPicture();
                    }
                    else if (mShouldCaptureSmilers) {
                        setShouldCaptureSmilers(false);
                    } else {
                        setShouldCaptureSmilers(true);
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

        Button addMinSmile = (Button) findViewById(R.id.addMinFaces);
        if (addMinSmile != null) {
            addMinSmile.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mMinSmiles++;
                }
            });
        }
        Button subMinSmile = (Button) findViewById(R.id.subMinFaces);
        if (subMinSmile != null) {
            subMinSmile.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mMinSmiles > 1) {
                        mMinSmiles--;
                    }
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
        Picasso.with(this).load("file://" + latestImagePath).transform(new CircleTransform()).into(mThumbnail);
    }

    private void initializeAnimation() {
        if (mFlashView != null) {
            ObjectAnimator fadeOut = ObjectAnimator.ofFloat(mFlashView, "alpha", 7f, 0f);
            fadeOut.setDuration(250);
            ObjectAnimator fadeIn = ObjectAnimator.ofFloat(mFlashView, "alpha", 0f, 7f);
            fadeIn.setDuration(250);

            mAnimationSet = new AnimatorSet();

            mAnimationSet.play(fadeOut).after(fadeIn);

            mAnimationSet.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationStart(Animator animation) {
                    mFlashView.setVisibility(View.VISIBLE);
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    super.onAnimationEnd(animation);
                    Toast.makeText(getApplicationContext(), "Picture Taken", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void flipCamera() {
        resetVars();
        if (mCameraSource == null) {
            Toast.makeText(getApplicationContext(), "Null CameraSource", Toast.LENGTH_SHORT).show();
            return;
        }

        int facing = mCameraSource.getCameraFacing();
        mCameraSource.release();
        mCameraSource = null;
        if (facing == CameraSource.CAMERA_FACING_BACK)
            createCameraSource(CameraSource.CAMERA_FACING_FRONT);
        else
            createCameraSource(CameraSource.CAMERA_FACING_BACK);

        startCameraSource();
    }

    private void resetVars() {
        mNumPics = 1;
        mSmilers = 0;
        setShouldCaptureSmilers(false);
        mShouldRetake = false;
        mFaces = 0;
        mMinSmiles = 1;
        mCount = 0;
        updateSettings();
    }

    private void takePicture(boolean isRetake) {
        if (isRetake) {
            mCameraSource.takePicture(new CameraSource.ShutterCallback() {
                @Override
                public void onShutter() {
                    setShouldCaptureSmilers(false);
                }
            }, new CameraSource.PictureCallback() {
                @Override
                public void onPictureTaken(byte[] bytes) {
                    SavePictureTask save = new SavePictureTask();
                    save.execute(bytes);
                }
            });
        } else {
            mCameraSource.takePicture(new CameraSource.ShutterCallback() {
                @Override
                public void onShutter() {
                    mAnimationSet.start();
                    setShouldCaptureSmilers(false);
                }
            }, new CameraSource.PictureCallback() {
                @Override
                public void onPictureTaken(byte[] bytes) {
                    SavePictureTask save = new SavePictureTask();
                    save.execute(bytes);
                }
            });
        }
    }

    private void takeSmartPicture()
    {
        mSmartChooseByteList = new ArrayList<>();
        mCameraButton.setActivated(true);

        mCameraSource.setPreviewCallback(new Camera.PreviewCallback() {
            @Override
            public void onPreviewFrame(byte[] data, Camera camera) {
                if(data != null) {
                    mSmartChooseByteList.add(data);
                    Log.d(TAG, "onPreviewFrame: " + mSmartChooseByteList.size());
                }
                else
                {
                    Log.d(TAG, "data is null");
                }
                if(mSmartChooseByteList.size() >= 3)
                {
                    mCameraSource.removePreviewCallback();
                    SmartPictureTask task = new SmartPictureTask(mSmartChooseByteList
                            , camera.getParameters().getPreviewSize().width
                            , camera.getParameters().getPreviewSize().height);
                    task.execute();

                    mCameraButton.setActivated(false);
                }
            }
        });
    }

    private Bitmap getCorrectlyOrientedBitmap(byte[] data, int width, int height)
    {
        String filePath = generateTempImagePath(1);
        File tempPictureFile = new File(filePath);
        if (tempPictureFile.exists()) {
            tempPictureFile.delete();
        }

        try {
            FileOutputStream fileOutputStream = new FileOutputStream(tempPictureFile);

            Bitmap bitmap = getBitmapFromYuvBytes(data, width, height);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fileOutputStream);
            fileOutputStream.close();
            ExifInterface exif=new ExifInterface(filePath);

            Log.d(TAG, "EXIF value" + exif.getAttribute(ExifInterface.TAG_ORIENTATION));
            if(exif.getAttribute(ExifInterface.TAG_ORIENTATION).equalsIgnoreCase("6")){
                bitmap= rotateBitmap(bitmap, 90);
            } else if(exif.getAttribute(ExifInterface.TAG_ORIENTATION).equalsIgnoreCase("8")){
                bitmap= rotateBitmap(bitmap, 270);
            } else if(exif.getAttribute(ExifInterface.TAG_ORIENTATION).equalsIgnoreCase("3")){
                bitmap= rotateBitmap(bitmap, 180);
            } else if(exif.getAttribute(ExifInterface.TAG_ORIENTATION).equalsIgnoreCase("0")){
                bitmap= rotateBitmap(bitmap, 270);
            }

            return bitmap;

        } catch (FileNotFoundException e) {
            Log.d("Info", "File not found: " + e.getMessage());
        } catch (IOException e) {
            Log.d("TAG", "Error accessing file: " + e.getMessage());
        }
        return null;
    }

    private Bitmap getBitmapFromYuvBytes(byte[] data, int width, int height)
    {
        YuvImage yuvimage=new YuvImage(data, ImageFormat.NV21, width, height, null);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        yuvimage.compressToJpeg(new Rect(0, 0, width, height), 80, baos);
        byte[] jpegData = baos.toByteArray();
        return BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length);
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
            // completed, then the above call will not detect any mFaces.
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
                .setFacing(facing)
                .setRequestedFps(32.0f)
                .build();
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

    private File[] getImagesTaken() {
        if (mImagesTaken == null) {
            File dir = new File(IMAGE_DIRECTORY);
            mImagesTaken = dir.listFiles();
        }
        return mImagesTaken;
    }

    private Bitmap smartChoose(ArrayList<Bitmap> bitmaps) {
        FaceDetector detector = new FaceDetector.Builder(getApplicationContext())
                .setTrackingEnabled(false)
                .setClassificationType(FaceDetector.ALL_CLASSIFICATIONS)
                .setMode(FaceDetector.ACCURATE_MODE)
                .setLandmarkType(FaceDetector.NO_LANDMARKS)
                .build();

        Bitmap bestBitmap = null;
        float bestSum = -1;

        for (Bitmap bitmap : bitmaps) {
            Frame frame = new Frame.Builder().setBitmap(bitmap).build();
            SparseArray<Face> faces = detector.detect(frame);
            float leftEyeProb = 0, rightEyeProb = 0, smileProb = 0;
            for (int i = 0; i < faces.size(); ++i) {
                Face face = faces.valueAt(i);
                leftEyeProb += face.getIsLeftEyeOpenProbability();
                rightEyeProb += face.getIsRightEyeOpenProbability();
                smileProb += face.getIsSmilingProbability();
            }
            Log.d(TAG, "Left: " + leftEyeProb + " Right: " + rightEyeProb + " Smile: " + smileProb);
            float currentSum = leftEyeProb + rightEyeProb + smileProb;
            if(currentSum > bestSum) {
                bestBitmap = bitmap;
                bestSum = currentSum;
            }
        }
        Log.d(TAG, "best sum: " + bestSum);
        return  bestBitmap;
    }

    private boolean saveBitmap(Bitmap bitmap, String destPath) {
        File newdir = new File(IMAGE_DIRECTORY);
        if (!newdir.isDirectory()) {
            newdir.mkdirs();
        }
        File file = new File(destPath);
        try {
            FileOutputStream mFileOutputStream = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, mFileOutputStream);

            mFileOutputStream.flush();
            mFileOutputStream.close();
            return true;
        }
        catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    public static Bitmap rotateBitmap(Bitmap bitmap, int degree) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();

        Matrix mtx = new Matrix();
        //       mtx.postRotate(degree);
        mtx.setRotate(degree);

        return Bitmap.createBitmap(bitmap, 0, 0, w, h, mtx, true);
    }

    private String generateDestinationPath()
    {
        return IMAGE_DIRECTORY  + System.currentTimeMillis() + ".jpg";
    }

    private String generateTempImagePath(int identifier)
    {
        return getApplicationContext().getExternalCacheDir().getAbsolutePath() + "/" + identifier + "-temp.jpg";
    }

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
            mFaces++;
            mCount = 0;
        }

        /**
         * Update the position/characteristics of the face within the overlay.
         */
        @Override
        public void onUpdate(FaceDetector.Detections<Face> detectionResults, Face face) {
            if (mShowOverlay) {
                mOverlay.add(mFaceGraphic);
                mFaceGraphic.updateFace(face);
            } else if (mOverlay != null) {
                mOverlay.clear();
                mOverlay = null;
            }

            if (face.getIsSmilingProbability() > mSmileThreshold && !smiling) {
                mSmilers++;
                smiling = true;
            } else if (face.getIsSmilingProbability() < mSmileThreshold && smiling) {
                mSmilers--;
                mCount = 0;
                smiling = false;
            }

            if (checkConditions()) {
                takePicture(false);
            } else if (checkRetakeConditions()) {
                takePicture(true);
            }
        }

        private boolean checkConditions() {
            return mSmilers >= mMinSmiles && mShouldCaptureSmilers && mCount < mNumPics && mFaces >= mMinFaces;
        }

        private boolean checkRetakeConditions() {
            long TIME_BETWEEN_THRESHOLD = 2000;
            return (mShouldRetake && System.currentTimeMillis() > mGlobalTime + TIME_BETWEEN_THRESHOLD && mSmilers >= mMinSmiles);
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
            mFaces--;
            mCount = 0;
        }
    }

    private class SavePictureTask extends AsyncTask<byte[], Integer, String> {

        @Override
        protected void onPreExecute() {
        }

        @Override
        protected String doInBackground(byte[]... params) {
            byte[] bytes = params[0];

            if (bytes == null) {
                return null;
            }
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bitmap == null) {
                return null;
            }

            // If someone is blinking with both eyes, take another picture.
            if (mIsBlinkProofOn) {
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
                    Log.d(TAG, "Left: " + leftEyeProb + " Right: " + rightEyeProb + " Smile: " + smileProb);
                    if (leftEyeProb < mEyeProbability && rightEyeProb < mEyeProbability && leftEyeProb >= 0) {
                        mShouldRetake = true;
                        //mCount--;
                        mGlobalTime = System.currentTimeMillis();
                        Log.d(TAG, "Don't blink!");
                        return null;
                    }
                }
            }

            String filePath = generateDestinationPath();
            boolean wasSaveSuccessful = saveBitmap(bitmap, filePath);
            if(wasSaveSuccessful) {
                mShouldRetake = false;
                return filePath;
            }
            else {
                return null;
            }
        }

        @Override
        protected void onPostExecute(String destPath) {
            if (destPath != null && !destPath.isEmpty()) {
                if (mThumbnail != null) {
                    Log.d(TAG, "Updating thumbnail: " + destPath);
                    Picasso
                            .with(MainActivity.this)
                            .load("file://" + destPath)
                            .transform(new CircleTransform())
                            .into(mThumbnail
                                    , new Callback() {
                                        @Override
                                        public void onSuccess() {
                                            Log.d(TAG, "Thumbnail loaded successfully!");
                                        }

                                        @Override
                                        public void onError() {
                                            Log.d(TAG, "Thumbnail loaded failed!");
                                        }
                                    });
                    setShouldCaptureSmilers(false);
                } else {
                    Log.d(TAG, "null thumbnail");
                }
                Log.d("Calhacks", "Image saved");
            }
        }
    }

    private class SmartPictureTask extends AsyncTask<Void, Integer, Boolean> {

        private ArrayList<byte[]> mByteListsToProcess;
        private ArrayList<Bitmap> mProcessedBitmaps = new ArrayList<>();
        private int mWidth;
        private int mHeight;

        public SmartPictureTask(ArrayList<byte[]> byteListsToProcess, int width, int height)
        {
            mByteListsToProcess = byteListsToProcess;
            mWidth = width;
            mHeight = height;
        }

        @Override
        protected void onPreExecute() {
            Log.d(TAG, "Taking smart picture");
        }

        @Override
        protected Boolean doInBackground(Void... params) {
            for(byte[] data: mByteListsToProcess)
            {
                Bitmap bitmap = getBitmapFromYuvBytes(data, mWidth, mHeight);
                mProcessedBitmaps.add(bitmap);
                bitmap = getCorrectlyOrientedBitmap(data, mWidth, mHeight);
                saveBitmap(bitmap, generateDestinationPath());
                mProcessedBitmaps.add(bitmap);
            }
            Bitmap best = smartChoose(mProcessedBitmaps);
            String filePath = generateDestinationPath();
            boolean savedSuccessfully = saveBitmap(best, filePath);
            Log.d(TAG, (savedSuccessfully? "Saved best bitmap to: " + filePath: " Failed to save the best bitmap"));
            return null;
        }

        @Override
        protected void onPostExecute(Boolean result) {
            Log.d(TAG, "smartChooseTask done");
        }
    }

}
