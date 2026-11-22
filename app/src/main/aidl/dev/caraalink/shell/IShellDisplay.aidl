package dev.caraalink.shell;

import android.view.Surface;

/** What AA Bridge asks of its helper running with adb's rights through Shizuku. */
interface IShellDisplay {
    /** Shizuku's own transaction for ending a user service. */
    void destroy() = 16777114;

    /** A new screen drawn into [surface]; returns its display id, or -1. */
    int createDisplay(in Surface surface, int width, int height, int dpi) = 1;
    void releaseDisplay() = 2;
    /** Opens an activity ("package/class") on a display; returns what `am` printed. */
    String startApp(int displayId, String component) = 3;
    void touch(int displayId, int action, float x, float y, long downTime) = 4;
    void key(int displayId, int keycode) = 5;
}
