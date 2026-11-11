package android.view;

import java.io.Closeable;

public class SurfaceControl {
    public boolean isValid() {
        return false;
    }

    public static class Transaction implements Closeable {
        public Transaction() {}

        public Transaction setLayer(SurfaceControl sc, int z) {
            return this;
        }

        public Transaction setAlpha(SurfaceControl sc, float alpha) {
            return this;
        }

        public void apply() {}

        @Override
        public void close() {}
    }
}
