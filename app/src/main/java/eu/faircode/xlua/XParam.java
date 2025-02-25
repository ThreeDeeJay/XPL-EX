/*
    This file is part of XPrivacyLua.

    XPrivacyLua is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    XPrivacyLua is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with XPrivacyLua.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2017-2019 Marcel Bokhorst (M66B)
 */

package eu.faircode.xlua;

import android.app.ActivityManager;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;
import android.util.Log;
import android.view.InputDevice;

import java.io.File;
import java.io.FileDescriptor;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ThreadLocalRandom;

import de.robv.android.xposed.XC_MethodHook;
import eu.faircode.xlua.api.properties.MockPropSetting;
import eu.faircode.xlua.api.xmock.XMockCall;
import eu.faircode.xlua.interceptors.shell.ShellInterceptionResult;
import eu.faircode.xlua.interceptors.UserContextMaps;
import eu.faircode.xlua.interceptors.ShellIntercept;
import eu.faircode.xlua.logger.XLog;
import eu.faircode.xlua.rootbox.XReflectUtils;
import eu.faircode.xlua.tools.BytesReplacer;
import eu.faircode.xlua.utilities.Evidence;
import eu.faircode.xlua.utilities.ListFilterUtil;
import eu.faircode.xlua.utilities.CollectionUtil;
import eu.faircode.xlua.utilities.CursorUtil;
import eu.faircode.xlua.utilities.MemoryUtilEx;
import eu.faircode.xlua.utilities.MockCpuUtil;
import eu.faircode.xlua.display.MotionRangeUtil;
import eu.faircode.xlua.utilities.FileUtil;
import eu.faircode.xlua.utilities.LuaLongUtil;
import eu.faircode.xlua.utilities.MemoryUtil;
import eu.faircode.xlua.utilities.NetworkUtil;
import eu.faircode.xlua.utilities.RandomStringGenerator;
import eu.faircode.xlua.utilities.ReflectUtil;
import eu.faircode.xlua.utilities.StringUtil;
import eu.faircode.xlua.utilities.MockUtils;

public class XParam {
    private static final List<String> ALLOWED_PACKAGES = Arrays.asList("google", "system", "settings", "android", "webview");
    private static final String TAG = "XLua.XParam";

    private final Context context;
    private final Field field;
    private final XC_MethodHook.MethodHookParam param;
    private final Class<?>[] paramTypes;
    private final Class<?> returnType;
    private final Map<String, String> settings;
    private final Map<String, Integer> propSettings;
    private final Map<String, String> propMaps;
    private final String key;
    private final boolean useDefault;
    private final String packageName;

    private static final Map<Object, Map<String, Object>> nv = new WeakHashMap<>();
    private UserContextMaps getUserMaps() { return new UserContextMaps(this.settings, this.propMaps, this.propSettings); }

    // Field param
    public XParam(
            Context context,
            Field field,
            Map<String, String> settings,
            Map<String, Integer> propSettings,
            Map<String, String> propMaps,
            String key,
            boolean useDefault,
            String packageName) {
        this.context = context;
        this.field = field;
        this.param = null;
        this.paramTypes = null;
        this.returnType = field.getType();
        this.settings = settings;
        this.propSettings = propSettings;
        this.propMaps = propMaps;
        this.key = key;
        this.useDefault = useDefault;
        this.packageName = packageName;
    }

    // Method param
    public XParam(
            Context context,
            XC_MethodHook.MethodHookParam param,
            Map<String, String> settings,
            Map<String, Integer> propSettings,
            Map<String, String> propMaps,
            String key,
            boolean useDefault,
            String packageName) {


        this.context = context;
        this.field = null;
        this.param = param;
        if (param.method instanceof Constructor) {
            this.paramTypes = ((Constructor) param.method).getParameterTypes();
            this.returnType = null;
        } else {
            this.paramTypes = ((Method) param.method).getParameterTypes();
            this.returnType = ((Method) param.method).getReturnType();
        }
        this.settings = settings;
        this.propSettings = propSettings;
        this.propMaps = propMaps;
        this.key = key;
        this.useDefault = useDefault;
        this.packageName = packageName;
    }

    //
    //Start of FILTER Functions
    //

    @SuppressWarnings("unused")
    public boolean isDriverFiles(String pathOrFile) { return FileUtil.isDeviceDriver(pathOrFile); }

    @SuppressWarnings("unused")
    public String filterBuildProperty(String property) {
        if(property == null || MockUtils.isPropVxpOrLua(property))
            return MockUtils.NOT_BLACKLISTED;

        if(DebugUtil.isDebug())
            Log.i(TAG, "Filtering Property=" + property + " prop maps=" + propMaps.size() + " settings size=" + settings.size());

        Integer code = propSettings.get(property);
        if(code != null) {
            if(code == MockPropSetting.PROP_HIDE) return null;//return MockUtils.HIDE_PROPERTY;
            if(code == MockPropSetting.PROP_SKIP) return MockUtils.NOT_BLACKLISTED;
            else {
                if(code != MockPropSetting.PROP_FORCE) {
                    try {
                        Object res = getResult();
                        if(res == null) return MockUtils.NOT_BLACKLISTED;
                    } catch (Throwable ignore) {  }
                }
            }
        }

        boolean cleanEmulator = getSettingBool(Evidence.SETTING_QEMU_EMULATOR, false) || getSettingBool(Evidence.SETTING_EMULATOR, false);
        boolean cleanRoot = getSettingBool(Evidence.SETTING_ROOT, false);
        if(cleanEmulator || cleanRoot) {
            if(cleanEmulator && Evidence.property(property, 1))
                return null;
            if(cleanRoot) {
                if(Evidence.property(property, 2))
                    return null;
                if(property.equalsIgnoreCase(Evidence.PROP_DEBUGGABLE))
                    return Evidence.PROP_DEBUGGABLE_GOOD;
                if(property.equalsIgnoreCase(Evidence.PROP_SECURE))
                    return Evidence.PROP_SECURE_GOOD;
            }
        }

        //add force options now
        if(propMaps != null && !propMaps.isEmpty()) {
            String settingName = propMaps.get(property);
            if(settingName != null) {
                if(settings.containsKey(settingName))
                    return getSetting(settingName, null);
            }
        }

        return MockUtils.NOT_BLACKLISTED;
    }

    @SuppressWarnings("unused")
    public String filterBinderProxyAfter(String filterKind) {
        try {
            Object ths = getThis();
            Method mth = XReflectUtils.getMethodFor("android.os.BinderProxy", "getInterfaceDescriptor");
            if (ths == null || mth == null) throw new RuntimeException("No such Member [getInterfaceDescriptor] for Binder proxy and or the current Instance is NULL!");

            String interfaceName = (String) mth.invoke(ths);
            if (!Str.isValidNotWhitespaces(interfaceName)) throw new RuntimeException("Invalid Interface Name for the Binder Proxy! Method has failed to invoke...");

            int code = (int) getArgument(0);
            Parcel data = (Parcel) getArgument(1);
            Parcel reply = (Parcel) getArgument(2);
            switch (filterKind) {
                case "adid":
                    if ("com.google.android.gms.ads.identifier.internal.IAdvertisingIdService".equalsIgnoreCase(interfaceName)) {
                        String fAd = getSetting("unique.google.advertising.id");
                        if(!Str.isValidNotWhitespaces(fAd)) return null;
                        Object res = getResult();
                        boolean result = (boolean) res;
                        if (result) {
                            byte[] bytes = reply.marshall();
                            reply.setDataPosition(0);
                            if (bytes.length == 84) {
                                Log.i(TAG, "[onAfterTransact] bytes: " + Str.bytesToHex(bytes));
                                try {
                                    reply.readException();
                                } finally {
                                    String adId = reply.readString();
                                    Log.i(TAG, "[onAfterTransact] adId: " + adId);
                                    if (adId != null) {
                                        try {
                                            byte[] from = adId.getBytes("UTF-16");
                                            from = Arrays.copyOfRange(from, 2, from.length);
                                            Log.i(TAG, "[onAfterTransact] from: " + Str.bytesToHex(from));

                                            byte[] to = fAd.getBytes("UTF-16");
                                            to = Arrays.copyOfRange(to, 2, to.length);
                                            Log.i(TAG, "[onAfterTransact] to: " + Str.bytesToHex(to));

                                            BytesReplacer bytesReplacer = new BytesReplacer(from, to);
                                            bytesReplacer.replace(bytes);

                                            reply.unmarshall(bytes, 0, bytes.length);
                                        } catch (Exception e) {
                                            Log.w(TAG, e);
                                            fAd = null;
                                        }
                                    }
                                    reply.setDataPosition(0);
                                }
                            }
                        }  return fAd;
                    } break;
            }
        }catch (Throwable e) {
            XLog.e("Failed to get Result / Transaction! after", e, true);
        } return null;
    }

    @SuppressWarnings("unused")
    public boolean filterSettingsSecure(String setting, String newValue) {
        try {
            Object arg = getArgument(1);
            if(setting != null && newValue != null && arg instanceof String) {
                String set = ((String)arg);
                if(setting.equalsIgnoreCase(set)) {
                    setResult(newValue);
                    return true;
                }
            }
        }catch (Throwable e) { Log.e(TAG, "Filter SettingsSecure Error: " + e.getMessage()); }
        return false;
    }

    //
    //End of FILTER Functions
    //

    @SuppressWarnings("unused")
    public boolean isDriverFile(String path) { return FileUtil.isDeviceDriver(path); }

    @SuppressWarnings("unused")
    public File[] filterFilesArray(File[] files) { return FileUtil.filterFileArray(files); }

    @SuppressWarnings("unused")
    public List<File> filterFilesList(List<File> files) { return FileUtil.filterFileList(files); }

    @SuppressWarnings("unused")
    public String[] filterFileStringArray(String[] files) { return FileUtil.filterArray(files); }

    @SuppressWarnings("unused")
    public List<String> filterFileStringList(List<String> files) { return FileUtil.filterList(files); }

    @SuppressWarnings("unused")
    public boolean isPackageAllowed(String str) {
        if(str == null || str.isEmpty() || str.equalsIgnoreCase(this.packageName)) return true;
        str = str.toLowerCase().trim();
        String blackSetting = getSetting("applications.blacklist.mode.bool");
        if(blackSetting != null) {
            boolean isBlacklist = StringUtil.toBoolean(blackSetting, false);
            if(isBlacklist) {
                String block = getSetting("applications.block.list");
                if(Str.isValidNotWhitespaces(block)) {
                    block = block.trim();
                    if(block.contains(",")) {
                        String[] blocked = block.split(",");
                        for(String b : blocked)
                            if(b.trim().equalsIgnoreCase(str))
                                return false;
                    }else {
                        if(block.equalsIgnoreCase("*"))
                            return false;
                        else if(block.equalsIgnoreCase(str))
                            return false;
                    }
                }
            } else {
                String allow = getSetting("applications.allow.list");
                if(Str.isValidNotWhitespaces(allow)) {
                    allow = allow.trim();
                    if(allow.contains(",")) {
                        String[] allowed = allow.split(",");
                        for(String a : allowed)
                            if(a.trim().equalsIgnoreCase(str))
                                return true;
                    }else {
                        if(allow.equalsIgnoreCase("*"))
                            return true;
                        else if(allow.equalsIgnoreCase(str))
                            return true;
                    }
                }
                if(Evidence.packageName(str, 3))
                    return false;

                for(String p : ALLOWED_PACKAGES)
                    if(str.contains(p))
                        return true;

                return false;
            }
        } return !Evidence.packageName(str, 3);
    }

    //
    //Shell Intercept
    //

    @SuppressWarnings("unused")
    public String interceptCommand(String command) {
        //We would accept args and even do some of this in LUAJ but LUAJ LOVES and I mean LOVES to Gas light us
        //Its one thing for me as the programmer to make a mistake another thing when LUAJ just lies to me and wont work
        if(command != null) {
            try {
                ShellInterceptionResult res = ShellIntercept.intercept(ShellInterceptionResult.create(command, getUserMaps()));
                if(res != null && res.isMalicious()) {
                    if(res.getNewValue() != null) {
                        Log.w(TAG, "Command Intercepted: " + command);
                        Log.w(TAG, "Replacing Command with: " + res.getNewValue());
                        if(returnType.equals(Process.class))
                            setResult(res.getEchoProcess());

                        return res.getNewValue();
                    }
                }
            }catch (Throwable e) {
                Log.e(TAG, "Failed to intercept e=" + e);
            }
        } return null;
    }

    @SuppressWarnings("unused")
    public String interceptCommandArray(String[] commands) {
        if(commands != null) {
            try {
                ShellInterceptionResult res = ShellIntercept.intercept(ShellInterceptionResult.create(commands, getUserMaps()));
                if(res != null && res.isMalicious()) {
                    if(res.getNewValue() != null) {
                        Log.w(TAG, "Command Intercepted: " + joinArray(commands));
                        Log.w(TAG, "Replacing Command with: " + res.getNewValue());
                        if(returnType.equals(Process.class))
                            setResult(res.getEchoProcess());

                        return res.getNewValue();
                    }else {
                        Log.e(TAG, "[getNewValue] is NULL!");
                    }
                }
            }catch (Throwable e) {
                Log.e(TAG, "Failed to intercept e=" + e);
            }
        } return null;
    }

    @SuppressWarnings("unused")
    public String interceptCommandList(List<String> commands) {
        if(commands != null) {
            try {
                ShellInterceptionResult res = ShellIntercept.intercept(ShellInterceptionResult.create(commands, getUserMaps()));
                if(res != null && res.isMalicious()) {
                    if(res.getNewValue() != null) {
                        Log.w(TAG, "Command Intercepted: " + joinList(commands));
                        Log.w(TAG, "Replacing Command with: " + res.getNewValue());
                        if(returnType.equals(Process.class))
                            setResult(res.getEchoProcess());

                        return res.getNewValue();
                    }else {
                        Log.e(TAG, "[getNewValue] is NULL!");
                    }
                }
            }catch (Throwable e) {
                Log.e(TAG, "Failed to intercept e=" + e);
            }
        } return null;
    }

    //
    //End of Shell Intercept
    //

    //
    //Start of Memory/CPU Functions
    //

    @SuppressWarnings("unused")
    public int getFileDescriptorId(FileDescriptor fs) { return FileUtil.getDescriptorNumber(fs);  }

    @SuppressWarnings("unused")
    public File createFakeMeminfoFile(int totalGigabytes, int availableGigabytes) { return FileUtil.generateTempFakeFile(MemoryUtil.generateFakeMeminfoContents(totalGigabytes, availableGigabytes)); }

    @SuppressWarnings("unused")
    public FileDescriptor createFakeMeminfoFileDescriptor(int totalGigabytes, int availableGigabytes) { return FileUtil.generateFakeFileDescriptor(MemoryUtil.generateFakeMeminfoContents(totalGigabytes, availableGigabytes)); }

    @SuppressWarnings("unused")
    public void populateMemoryInfo(ActivityManager.MemoryInfo memoryInfo, int totalMemoryInGB, int availableMemoryInGB) { MemoryUtilEx.populateMemoryInfo(memoryInfo, totalMemoryInGB, availableMemoryInGB); }

    @SuppressWarnings("unused")
    public ActivityManager.MemoryInfo getFakeMemoryInfo(int totalMemoryInGB, int availableMemoryInGB) { return MemoryUtilEx.getMemory(totalMemoryInGB, availableMemoryInGB); }

    @SuppressWarnings("unused")
    public FileDescriptor createFakeCpuinfoFileDescriptor() { return MockCpuUtil.generateFakeFileDescriptor(XMockCall.getSelectedMockCpu(getApplicationContext())); }

    @SuppressWarnings("unused")
    public File createFakeCpuinfoFile() { return MockCpuUtil.generateFakeFile(XMockCall.getSelectedMockCpu(getApplicationContext())); }

    //
    //End of Memory/CPU Functions
    //

    //
    //Start of Bluetooth Functions
    //

    @SuppressWarnings("unused")
    public Set<BluetoothDevice> filterSavedBluetoothDevices(Set<BluetoothDevice> devices, List<String> allowList) { return ListFilterUtil.filterSavedBluetoothDevices(devices, allowList); }

    @SuppressWarnings("unused")
    public List<ScanResult> filterWifiScanResults(List<ScanResult> results, List<String> allowList) { return ListFilterUtil.filterWifiScanResults(results, allowList); }

    @SuppressWarnings("unused")
    public List<WifiConfiguration> filterSavedWifiNetworks(List<WifiConfiguration> results, List<String> allowList) { return ListFilterUtil.filterSavedWifiNetworks(results, allowList); }

    //
    //End of Bluetooth Functions
    //

    //
    //Start of Display Functions
    //

    @SuppressWarnings("unused")
    public InputDevice.MotionRange createXAxis(int height) { return MotionRangeUtil.createXAxis(height); }

    @SuppressWarnings("unused")
    public InputDevice.MotionRange createYAxis(int width) { return MotionRangeUtil.createYAxis(width); }

    //
    //End of Display Functions
    //

    //
    //Start of ETC Util Functions
    //

    @SuppressWarnings("unused")
    public String generateRandomString(int min, int max) { return generateRandomString(ThreadLocalRandom.current().nextInt(min, max + 1)); }

    @SuppressWarnings("unused")
    public String generateRandomString(int length) { return RandomStringGenerator.generateRandomAlphanumericString(length); }

    @SuppressWarnings("unused")
    public byte[] getIpAddressBytes(String ipAddress) { return NetworkUtil.stringIpAddressToBytes(ipAddress); }

    @SuppressWarnings("unused")
    public byte[] getFakeIpAddressBytes() { return NetworkUtil.stringIpAddressToBytes(getSetting("net.host_address")); }

    @SuppressWarnings("unused")
    public int getFakeIpAddressInt() { return NetworkUtil.stringIpAddressToInt(getSetting("net.host_address")); }

    @SuppressWarnings("unused")
    public byte[] getFakeMacAddressBytes() { return NetworkUtil.macStringToBytes(getSetting("net.mac")); }

    @SuppressWarnings("unused")
    public String gigabytesToBytesString(int gigabytes) { return Long.toString((long) gigabytes * 1073741824L); }

    //
    //End of ETC Util Functions
    //

    //
    //Start of Query / Call Functions
    //

    @SuppressWarnings("unused")
    public File[] fileArrayHasEvidence(File[] files, int code) { return Evidence.fileArray(files, code); }
    @SuppressWarnings("unused")
    public List<File> fileListHasEvidence(List<File> files, int code) { return Evidence.fileList(files, code); }
    @SuppressWarnings("unused")
    public String[] stringArrayHasEvidence(String[] file, int code) { return Evidence.stringArray(file, code); }
    @SuppressWarnings("unused")
    public List<String> stringListHasEvidence(List<String> files, int code) { return Evidence.stringList(files, code); }
    @SuppressWarnings("unused")
    public boolean packageNameHasEvidence(String packageName, int code) { return Evidence.packageName(packageName, code); }
    @SuppressWarnings("unused")
    public boolean fileIsEvidence(String file, int code) { return Evidence.file(new File(file), code); }
    @SuppressWarnings("unused")
    public boolean fileIsEvidence(File file,  int code) { return Evidence.file(file.getAbsolutePath(), file.getName(), code); }
    @SuppressWarnings("unused")
    public boolean fileIsEvidence(String fileFull, String fileName, int code) { return Evidence.file(fileFull, fileName, code); }
    @SuppressWarnings("unused")
    public StackTraceElement[] stackHasEvidence(StackTraceElement[] elements) { return Evidence.stack(elements); }

    @SuppressWarnings("unused")
    public String createFilledString(String s, String fillChar) { return Str.createFilledCopy(s, fillChar); }

    @SuppressWarnings("unused")
    public String[] extractSelectionArgs() {
        String[] sel = null;
        if(paramTypes[2].getName().equals(Bundle.class.getName())){
            //ContentResolver.query26
            Bundle bundle = (Bundle) getArgument(2);
            if(bundle != null) sel = bundle.getStringArray("android:query-arg-sql-selection-args");
        }
        else if(paramTypes[3].getName().equals(String[].class.getName())) sel = (String[]) getArgument(3);
        return sel;
    }

    @SuppressWarnings("unused")
    public boolean queryFilterAfter(String serviceName, String columnName, String newValue) { return queryFilterAfter(serviceName, columnName, newValue, (Uri)getArgument(0)); }

    @SuppressWarnings("unused")
    public boolean queryFilterAfter(String serviceName, String columnName, String newValue, Uri uri) {
        if(newValue != null && serviceName != null && columnName != null) {
            try {
                String authority = uri.getAuthority();
                Cursor ret = (Cursor) getResult();
                if((ret != null && authority != null) && authority.equalsIgnoreCase(serviceName)) {
                    String[] args = extractSelectionArgs();
                    if(args != null) {
                        for(String arg : args) {
                            if(arg.equalsIgnoreCase(newValue)) {
                                Log.i(TAG, "Found Query Service [" + serviceName + "] and Column [" + columnName + "] new Value [" + newValue + "]");
                                setResult(CursorUtil.copyKeyValue(ret, columnName, newValue));
                                return true;
                            }
                        }
                    }
                }
            }catch (Throwable e) { Log.e(TAG, "LUA PARAM [queryFilterAfter] Error: " + e.getMessage()); }
        } return false;
    }

    //
    //End of Query / Call Functions
    //

    @SuppressWarnings("unused")
    public Context getApplicationContext() { return this.context; }

    @SuppressWarnings("unused")
    public String getPackageName() { return this.context.getPackageName(); }

    @SuppressWarnings("unused")
    public int getUid() { return this.context.getApplicationInfo().uid; }

    @SuppressWarnings("unused")
    public Object getScope() { return this.param; }

    @SuppressWarnings("unused")
    public int getSDKCode() { return Build.VERSION.SDK_INT; }

    @SuppressWarnings("unused")
    public void printFileContents(String filePath) { FileUtil.printContents(filePath); }

    @SuppressWarnings("unused")
    public StackTraceElement[] getStackTrace() { return Thread.currentThread().getStackTrace(); }

    @SuppressWarnings("unused")
    public String getStackTraceString() { return Log.getStackTraceString(new Throwable()); }

    @SuppressWarnings("unused")
    public void printStack() { Log.w(TAG, Log.getStackTraceString(new Throwable())); }

    //
    //Start of REFLECT Functions
    //

    @SuppressWarnings("unused")
    public Class<Byte> getByteType() { return Byte.TYPE; }

    @SuppressWarnings("unused")
    public Class<Integer> getIntType() { return Integer.TYPE; }

    @SuppressWarnings("unused")
    public Class<Character> getCharType() { return Character.TYPE; }

    @SuppressWarnings("unused")
    public byte[] createByteArray(int size) { return new byte[size]; }

    @SuppressWarnings("unused")
    public int[] createIntArray(int size) { return new int[size]; }

    @SuppressWarnings("unused")
    public Character[] createCharArray(int size) { return new Character[size]; }

    @SuppressWarnings("unused")
    public static boolean isNumericString(String s) { return StringUtil.isNumeric(s); }

    @SuppressWarnings("unused")
    public static int getContainerSize(Object o) { return CollectionUtil.getSize(o); }

    @SuppressWarnings("unused")
    public String joinArray(String[] array) { return array == null ? "" : StringUtil.joinDelimiter(" ", array); }

    @SuppressWarnings("unused")
    public String joinArray(String[] array, String delimiter) {  return array == null ? "" : StringUtil.joinDelimiter(delimiter, array); }

    @SuppressWarnings("unused")
    public String joinList(List<String> list) { return  list == null ? "" : StringUtil.joinDelimiter(" ", list); }

    @SuppressWarnings("unused")
    public String joinList(List<String> list, String delimiter) { return  list == null ? "" : StringUtil.joinDelimiter(delimiter, list);  }

    @SuppressWarnings("unused")
    public List<String> stringToList(String s, String del) { return StringUtil.stringToList(s, del); }

    @SuppressWarnings("unused")
    public boolean listHasString(List<String> lst, String s) { return StringUtil.listHasString(lst, s);  }

    @SuppressWarnings("unused")
    public int stringLength(String s) { return s == null ? -1 : s.length(); }

    @SuppressWarnings("unused")
    public byte[] stringToUTF8Bytes(String s) { return StringUtil.getUTF8Bytes(s); }

    @SuppressWarnings("unused")
    public String bytesToUTF8String(byte[] bs) { return StringUtil.getUTF8String(bs); }

    @SuppressWarnings("unused")
    public byte[] stringToRawBytes(String s) { return StringUtil.stringToRawBytes(s); }

    @SuppressWarnings("unused")
    public String rawBytesToHexString(byte[] bs) { return StringUtil.rawBytesToHex(bs); }

    @SuppressWarnings("unused")
    public String bytesToSHA256Hash(byte[] bs) { return StringUtil.getBytesSHA256Hash(bs); }

    //
    //End of REFLECT Functions
    //

    @SuppressWarnings("unused")
    public boolean javaMethodExists(String className, String methodName) { return ReflectUtil.javaMethodExists(className, methodName); }

    @SuppressWarnings("unused")
    public Class<?> getClassType(String className) { return ReflectUtil.getClassType(className); }

    @SuppressWarnings("unused")
    public Object createReflectArray(String className, int size) { return ReflectUtil.createArray(className, size); }

    @SuppressWarnings("unused")
    public Object createReflectArray(Class<?> classType, int size) { return ReflectUtil.createArray(classType, size); }

    @SuppressWarnings("unused")
    public boolean hasFunction(String classPath, String function) { return XReflectUtils.methodExists(classPath, function); }

    @SuppressWarnings("unused")
    public boolean hasFunction(String function) { return XReflectUtils.methodExists(getThis().getClass().getName(), function); }

    @SuppressWarnings("unused")
    public boolean hasField(String classPath, String field) { return XReflectUtils.fieldExists(classPath, field); }

    @SuppressWarnings("unused")
    public boolean hasField(String field) { return XReflectUtils.fieldExists(getThis().getClass().getName(), field); }

    //
    //START OF LONG HELPER FUNCTIONS
    //
    @SuppressWarnings("unused")
    public String getFieldLong(Object instance, String fieldName) { return Long.toString(LuaLongUtil.getFieldValue(instance, fieldName)); }

    @SuppressWarnings("unused")
    public void setFieldLong(Object instance, String fieldName, String longValue) { LuaLongUtil.setLongFieldValue(instance, fieldName, longValue); }

    @SuppressWarnings("unused")
    public void parcelWriteLong(Parcel parcel, String longValue) { LuaLongUtil.parcelWriteLong(parcel, longValue); }

    @SuppressWarnings("unused")
    public String parcelReadLong(Parcel parcel) { return LuaLongUtil.parcelReadLong(parcel); }

    @SuppressWarnings("unused")
    public void bundlePutLong(Bundle bundle, String key, String longValue) { LuaLongUtil.bundlePutLong(bundle, key, longValue); }

    @SuppressWarnings("unused")
    public String bundleGetLong(Bundle bundle, String key) { return LuaLongUtil.bundleGetLong(bundle, key); }

    @SuppressWarnings("unused")
    public void setResultToLong(String long_value) throws Throwable { try { setResult(Long.parseLong(long_value)); }catch (Exception e) { XLog.e("Failed to set result to Long Value. Make sure its a valid Number.", e); } }

    @SuppressWarnings("unused")
    public void setResultToLongInt(String long_int) throws Throwable { try { setResult(Integer.parseInt(long_int)); }catch (Exception e) {  XLog.e("Failed to set result to Long Int Value. Make sure its a valid Number.", e); } }

    @SuppressWarnings("unused")
    public String getResultLong() throws Throwable { try { return Long.toString((long)getResult()); }catch (Exception e) { XLog.e("Failed Get Result as Long String", e); return "0"; } }

    //
    //END OF LONG HELPER FUNCTIONS
    //

    @SuppressWarnings("unused")
    public Object getThis() {
        if (this.field == null) return this.param.thisObject;
        else return null;
    }

    @SuppressWarnings("unused")
    public Object getArgument(int index) {
        if (index < 0 || index >= this.paramTypes.length) throw new ArrayIndexOutOfBoundsException("Argument #" + index);
        return this.param.args[index];
    }

    @SuppressWarnings("unused")
    public void setArgumentString(int index, Object value) { setArgument(index, (String)String.valueOf(value)); }

    @SuppressWarnings("unused")
    public void setArgumentString(int index, String value) { setArgument(index, value); }

    @SuppressWarnings("unused")
    public void setArgument(int index, Object value) {
        if (index < 0 || index >= this.paramTypes.length) throw new ArrayIndexOutOfBoundsException("Argument #" + index);
        if (value != null) {
            value = coerceValue(this.paramTypes[index], value);
            if (!boxType(this.paramTypes[index]).isInstance(value))
                throw new IllegalArgumentException("Expected argument #" + index + " " + this.paramTypes[index] + " got " + value.getClass());
        } this.param.args[index] = value;
    }


    @SuppressWarnings("unused")
    public Throwable getException() {
        Throwable ex = (this.field == null ? this.param.getThrowable() : null);
        if(DebugUtil.isDebug()) Log.i(TAG, "Get " + this.getPackageName() + ":" + this.getUid() + " result=" + ex.getMessage());
        return ex;
    }

    @SuppressWarnings("unused")
    public Object getResult() throws Throwable {
        Object result = (this.field == null ? this.param.getResult() : this.field.get(null));
        if (DebugUtil.isDebug()) Log.i(TAG, "Get " + this.getPackageName() + ":" + this.getUid() + " result=" + result);
        return result;
    }

    @SuppressWarnings("unused")
    public void setResultString(Object result) throws Throwable { setResult(String.valueOf(result)); }

    @SuppressWarnings("unused")
    public void setResultString(String result) throws Throwable { setResult(result); }

    @SuppressWarnings("unused")
    public void setResultByteArray(Byte[] result) throws Throwable { setResult(result); }

    @SuppressWarnings("unused")
    public void setResultBytes(byte[] result) throws Throwable {
        if(result == null) {
            Log.e(TAG, "Set Bytes is NULL ??? fix");
            return;
        }

        if (BuildConfig.DEBUG) {
            Log.i(TAG, "Setting as Bytes...");
            Log.i(TAG, "Bytes Set " + this.getPackageName() + ":" + this.getUid() +
                    " result size=" + result.length + " return=" + this.returnType);
        }

        try {
            Object res2 = coerceValue(this.returnType, result);
            Log.w(TAG, "Res2 = " + res2 + " clas=" + res2.getClass().getName());
        }catch (Exception e) { Log.e(TAG, "Failed to read ccv e=" + e); }

        try {
            boolean boxTyp = !boxType(this.returnType).isInstance(result);
            Log.w(TAG, "Is bytes shit box type ? " + boxTyp);
        }catch (Exception e) { Log.e(TAG, "Failed to read is box type e=" + e); }

        if (this.field == null) this.param.setResult(result);
        else this.field.set(null, result);
    }

    @SuppressWarnings("unused")
    public void setResult(Object result) throws Throwable {
        if (BuildConfig.DEBUG)
            Log.i(TAG, "Set " + this.getPackageName() + ":" + this.getUid() +
                    " result=" + result + " return=" + this.returnType);

        if (result != null && !(result instanceof Throwable) && this.returnType != null) {
            result = coerceValue(this.returnType, result);
            if (!boxType(this.returnType).isInstance(result))
                throw new IllegalArgumentException(
                        "Expected return " + this.returnType + " got " + result.getClass());
        }

        if (this.field == null)
            if (result instanceof Throwable) this.param.setThrowable((Throwable) result);
            else this.param.setResult(result);
        else this.field.set(null, result);
    }

    @SuppressWarnings("unused")
    public Integer getSettingInt(String name, int defaultValue) {
        String setting = getSetting(name);
        if(setting == null) return useDefault ? defaultValue : null;
        try {
            return Integer.parseInt(setting);
        }catch (Exception e) {
            Log.e(TAG, "Invalid Numeric Input::\n", e);
            return useDefault ? defaultValue : null;
        }
    }

    @SuppressWarnings("unused")
    public String getSettingReMap(String name, String oldName) { return getSettingReMap(name, oldName, null); }

    @SuppressWarnings("unused")
    public String getSettingReMap(String name, String oldName, String defaultValue) {
        if(name == null && oldName == null) return useDefault ? defaultValue : null;
        String setting = getSetting(name);
        if (setting != null) return setting;
        if(oldName != null) {
            setting = getSetting(oldName);
            if (setting != null) return setting;
            return useDefault ? defaultValue : null;
        }

        return useDefault ? defaultValue : null;
    }

    @SuppressWarnings("unused")
    public String getSetting(String name, String defaultValue) {
        String setting = getSetting(name);
        return setting == null && useDefault ? defaultValue : setting;
    }

    @SuppressWarnings("unused")
    public boolean getSettingBool(String name, boolean defaultValue) {
        String setting = getSetting(name);
        if(setting == null) return defaultValue;
        return Str.toBoolean(setting, defaultValue);
    }

    @SuppressWarnings("unused")
    public String getSetting(String name) {
        synchronized (this.settings) { return (this.settings.containsKey(name) ? this.settings.get(name) : null); }
    }

    @SuppressWarnings("unused")
    public void putValue(String name, Object value, Object scope) {
        Log.i(TAG, "Put value " + this.getPackageName() + ":" + this.getUid() + " " + name + "=" + value + " @" + scope);
        synchronized (nv) {
            if (!nv.containsKey(scope))
                nv.put(scope, new HashMap<String, Object>());
            nv.get(scope).put(name, value);
        }
    }

    @SuppressWarnings("unused")
    public Object getValue(String name, Object scope) {
        Object value = getValueInternal(name, scope);
        //Log.i(TAG, "Get value " + this.getPackageName() + ":" + this.getUid() + " " + name + "=" + value + " @" + scope);
        return value;
    }

    private static Object getValueInternal(String name, Object scope) {
        synchronized (nv) {
            if (!nv.containsKey(scope))
                return null;
            if (!nv.get(scope).containsKey(name))
                return null;
            return nv.get(scope).get(name);
        }
    }

    private static Class<?> boxType(Class<?> type) {
        if (type == boolean.class)
            return Boolean.class;
        else if (type == byte.class)
            return Byte.class;
        else if (type == char.class)
            return Character.class;
        else if (type == short.class)
            return Short.class;
        else if (type == int.class)
            return Integer.class;
        else if (type == long.class)
            return Long.class;
        else if (type == float.class)
            return Float.class;
        else if (type == double.class)
            return Double.class;
        return type;
    }

    private static Object coerceValue(Class<?> type, Object value) {
        // TODO: check for null primitives
        Class<?> vType = value.getClass();
        if(vType == Double.class || vType == Float.class || vType == Long.class || vType == Integer.class || vType == String.class) {
            Class<?> bType = boxType(type);
            if(bType == Double.class || bType == Float.class || bType == Long.class || bType == Integer.class || bType == String.class) {
                switch (bType.getName()) {
                    case "java.lang.Integer":
                        return
                                vType == Double.class ? ((Double) value).intValue() :
                                vType == Float.class ? ((Float) value).intValue() :
                                vType == Long.class ? ((Long) value).intValue() :
                                vType == String.class ? Str.tryParseInt(String.valueOf(value)) : value;
                    case "java.lang.Double":
                        return
                                vType == Integer.class ? Double.valueOf((Integer) value) :
                                vType == Float.class ? Double.valueOf((Float) value) :
                                vType == Long.class ? Double.valueOf((Long) value) :
                                vType == String.class ? Str.tryParseDouble(String.valueOf(value)) : value;
                    case "java.lang.Float":
                         return
                                vType == Integer.class ? Float.valueOf((Integer) value) :
                                vType == Double.class ? ((Double) value).floatValue() :
                                vType == Long.class ? ((Long) value).floatValue() :
                                vType == String.class ? Str.tryParseFloat(String.valueOf(value)) : value;
                    case "java.lang.Long":
                        return
                                vType == Integer.class ? Long.valueOf((Integer) value) :
                                vType == Double.class ? ((Double) value).longValue() :
                                vType == Float.class ? ((Float) value).longValue() :
                                vType == String.class ? Str.tryParseLong(String.valueOf(value)) : value;
                    case "java.lang.String":
                        return
                                vType == Integer.class ? Integer.toString((int) value) :
                                vType == Double.class ? Double.toString((double) value) :
                                vType == Float.class ? Float.toString((float) value) :
                                vType == Long.class ? Long.toString((long) value) : value;
                }
            }
        }


        // Lua 5.2 auto converts numbers into floating or integer values
    if (Integer.class.equals(value.getClass())) {
            if (long.class.equals(type)) return (long) (int) value;
            else if (float.class.equals(type)) return (float) (int) value;
        else if (double.class.equals(type))
            return (double) (int) value;
    } else if (Double.class.equals(value.getClass())) {
        if (float.class.equals(type))
            return (float) (double) value;
    } else if (value instanceof String && int.class.equals(type)) {
            return Integer.parseInt((String) value);
        }
        else if (value instanceof String && long.class.equals(type)) {
            return Long.parseLong((String) value);
        }
        else if (value instanceof String && float.class.equals(type))
            return Float.parseFloat((String) value);
        else if (value instanceof String && double.class.equals(type))
            return Double.parseDouble((String) value);

        return value;
    }
}
