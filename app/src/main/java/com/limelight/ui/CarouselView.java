package com.limelight.ui;

import android.content.Context;
import android.database.DataSetObserver;
import android.util.AttributeSet;
import android.view.ContextMenu;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Horizontal, center-snapping carousel that displays the items of an existing {@link BaseAdapter}.
 *
 * - The centered item is shown at full size, neighbours are scaled down and dimmed.
 * - Optionally shows a trailing "add" item that is always present.
 * - Supports touch (swipe/fling + snap), D-pad / gamepad (left/right, A = open, X = menu)
 *   and long-press context menus that deliver {@link AdapterContextMenuInfo} like AbsListView does,
 *   so existing onCreateContextMenu / onContextItemSelected code keeps working.
 */
public class CarouselView extends RecyclerView {
    public interface OnItemClickListener {
        void onItemClick(View itemView, int position);
    }

    /** Gamepad X on an item. */
    public interface OnItemSecondaryActionListener {
        void onItemSecondaryAction(View itemView, int position);
    }

    public interface OnAddClickListener {
        void onAddClick();
    }

    private static final int TYPE_ITEM = 0;
    private static final int TYPE_ADD = 1;

    private static final float MIN_SCALE = 0.78f;
    private static final float MIN_ALPHA = 0.45f;

    private BaseAdapter source;
    private int addItemLayoutId;
    private OnItemClickListener itemClickListener;
    private OnAddClickListener addClickListener;
    private OnItemSecondaryActionListener secondaryActionListener;
    private AdapterContextMenuInfo contextMenuInfo;

    // Bumped when the source adapter is invalidated (e.g. item layout changed) so
    // we stop recycling item views that were inflated from the old layout.
    private int viewGeneration;

    private final BridgeAdapter bridge = new BridgeAdapter();

    private boolean selectFirstPending;
    // Keep re-selecting the first item while the list is still loading, until the user acts
    private boolean keepFirstSelected;

    private final DataSetObserver observer = new DataSetObserver() {
        @Override
        public void onChanged() {
            bridge.notifyDataSetChanged();
            post(transformRunnable);
            if (keepFirstSelected) {
                selectFirstPending = true;
            }
            maybeSelectFirst();
        }

        @Override
        public void onInvalidated() {
            viewGeneration++;
            bridge.notifyDataSetChanged();
            post(transformRunnable);
        }
    };

    private final Runnable transformRunnable = new Runnable() {
        @Override
        public void run() {
            applyTransforms();
        }
    };

    public CarouselView(Context context) {
        this(context, null);
    }

    public CarouselView(Context context, AttributeSet attrs) {
        super(context, attrs);

        setLayoutManager(new LinearLayoutManager(context, HORIZONTAL, false));
        new LinearSnapHelper().attachToRecyclerView(this);
        setItemAnimator(null);
        setClipToPadding(false);
        setClipChildren(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setHorizontalScrollBarEnabled(false);
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);

        addOnScrollListener(new OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                applyTransforms();
            }
        });
    }

    /**
     * @param adapter the item source
     * @param addItemLayoutId layout for a trailing "add" item, or 0 for none
     */
    public void setSourceAdapter(BaseAdapter adapter, int addItemLayoutId) {
        if (source != null) {
            source.unregisterDataSetObserver(observer);
        }
        this.source = adapter;
        this.addItemLayoutId = addItemLayoutId;
        if (source != null) {
            source.registerDataSetObserver(observer);
        }
        setAdapter(bridge);
    }

    /**
     * Scrolls to and focuses the first item as soon as one exists
     * (items usually arrive asynchronously after the screen opens).
     */
    public void selectFirstItemWhenAvailable() {
        selectFirstPending = true;
        keepFirstSelected = true;
        maybeSelectFirst();
    }

    private void maybeSelectFirst() {
        if (!selectFirstPending || source == null || source.getCount() == 0) {
            return;
        }
        selectFirstPending = false;
        ((LinearLayoutManager) getLayoutManager()).scrollToPositionWithOffset(0, 0);
        post(new Runnable() {
            @Override
            public void run() {
                ViewHolder first = findViewHolderForAdapterPosition(0);
                if (first != null) {
                    first.itemView.requestFocus();
                    applyTransforms();
                }
                else {
                    // Not laid out yet; try again on the next frame
                    selectFirstPending = true;
                    postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            maybeSelectFirst();
                        }
                    }, 16);
                }
            }
        });
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.itemClickListener = listener;
    }

    public void setOnItemSecondaryActionListener(OnItemSecondaryActionListener listener) {
        this.secondaryActionListener = listener;
    }

    public void setOnAddClickListener(OnAddClickListener listener) {
        this.addClickListener = listener;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (source != null) {
            source.unregisterDataSetObserver(observer);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (source != null) {
            // Re-register in case we were detached and reattached (no-op guard for duplicates)
            try {
                source.registerDataSetObserver(observer);
            } catch (IllegalStateException ignored) {
                // Already registered
            }
        }
    }

    // ---- Layout & visual effects ----

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);

        // Pad both sides so the first and last items can sit in the center
        View child = getChildAt(0);
        if (child != null && child.getWidth() > 0) {
            int side = Math.max(0, (getWidth() - child.getWidth()) / 2);
            if (side != getPaddingLeft() || side != getPaddingRight()) {
                final int pad = side;
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (getPaddingLeft() == pad && getPaddingRight() == pad) {
                            // An earlier queued pass already applied it; positions may be stale now
                            return;
                        }
                        // Keep the same item in the middle across the padding change; on the first
                        // pass (no padding yet) that's the first visible item rather than whatever
                        // happens to sit near the center.
                        LinearLayoutManager lm = (LinearLayoutManager) getLayoutManager();
                        int pos;
                        if (getPaddingLeft() == 0) {
                            pos = lm.findFirstVisibleItemPosition();
                        }
                        else {
                            View center = findCenterChild();
                            pos = center != null ? getChildAdapterPosition(center) : NO_POSITION;
                        }
                        setPadding(pad, getPaddingTop(), pad, getPaddingBottom());
                        if (pos != NO_POSITION) {
                            lm.scrollToPositionWithOffset(pos, 0);
                        }
                    }
                });
            }
        }
        applyTransforms();

    }

    private View findCenterChild() {
        View best = null;
        float bestDist = Float.MAX_VALUE;
        float center = getWidth() / 2f;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            float d = Math.abs((child.getLeft() + child.getRight()) / 2f - center);
            if (d < bestDist) {
                bestDist = d;
                best = child;
            }
        }
        return best;
    }

    private void applyTransforms() {
        float center = getWidth() / 2f;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            float childCenter = (child.getLeft() + child.getRight()) / 2f;
            float width = Math.max(1, child.getWidth());
            float f = Math.min(1f, Math.abs(childCenter - center) / width);

            float scale = 1f - (1f - MIN_SCALE) * f;
            child.setScaleX(scale);
            child.setScaleY(scale);
            child.setAlpha(1f - (1f - MIN_ALPHA) * f);
        }
    }

    private void scrollToCenter(View child) {
        int childCenter = (child.getLeft() + child.getRight()) / 2;
        int dx = childCenter - getWidth() / 2;
        if (dx != 0) {
            smoothScrollBy(dx, 0);
        }
    }

    // ---- Input ----

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            keepFirstSelected = false;
        }
        return super.onInterceptTouchEvent(e);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Only a new key press counts (a stray key-up from the previous screen must not)
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            keepFirstSelected = false;
        }

        // In touch mode only the RecyclerView itself can hold focus. The first gamepad key
        // leaves touch mode: hand focus to the centered item and let it handle the key
        // (so A opens the centered item right away instead of doing nothing).
        if (isFocused() && event.getAction() == KeyEvent.ACTION_DOWN) {
            View center = findCenterChild();
            if (center != null && center.requestFocus()) {
                switch (event.getKeyCode()) {
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        // First arrow press just selects the centered item
                        return true;
                    default:
                        return center.dispatchKeyEvent(event);
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    // ---- Context menu support (mirrors AbsListView) ----

    @Override
    public boolean showContextMenuForChild(View originalView) {
        return prepareContextMenuInfo(originalView) && super.showContextMenuForChild(originalView);
    }

    @Override
    public boolean showContextMenuForChild(View originalView, float x, float y) {
        return prepareContextMenuInfo(originalView) && super.showContextMenuForChild(originalView, x, y);
    }

    private boolean prepareContextMenuInfo(View originalView) {
        View item = findContainingItemView(originalView);
        if (item == null || source == null) {
            return false;
        }
        int pos = getChildAdapterPosition(item);
        if (pos < 0 || pos >= source.getCount()) {
            // The "add" item has no context menu
            return false;
        }
        contextMenuInfo = new AdapterContextMenuInfo(item, pos, source.getItemId(pos));
        return true;
    }

    @Override
    protected ContextMenu.ContextMenuInfo getContextMenuInfo() {
        return contextMenuInfo;
    }

    @Override
    public boolean showContextMenu() {
        // Long-pressing empty space must not open a menu without an item
        return false;
    }

    @Override
    public boolean showContextMenu(float x, float y) {
        return false;
    }

    // ---- Adapter bridge ----

    private static final class Holder extends ViewHolder {
        final FrameLayout wrapper;
        int generation = -1;

        Holder(FrameLayout wrapper) {
            super(wrapper);
            this.wrapper = wrapper;
        }
    }

    private final class BridgeAdapter extends RecyclerView.Adapter<Holder> {
        BridgeAdapter() {
            // Stable IDs let RecyclerView keep focus on the same item across
            // notifyDataSetChanged() (the app list refreshes in the background)
            setHasStableIds(true);
        }

        @Override
        public long getItemId(int position) {
            if (source == null || position >= source.getCount()) {
                return Long.MIN_VALUE; // the "add" item
            }
            return System.identityHashCode(source.getItem(position));
        }

        @Override
        public int getItemCount() {
            int count = source != null ? source.getCount() : 0;
            return addItemLayoutId != 0 ? count + 1 : count;
        }

        @Override
        public int getItemViewType(int position) {
            return (source == null || position >= source.getCount()) ? TYPE_ADD : TYPE_ITEM;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            FrameLayout wrapper = new FrameLayout(parent.getContext());
            wrapper.setLayoutParams(new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
            wrapper.setFocusable(true);
            wrapper.setClickable(true);
            wrapper.setLongClickable(true);
            wrapper.setClipChildren(false);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                // The centered (focused) item is already enlarged; the default grey focus box looks wrong
                wrapper.setDefaultFocusHighlightEnabled(false);
            }

            if (viewType == TYPE_ADD) {
                View add = LayoutInflater.from(parent.getContext()).inflate(addItemLayoutId, wrapper, false);
                wrapper.addView(add, centered());
            }

            final Holder holder = new Holder(wrapper);
            wrapper.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    onItemActivated(holder);
                }
            });
            wrapper.setOnLongClickListener(new OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    return holder.getItemViewType() == TYPE_ITEM && v.showContextMenu();
                }
            });
            wrapper.setOnFocusChangeListener(new OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus) {
                        scrollToCenter(v);
                    }
                }
            });
            wrapper.setOnKeyListener(new OnKeyListener() {
                @Override
                public boolean onKey(View v, int keyCode, KeyEvent event) {
                    if (keyCode == KeyEvent.KEYCODE_BUTTON_A) {
                        if (event.getAction() == KeyEvent.ACTION_UP) {
                            v.performClick();
                        }
                        return true;
                    }
                    if (keyCode == KeyEvent.KEYCODE_BUTTON_X || keyCode == KeyEvent.KEYCODE_MENU) {
                        if (event.getAction() == KeyEvent.ACTION_UP && holder.getItemViewType() == TYPE_ITEM) {
                            int pos = holder.getBindingAdapterPosition();
                            if (secondaryActionListener != null && pos != NO_POSITION) {
                                secondaryActionListener.onItemSecondaryAction(v, pos);
                            }
                            else {
                                v.showContextMenu();
                            }
                        }
                        return true;
                    }
                    return false;
                }
            });
            return holder;
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            if (holder.getItemViewType() != TYPE_ITEM) {
                return;
            }

            View old = (holder.generation == viewGeneration && holder.wrapper.getChildCount() > 0) ?
                    holder.wrapper.getChildAt(0) : null;
            View v = source.getView(position, old, holder.wrapper);
            if (v != old) {
                holder.wrapper.removeAllViews();
                holder.wrapper.addView(v, centered());
            }
            holder.generation = viewGeneration;
        }

        private FrameLayout.LayoutParams centered() {
            return new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER);
        }
    }

    private void onItemActivated(Holder holder) {
        int pos = holder.getBindingAdapterPosition();
        if (pos == NO_POSITION) {
            return;
        }

        // Tapping a side item first brings it to the center
        View item = holder.itemView;
        int childCenter = (item.getLeft() + item.getRight()) / 2;
        if (Math.abs(childCenter - getWidth() / 2) > item.getWidth() / 4) {
            scrollToCenter(item);
            return;
        }

        if (holder.getItemViewType() == TYPE_ADD) {
            if (addClickListener != null) {
                addClickListener.onAddClick();
            }
        }
        else if (itemClickListener != null) {
            itemClickListener.onItemClick(item, pos);
        }
    }
}
