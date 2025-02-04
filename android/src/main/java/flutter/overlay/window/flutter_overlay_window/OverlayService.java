package flutter.overlay.window.flutter_overlay_window;

import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.app.PendingIntent;
import android.graphics.Point;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.initialization.InitializationStatus;
import com.google.android.gms.ads.initialization.OnInitializationCompleteListener;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;

import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import com.example.flutter_overlay_window.R;

import java.util.Timer;
import java.util.TimerTask;

import io.flutter.embedding.android.FlutterTextureView;
import io.flutter.embedding.android.FlutterView;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.FlutterEngineCache;
import io.flutter.plugin.common.BasicMessageChannel;
import io.flutter.plugin.common.JSONMessageCodec;
import io.flutter.plugin.common.MethodChannel;

public class OverlayService extends Service implements View.OnTouchListener {
    private final int DEFAULT_NAV_BAR_HEIGHT_DP = 48;
    private final int DEFAULT_STATUS_BAR_HEIGHT_DP = 25;

    private Integer mStatusBarHeight = -1;
    private Integer mNavigationBarHeight = -1;
    private Resources mResources;

    public static final String INTENT_EXTRA_IS_CLOSE_WINDOW = "IsCloseWindow";
    public static boolean isRunning = false;
    private WindowManager windowManager = null;
    private FlutterView flutterView;

    private MethodChannel flutterChannel = new MethodChannel(
            FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG).getDartExecutor(),
            OverlayConstants.OVERLAY_TAG);
    private BasicMessageChannel<Object> overlayMessageChannel = new BasicMessageChannel(
            FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG).getDartExecutor(),
            OverlayConstants.MESSENGER_TAG, JSONMessageCodec.INSTANCE);
    private int clickableFlag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

    private Handler mAnimationHandler = new Handler();
    private float lastX, lastY;
    private int lastYPosition;
    private boolean dragging;
    private static final float MAXIMUM_OPACITY_ALLOWED_FOR_S_AND_HIGHER = 0.8f;
    private Point szWindow = new Point();
    private Timer mTrayAnimationTimer;
    private TrayAnimationTimerTask mTrayTimerTask;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    public void onDestroy() {
        Log.d("OverLay", "Destroying the overlay window service");
        isRunning = false;
        NotificationManager notificationManager = (NotificationManager) getApplicationContext()
                .getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(OverlayConstants.NOTIFICATION_ID);
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mResources = getApplicationContext().getResources();
        boolean isCloseWindow = intent.getBooleanExtra(INTENT_EXTRA_IS_CLOSE_WINDOW, false);
        if (isCloseWindow) {
            if (windowManager != null) {
                windowManager.removeView(flutterView);
                windowManager = null;
                flutterView.detachFromFlutterEngine();
                stopSelf();
            }
            isRunning = false;

            // // Load the ad asynchronously after the overlay is set up
            // new Handler(Looper.getMainLooper()).post(new Runnable() {
            // @Override
            // public void run() {
            //
            // }
            // });

            return START_STICKY;
        }
        if (windowManager != null) {
            windowManager.removeView(flutterView);
            windowManager = null;
            flutterView.detachFromFlutterEngine();
            stopSelf();
        }
        isRunning = true;
        Log.d("onStartCommand", "Service started");
        FlutterEngine engine = FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG);
        engine.getLifecycleChannel().appIsResumed();
        flutterView = new FlutterView(getApplicationContext(), new FlutterTextureView(getApplicationContext()));
        flutterView.attachToFlutterEngine(FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG));
        flutterView.setFitsSystemWindows(true);
        flutterView.setFocusable(true);
        flutterView.setFocusableInTouchMode(true);
        flutterView.setBackgroundColor(Color.TRANSPARENT);

        flutterChannel.setMethodCallHandler((call, result) -> {
            if (call.method.equals("updateFlag")) {
                String flag = call.argument("flag").toString();
                updateOverlayFlag(result, flag);
            } else if (call.method.equals("resizeOverlay")) {
                int width = call.argument("width");
                int height = call.argument("height");
                boolean enableDrag = call.argument("enableDrag");
                boolean showAd = call.argument("showAd");
                String alignment = call.argument("alignment");
                String positionGravity = call.argument("positionGravity");

                resizeOverlay(width, height, enableDrag, showAd, result);
            }
        });
        overlayMessageChannel.setMessageHandler((message, reply) -> {
            WindowSetup.messenger.send(message);
        });
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            windowManager.getDefaultDisplay().getSize(szWindow);
        } else {
            DisplayMetrics displaymetrics = new DisplayMetrics();
            windowManager.getDefaultDisplay().getMetrics(displaymetrics);
            int w = displaymetrics.widthPixels;
            int h = displaymetrics.heightPixels;
            szWindow.set(w, h);
        }
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowSetup.width == -1999 ? -1 : WindowSetup.width,
                WindowSetup.height != -1999 ? WindowSetup.height : screenHeight(),
                0,
                -statusBarHeightPx(),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowSetup.flag | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && WindowSetup.flag == clickableFlag) {
            params.alpha = MAXIMUM_OPACITY_ALLOWED_FOR_S_AND_HIGHER;
        }
        params.gravity = WindowSetup.gravity;
        flutterView.setOnTouchListener(this);
        windowManager.addView(flutterView, params);
        return START_STICKY;
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
    private int screenHeight() {
        Display display = windowManager.getDefaultDisplay();
        DisplayMetrics dm = new DisplayMetrics();
        display.getRealMetrics(dm);
        return inPortrait() ? dm.heightPixels + statusBarHeightPx() + navigationBarHeightPx()
                : dm.heightPixels + statusBarHeightPx();
    }

    private int statusBarHeightPx() {
        if (mStatusBarHeight == -1) {
            int statusBarHeightId = mResources.getIdentifier("status_bar_height", "dimen", "android");

            if (statusBarHeightId > 0) {
                mStatusBarHeight = mResources.getDimensionPixelSize(statusBarHeightId);
            } else {
                mStatusBarHeight = dpToPx(DEFAULT_STATUS_BAR_HEIGHT_DP);
            }
        }

        return mStatusBarHeight;
    }

    int navigationBarHeightPx() {
        if (mNavigationBarHeight == -1) {
            int navBarHeightId = mResources.getIdentifier("navigation_bar_height", "dimen", "android");

            if (navBarHeightId > 0) {
                mNavigationBarHeight = mResources.getDimensionPixelSize(navBarHeightId);
            } else {
                mNavigationBarHeight = dpToPx(DEFAULT_NAV_BAR_HEIGHT_DP);
            }
        }

        return mNavigationBarHeight;
    }

    private void updateOverlayFlag(MethodChannel.Result result, String flag) {
        if (windowManager != null) {
            WindowSetup.setFlag(flag);
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
            params.flags = WindowSetup.flag | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS |
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                    WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                    | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && WindowSetup.flag == clickableFlag) {
                params.alpha = MAXIMUM_OPACITY_ALLOWED_FOR_S_AND_HIGHER;
            } else {
                params.alpha = 1;
            }
            windowManager.updateViewLayout(flutterView, params);
            result.success(true);
        } else {
            result.success(false);
        }
    }

    private AdView adView;
    Handler mainHandler = new Handler(Looper.getMainLooper());

    private void resizeOverlay(int width, int height, boolean enableDrag, boolean showAd, MethodChannel.Result result) {
        if (windowManager != null) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
                    // params.gravity = Gravity.LEFT | Gravity.CENTER_VERTICAL;
                    params.x = 0;
                    params.y = 0;

                    int currentWidth = params.width;
                    int currentHeight = params.height;

                    // Create a ValueAnimator for width
                    ValueAnimator widthAnimator = ValueAnimator.ofInt(currentWidth, (width == -1999 || width == -1) ? -1 : dpToPx(width));
                    widthAnimator.setDuration(200); // Increase the duration for a slower animation
                    widthAnimator.setInterpolator(new DecelerateInterpolator()); // Use decelerate interpolator
                    widthAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                        @Override
                        public void onAnimationUpdate(ValueAnimator animation) {
                            params.width = (int) animation.getAnimatedValue();
                            windowManager.updateViewLayout(flutterView, params);
                        }
                    });

                    // Create a ValueAnimator for height
                    ValueAnimator heightAnimator = ValueAnimator.ofInt(currentHeight, (height == -1999 || height == -1) ? -1 : dpToPx(height));
                    heightAnimator.setDuration(200); // Increase the duration for a slower animation
                    heightAnimator.setInterpolator(new DecelerateInterpolator()); // Use decelerate interpolator
                    heightAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                        @Override
                        public void onAnimationUpdate(ValueAnimator animation) {
                            params.height = (int) animation.getAnimatedValue();
                            windowManager.updateViewLayout(flutterView, params);
                        }
                    });

                    // Start width and height animators together
                    AnimatorSet animatorSet = new AnimatorSet();
                    animatorSet.playTogether(widthAnimator, heightAnimator);
                    animatorSet.start();
//                    // Create a ValueAnimator for width and height
//                    ValueAnimator sizeAnimator = ValueAnimator.ofInt(currentWidth, ((width == -1999 || width == -1) ? -1 : dpToPx(width)), currentHeight, ((height == -1999 || height == -1) ? -1 : dpToPx(height)));
//                    sizeAnimator.setDuration(200); // Adjust the duration as needed
//                    sizeAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
//                        @Override
//                        public void onAnimationUpdate(ValueAnimator animation) {
//                            int animatedValue = (int) animation.getAnimatedValue();
//                            params.width = animatedValue;
//                            params.height = animatedValue;
//                            windowManager.updateViewLayout(flutterView, params);
//                        }
//                    });

                    // Start the size animator
//                    sizeAnimator.start();
//-------------------------------------------------------------------------------------------
//                    params.width = (width == -1999 || width == -1) ? -1 : dpToPx(width);
//                    // params.height = (height != 1999 || height != -1) ? dpToPx(height) : height;
//                    params.height = (height == -1999 || height == -1) ? -1 : dpToPx(height);

                    if (showAd) {
                        // Load and display a banner ad
                        adView = new AdView(OverlayService.this);
                        adView.setAdSize(AdSize.LARGE_BANNER);
//                        adView.setAdUnitId("ca-app-pub-3940256099942544/6300978111"); // --test ID
                        adView.setAdUnitId("ca-app-pub-5792296207093661/7795962486");
                        AdRequest adRequest = new AdRequest.Builder().build();
                        adView.loadAd(adRequest);


                        // Add the adView to your flutterView
                        FrameLayout.LayoutParams adParams = new FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.WRAP_CONTENT,
                                FrameLayout.LayoutParams.WRAP_CONTENT);

                        adParams.gravity = Gravity.CENTER;
                        adParams.bottomMargin = dpToPx(200);

                        flutterView.addView(adView, adParams);
                    } else {
                        if (adView != null) {
                            flutterView.removeView(adView);
                            adView = null;
                        }
                    }

//                    windowManager.updateViewLayout(flutterView, params);

                    boolean globalEnableDrag = WindowSetup.isEnableDrag();
                    if (globalEnableDrag != enableDrag) {
                        WindowSetup.setEnableDrag(enableDrag);
                    }

                    result.success(true);

                }
            });
        } else {
            result.success(false);
        }
    }

    @Override
    public void onCreate() {
        MobileAds.initialize(this, new OnInitializationCompleteListener() {
            @Override
            public void onInitializationComplete(InitializationStatus initializationStatus) {
            }
        });

        createNotificationChannel();
        Intent notificationIntent = new Intent(this, FlutterOverlayWindowPlugin.class);
        int pendingFlags;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            pendingFlags = PendingIntent.FLAG_IMMUTABLE;
        } else {
            pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(this,
                0, notificationIntent, pendingFlags);
        final int notifyIcon = getDrawableResourceId("mipmap", "launcher");
        Notification notification = new NotificationCompat.Builder(this, OverlayConstants.CHANNEL_ID)
                .setContentTitle(WindowSetup.overlayTitle)
                .setContentText(WindowSetup.overlayContent)
                .setSmallIcon(notifyIcon == 0 ? R.drawable.notification_icon : notifyIcon)
                .setContentIntent(pendingIntent)
                .setVisibility(WindowSetup.notificationVisibility)
                .build();
        startForeground(OverlayConstants.NOTIFICATION_ID, notification);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    OverlayConstants.CHANNEL_ID,
                    "Foreground Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT);
            NotificationManager manager = getSystemService(NotificationManager.class);
            assert manager != null;
            manager.createNotificationChannel(serviceChannel);
        }
    }

    private int getDrawableResourceId(String resType, String name) {
        return getApplicationContext().getResources().getIdentifier(String.format("ic_%s", name), resType,
                getApplicationContext().getPackageName());
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                Float.parseFloat(dp + ""), mResources.getDisplayMetrics());
    }

    private boolean inPortrait() {
        return mResources.getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
    }

    @Override
    public boolean onTouch(View view, MotionEvent event) {
        if (windowManager != null && WindowSetup.enableDrag) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    dragging = false;
                    lastX = event.getRawX();
                    lastY = event.getRawY();
                    break;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - lastX;
                    float dy = event.getRawY() - lastY;
                    if (!dragging && dx * dx + dy * dy < 25) {
                        return false;
                    }
                    lastX = event.getRawX();
                    lastY = event.getRawY();
                    int xx = params.x + (int) dx;
                    int yy = params.y + (int) dy;

                    if (Math.abs(params.x - xx) >= 2 || Math.abs(params.y - yy) >= 2) {
                        params.x = xx;
                        params.y = yy;
                        windowManager.updateViewLayout(flutterView, params);
                    }

                    dragging = true;

                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    lastYPosition = params.y;
                    if (WindowSetup.positionGravity != "none") {
                        windowManager.updateViewLayout(flutterView, params);
                        mTrayTimerTask = new TrayAnimationTimerTask();
                        mTrayAnimationTimer = new Timer();
                        mTrayAnimationTimer.schedule(mTrayTimerTask, 0, 25);
                    }
                    return false;
                default:
                    return false;
            }
            return false;
        }
        return false;
    }

    private class TrayAnimationTimerTask extends TimerTask {
        int mDestX;
        int mDestY;
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();

        public TrayAnimationTimerTask() {
            super();
            mDestY = lastYPosition;
            switch (WindowSetup.positionGravity) {
                case "auto":
                    mDestX = (params.x + (flutterView.getWidth() / 2)) <= szWindow.x / 2 ? 0
                            : szWindow.x - flutterView.getWidth();
                    return;
                case "left":
                    mDestX = 0;
                    return;
                case "right":
                    mDestX = szWindow.x - flutterView.getWidth();
                    return;
                default:
                    mDestX = params.x;
                    mDestY = params.y;
                    return;
            }
        }

        @Override
        public void run() {
            mAnimationHandler.post(() -> {
                params.x = (2 * (params.x - mDestX)) / 3 + mDestX;
                params.y = (2 * (params.y - mDestY)) / 3 + mDestY;
                windowManager.updateViewLayout(flutterView, params);
                if (Math.abs(params.x - mDestX) < 2 && Math.abs(params.y - mDestY) < 2) {
                    TrayAnimationTimerTask.this.cancel();
                    mTrayAnimationTimer.cancel();
                }
            });
        }
    }

}