package com.limelight.ui;

import android.content.Context;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

/**
 * Box-art image that fits the whole artwork inside a square card and mirrors the same
 * picture into a sibling backdrop view (id {@code carousel_bg}), shown blurred and dimmed
 * to fill the empty sides. Works with the async asset loader, which just calls setImage*.
 */
public class CoverArtView extends ImageView {
    private static final float BLUR_RADIUS_PX = 70f;

    public CoverArtView(Context context) {
        super(context);
    }

    public CoverArtView(Context context, AttributeSet attrs) {
        super(context, attrs);
        // Clip the poster to its rounded background
        setClipToOutline(true);
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        ImageView backdrop = findBackdrop();
        if (backdrop != null) {
            backdrop.setImageDrawable(drawable);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                backdrop.setRenderEffect(drawable == null ? null :
                        RenderEffect.createBlurEffect(BLUR_RADIUS_PX, BLUR_RADIUS_PX, Shader.TileMode.CLAMP));
            }
        }
    }

    private ImageView findBackdrop() {
        if (!(getParent() instanceof ViewGroup)) {
            return null;
        }
        View v = ((ViewGroup) getParent()).findViewById(com.limelight.R.id.carousel_bg);
        return v instanceof ImageView ? (ImageView) v : null;
    }
}
