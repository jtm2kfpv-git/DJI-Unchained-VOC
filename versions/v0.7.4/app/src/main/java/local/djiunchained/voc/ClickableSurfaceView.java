package local.djiunchained.voc;

import android.content.Context;
import android.view.MotionEvent;
import android.view.SurfaceView;

/** SurfaceView with an explicit accessibility click implementation. */
final class ClickableSurfaceView extends SurfaceView {
    ClickableSurfaceView(Context context) {
        super(context);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            performClick();
        }
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
