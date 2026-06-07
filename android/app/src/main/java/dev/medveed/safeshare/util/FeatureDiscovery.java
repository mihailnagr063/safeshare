package dev.medveed.safeshare.util;

import android.app.Activity;
import android.view.View;

import androidx.annotation.StringRes;

import com.getkeepsafe.taptargetview.TapTarget;
import com.getkeepsafe.taptargetview.TapTargetView;

public final class FeatureDiscovery {

    private FeatureDiscovery() {}

    public static final class Step {
        public final int viewId;
        @StringRes public final int titleRes;
        @StringRes public final int descRes;

        public Step(int viewId, int titleRes, int descRes) {
            this.viewId = viewId;
            this.titleRes = titleRes;
            this.descRes = descRes;
        }
    }

    public static void start(Activity activity, Step[] steps, Runnable onFinished) {
        showNext(activity, steps, 0, onFinished);
    }

    private static void showNext(Activity activity, Step[] steps, int index, Runnable onFinished) {
        if (index >= steps.length) {
            if (onFinished != null) onFinished.run();
            return;
        }

        Step step = steps[index];
        View anchor = activity.findViewById(step.viewId);
        if (anchor == null || anchor.getVisibility() != View.VISIBLE) {
            showNext(activity, steps, index + 1, onFinished);
            return;
        }

        TapTargetView.showFor(activity,
                TapTarget.forView(anchor,
                                activity.getString(step.titleRes),
                                activity.getString(step.descRes))
                        .tintTarget(false)
                        .cancelable(false),
                new TapTargetView.Listener() {
                    @Override
                    public void onTargetClick(TapTargetView view) {
                        super.onTargetClick(view);
                        showNext(activity, steps, index + 1, onFinished);
                    }
                });
    }
}
