package org.lsposed.lspatch.service;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;

import org.lsposed.lspatch.util.ModuleLoader;
import org.lsposed.lspd.models.Module;
import org.lsposed.lspd.service.ILSPApplicationService;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class LocalAppService extends ILSPApplicationService.Stub {

    private static final String TAG = "LocalAppService";
    private static final String PACKAGE_NAME = "cn.tinyhai.ban_uninstall";
    private static final String MODULE_DIR = "/data/local/tmp";
    private static final String MODULE_APK = MODULE_DIR + File.separator + "base.apk";

    private final List<Module> modules = new ArrayList<>();

    public LocalAppService(Context context) {
        String module = null;
        if (context != null) {
            try {
                ApplicationInfo appInfo = context.getPackageManager().getApplicationInfo(PACKAGE_NAME, 0);
                module = appInfo.sourceDir;
            } catch (PackageManager.NameNotFoundException e) {
                module = MODULE_APK;
            }
        } else {
            module = MODULE_APK;
        }
        addModule(module);
    }

    private void addModule(String modulePath) {
        final Module module = new Module();
        module.apkPath = modulePath;
        module.packageName = PACKAGE_NAME;
        module.file = ModuleLoader.loadModule(modulePath);
        modules.add(module);
    }

    @Override
    public List<Module> getLegacyModulesList() {
        return modules;
    }

    @Override
    public List<Module> getModulesList() {
        return Collections.emptyList();
    }

    @Override
    public String getPrefsPath(String packageName) {
        return new File(Environment.getDataDirectory(), "data/" + packageName + "shared_prefs").getAbsolutePath();
    }

    @Override
    public ParcelFileDescriptor requestInjectedManagerBinder(List<IBinder> binder) {
        return null;
    }
}
