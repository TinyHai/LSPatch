package org.lsposed.lspatch.loader;

import android.annotation.SuppressLint;
import android.app.ActivityThread;
import android.app.LoadedApk;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.MessageQueue;
import android.os.Process;
import android.os.ServiceManager;
import android.system.Os;
import android.util.Log;

import com.android.server.SystemServiceManager;

import org.lsposed.lspatch.loader.util.XLog;
import org.lsposed.lspatch.service.LocalAppService;
import org.lsposed.lspd.core.Startup;
import org.lsposed.lspd.deopt.PrebuiltMethodsDeopter;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XposedInit;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import hidden.HiddenApiBridge;

/**
 * Created by Windysha
 */
@SuppressLint("DiscouragedPrivateApi")
@SuppressWarnings("unused")
public class LSPApplication {

    private static final String TAG = "LSPatch";
    private static final int FIRST_APP_ZYGOTE_ISOLATED_UID = 90000;
    private static final int PER_USER_RANGE = 100000;

    private static final Method postSyncBarrier;

    private static final Method removeSyncBarrier;

    static {
        try {
            postSyncBarrier = MessageQueue.class.getDeclaredMethod("postSyncBarrier");
            removeSyncBarrier = MessageQueue.class.getDeclaredMethod("removeSyncBarrier", int.class);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * @noinspection DataFlowIssue
     */
    private static int postSyncBarrier() {
        MessageQueue queue = Looper.getMainLooper().getQueue();
        try {
            return (int) postSyncBarrier.invoke(queue);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    private static void removeSyncBarrier(int token) {
        MessageQueue queue = Looper.getMainLooper().getQueue();
        try {
            removeSyncBarrier.invoke(queue, token);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean isIsolated() {
        return (android.os.Process.myUid() % PER_USER_RANGE) >= FIRST_APP_ZYGOTE_ISOLATED_UID;
    }

    public static void onLoad() {
        if (isIsolated()) {
            XLog.d(TAG, "Skip isolated process");
            return;
        }
        var classloader = LSPApplication.class.getClassLoader();
        try {
            classloader.loadClass("cn.tinyhai.xposed.meta_loader.LoaderEntry");
            Log.i(TAG, "LoaderEntry is found");
        } catch (ClassNotFoundException e) {
            Log.i(TAG, "LoaderEntry not found");
        }

        hookApplicationIfNeeded();
        hookSystemServerIfNeeded();
    }

    private static boolean isSystemServer() {
        return ActivityThread.isSystem();
    }

    private static void hookSystemServerIfNeeded() {
        if (!isSystemServer()) {
            return;
        }

        Log.i(TAG, "post hookSystemServer");
        new Handler(Looper.getMainLooper()).post(LSPApplication::hookSystemServer);
    }

    private static void hookApplicationIfNeeded() {
        if (isSystemServer()) {
            return;
        }

        Log.i(TAG, "post hookApplication");

        final int token = postSyncBarrier();
        Handler asyncHandler = Handler.createAsync(Looper.getMainLooper());
        asyncHandler.post(() -> {
            hookApplication(token);
        });
    }

    private static void hookSystemServer() {
        Log.d(TAG, "start hookSystemServer");
        Object ams = ServiceManager.getService(Context.ACTIVITY_SERVICE);
        if (ams == null) {
            Log.d(TAG, "can't get ams");
            return;
        }

        ClassLoader systemServerCL = ams.getClass().getClassLoader();

        PrebuiltMethodsDeopter.deoptSystemServerMethods(systemServerCL);

        var service = new LocalAppService(ActivityThread.currentActivityThread().getSystemContext());
        Startup.initXposed(true, "system", null, service);
        Startup.bootstrapXposed();

        startHookSystemServer(systemServerCL);
    }

    private static void hookApplication(int token) {
        removeSyncBarrier(token);

        Log.d(TAG, "start hookApplication");
        String packageName = ActivityThread.currentPackageName();

        ActivityThread currentThread = ActivityThread.currentActivityThread();
        var mPackages = (Map<?, ?>) XposedHelpers.getObjectField(currentThread, "mPackages");
        if (!mPackages.containsKey(packageName)) {
            Log.d(TAG, "LoadedApk is not created");
            var service = new LocalAppService(null);
            Startup.initXposed(false, ActivityThread.currentProcessName(), null, service);
            Startup.bootstrapXposed();
            return;
        }
        var loadedApkRef = (WeakReference<?>) mPackages.get(packageName);
        var loadedApk = (LoadedApk) loadedApkRef.get();
        if (loadedApk == null) {
            Log.d(TAG, "LoadedApk is invalid");
            return;
        }

        Context context = null;
        try {
            context = (Context) XposedHelpers.callStaticMethod(Class.forName("android.app.ContextImpl"), "createAppContext", ActivityThread.currentActivityThread(), loadedApk);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }

        disableProfile(context);
        var service = new LocalAppService(context);
        Startup.initXposed(false, ActivityThread.currentProcessName(), loadedApk.getApplicationInfo().dataDir, service);
        Startup.bootstrapXposed();

        LSPLoader.initModules(loadedApk);
    }

    private static void startHookSystemServer(ClassLoader classLoader) {
        XposedInit.loadedPackagesInProcess.add("android");

        XC_LoadPackage.LoadPackageParam lpparam = new XC_LoadPackage.LoadPackageParam(XposedBridge.sLoadedPackageCallbacks);
        lpparam.packageName = "android";
        lpparam.processName = "android"; // it's actually system_server, but other functions return this as well
        lpparam.classLoader = classLoader;
        lpparam.appInfo = null;
        lpparam.isFirstApplication = true;

        XC_LoadPackage.callAll(lpparam);
    }

    public static void disableProfile(Context context) {
        final ArrayList<String> codePaths = new ArrayList<>();
        var appInfo = context.getApplicationInfo();
        var pkgName = context.getPackageName();
        if (appInfo == null) return;
        if ((appInfo.flags & ApplicationInfo.FLAG_HAS_CODE) != 0) {
            codePaths.add(appInfo.sourceDir);
        }
        if (appInfo.splitSourceDirs != null) {
            Collections.addAll(codePaths, appInfo.splitSourceDirs);
        }

        if (codePaths.isEmpty()) {
            // If there are no code paths there's no need to setup a profile file and register with
            // the runtime,
            return;
        }

        var profileDir = HiddenApiBridge.Environment_getDataProfilesDePackageDirectory(appInfo.uid / PER_USER_RANGE, pkgName);

        var attrs = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("r--------"));

        for (int i = codePaths.size() - 1; i >= 0; i--) {
            String splitName = i == 0 ? null : appInfo.splitNames[i - 1];
            File curProfileFile = new File(profileDir, splitName == null ? "primary.prof" : splitName + ".split.prof").getAbsoluteFile();
            Log.d(TAG, "Processing " + curProfileFile.getAbsolutePath());
            try {
                if (!curProfileFile.canWrite() && Files.size(curProfileFile.toPath()) == 0) {
                    Log.d(TAG, "Skip profile " + curProfileFile.getAbsolutePath());
                    continue;
                }
                if (curProfileFile.exists() && !curProfileFile.delete()) {
                    try (var writer = new FileOutputStream(curProfileFile)) {
                        Log.d(TAG, "Failed to delete, try to clear content " + curProfileFile.getAbsolutePath());
                    } catch (Throwable e) {
                        Log.e(TAG, "Failed to delete and clear profile file " + curProfileFile.getAbsolutePath(), e);
                    }
                    Os.chmod(curProfileFile.getAbsolutePath(), 00400);
                } else {
                    Files.createFile(curProfileFile.toPath(), attrs);
                }
            } catch (Throwable e) {
                Log.e(TAG, "Failed to disable profile file " + curProfileFile.getAbsolutePath(), e);
            }
        }
    }
}
