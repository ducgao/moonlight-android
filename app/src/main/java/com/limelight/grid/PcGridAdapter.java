package com.limelight.grid;

import android.content.Context;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.limelight.PcView;
import com.limelight.R;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.preferences.PreferenceConfiguration;

import java.util.Collections;
import java.util.Comparator;

public class PcGridAdapter extends GenericGridAdapter<PcView.ComputerObject> {
    private static final float BLUR_RADIUS_PX = 6f;

    public PcGridAdapter(Context context, PreferenceConfiguration prefs) {
        super(context, getLayoutIdForPreferences(prefs));
    }

    private static int getLayoutIdForPreferences(PreferenceConfiguration prefs) {
        return R.layout.pc_carousel_item;
    }

    public void updateLayoutWithPreferences(Context context, PreferenceConfiguration prefs) {
        // This will trigger the view to reload with the new layout
        setLayoutId(getLayoutIdForPreferences(prefs));
    }

    public void addComputer(PcView.ComputerObject computer) {
        itemList.add(computer);
        sortList();
    }

    private void sortList() {
        Collections.sort(itemList, new Comparator<PcView.ComputerObject>() {
            @Override
            public int compare(PcView.ComputerObject lhs, PcView.ComputerObject rhs) {
                return lhs.details.name.toLowerCase().compareTo(rhs.details.name.toLowerCase());
            }
        });
    }

    public boolean removeComputer(PcView.ComputerObject computer) {
        return itemList.remove(computer);
    }

    @Override
    public void populateView(View parentView, ImageView imgView, ProgressBar prgView, TextView txtView, ImageView overlayView, PcView.ComputerObject obj) {
        imgView.setImageResource(R.drawable.pc_case);

        View card = parentView.findViewById(R.id.carousel_card);
        if (card != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // Clip the photo to the frame's rounded corners
            card.setClipToOutline(true);
        }

        // Blur the PC art while it is locked (online but not paired); the lock icon stays sharp
        boolean locked = obj.details.state == ComputerDetails.State.ONLINE &&
                obj.details.pairState == PairingManager.PairState.NOT_PAIRED;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            imgView.setRenderEffect(locked ?
                    RenderEffect.createBlurEffect(BLUR_RADIUS_PX, BLUR_RADIUS_PX, Shader.TileMode.DECAL) : null);
        }
        if (locked && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            // No blur support before Android 12, dim instead
            imgView.setAlpha(0.5f);
        }
        else if (obj.details.state == ComputerDetails.State.ONLINE) {
            imgView.setAlpha(1.0f);
        }
        else {
            imgView.setAlpha(0.4f);
        }

        if (obj.details.state == ComputerDetails.State.UNKNOWN) {
            prgView.setVisibility(View.VISIBLE);
        }
        else {
            prgView.setVisibility(View.INVISIBLE);
        }

        txtView.setText(obj.details.name);

        TextView statusView = parentView.findViewById(R.id.carousel_status);
        if (statusView != null) {
            int status;
            if (obj.details.state == ComputerDetails.State.ONLINE) {
                status = obj.details.pairState == PairingManager.PairState.NOT_PAIRED ?
                        R.string.carousel_status_not_paired : R.string.pcview_menu_header_online;
            }
            else if (obj.details.state == ComputerDetails.State.OFFLINE) {
                status = R.string.pcview_menu_header_offline;
            }
            else {
                status = R.string.pcview_menu_header_unknown;
            }
            statusView.setText(status);

        }
        if (obj.details.state == ComputerDetails.State.ONLINE) {
            txtView.setAlpha(1.0f);
        }
        else {
            txtView.setAlpha(0.4f);
        }

        if (obj.details.state == ComputerDetails.State.OFFLINE) {
            overlayView.setImageResource(R.drawable.ic_pc_offline);
            overlayView.setAlpha(0.4f);
            overlayView.setVisibility(View.VISIBLE);
        }
        // We must check if the status is exactly online and unpaired
        // to avoid colliding with the loading spinner when status is unknown
        else if (obj.details.state == ComputerDetails.State.ONLINE &&
                obj.details.pairState == PairingManager.PairState.NOT_PAIRED) {
            overlayView.setImageResource(R.drawable.ic_lock);
            overlayView.setAlpha(1.0f);
            overlayView.setVisibility(View.VISIBLE);
        }
        else {
            overlayView.setVisibility(View.GONE);
        }
    }
}
