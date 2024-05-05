package cn.tinyhai.xposed.meta_loader;

import android.annotation.SuppressLint;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@SuppressLint("UnsafeDynamicallyLoadedCode")
public class LoaderEntry {
    private static final String TAG = "LoaderEntry";

    private static final String TMP_PATH = "/data/local/tmp";

    private static final String LSPATCH_PATH = TMP_PATH + File.separator + "lspatch";

    private static final String LOADER_DEX_PATH = LSPATCH_PATH + File.separator + "loader.dex";

    private static final String LSPATCH_SO_PATH = LSPATCH_PATH + File.separator + "so" + File.separator + "%s" + File.separator + "liblspatch.so";

    private static final Map<String, String> archToLib = new HashMap<>(4);

    public static byte[] dex;

    static {
        archToLib.put("arm", String.format(LSPATCH_SO_PATH, "armeabi-v7a"));
        archToLib.put("arm64", String.format(LSPATCH_SO_PATH, "arm64-v8a"));
        archToLib.put("x86", String.format(LSPATCH_SO_PATH, "x86"));
        archToLib.put("x86_64", String.format(LSPATCH_SO_PATH, "x86_64"));

        try (InputStream in = new FileInputStream(LOADER_DEX_PATH); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            transfer(in, out);
            dex = out.toByteArray();

            Class<?> VMRuntime = Class.forName("dalvik.system.VMRuntime");
            Method getRuntime = VMRuntime.getDeclaredMethod("getRuntime");
            getRuntime.setAccessible(true);
            Method vmInstructionSet = VMRuntime.getDeclaredMethod("vmInstructionSet");
            vmInstructionSet.setAccessible(true);
            String arch = (String) vmInstructionSet.invoke(getRuntime.invoke(null));
            String libPath = archToLib.get(arch);

            Log.d(TAG, "load lspatch so from " + libPath);
            System.load(libPath);
        } catch (Throwable e) {
            Log.e(TAG, "LoaderEntry initialize", e);
            throw new ExceptionInInitializerError(e);
        }
    }

    private static void transfer(InputStream is, OutputStream os) throws IOException {
        byte[] buffer = new byte[8192];
        int n;
        while (-1 != (n = is.read(buffer))) {
            os.write(buffer, 0, n);
        }
    }

    public static ClassLoader getClassLoader() {
        Log.d(TAG, "getClassLoader invoked");
        return LoaderEntry.class.getClassLoader();
    }

    public static void entry() {
        Log.d(TAG, "entry invoked");
    }
}
