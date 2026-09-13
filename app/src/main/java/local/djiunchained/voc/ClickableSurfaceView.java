package local.djiunchained.voc;

import android.content.Context;
import android.view.MotionEvent;
import android.view.SurfaceView;

/** SurfaceView with an explicit accessibility click implementation. */
final class ClickableSurfaceView extends SurfaceView {
    interface GestureHandler {
        boolean onTouch(MotionEvent event);
    }

    private GestureHandler gestureHandler;

    ClickableSurfaceView(Context context) {
        super(context);
    }

    void setGestureHandler(GestureHandler gestureHandler) {
        this.gestureHandler = gestureHandler;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean handled = gestureHandler != null && gestureHandler.onTouch(event);
        if (event.getActionMasked() == MotionEvent.ACTION_UP && !handled) {
            performClick();
            return true;
        }
        return handled;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
