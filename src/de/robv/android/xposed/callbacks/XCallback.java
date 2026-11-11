package de.robv.android.xposed.callbacks;

public abstract class XCallback implements Comparable<XCallback> {
    public final int priority;

    public XCallback() {
        this.priority = PRIORITY_DEFAULT;
    }

    public XCallback(int priority) {
        this.priority = priority;
    }

    public static final int PRIORITY_DEFAULT = 50;
    public static final int PRIORITY_LOWEST = -10000;
    public static final int PRIORITY_HIGHEST = 10000;

    @Override
    public int compareTo(XCallback o) {
        return Integer.compare(o.priority, this.priority);
    }
}
