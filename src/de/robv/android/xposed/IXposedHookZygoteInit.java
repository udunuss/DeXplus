package de.robv.android.xposed;

public interface IXposedHookZygoteInit extends IXposedMod {
    void initZygote(StartupParam startupParam) throws Throwable;

    public static final class StartupParam {
        public String modulePath;
        public boolean startsSystemServer;
    }
}
