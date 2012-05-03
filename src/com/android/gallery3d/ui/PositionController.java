/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.gallery3d.ui;

import android.content.Context;
import android.graphics.Rect;
import android.util.Log;
import android.widget.OverScroller;

import com.android.gallery3d.common.Utils;
import com.android.gallery3d.util.GalleryUtils;
import com.android.gallery3d.util.RangeArray;
import com.android.gallery3d.util.RangeIntArray;

class PositionController {
    private static final String TAG = "PositionController";

    public static final int IMAGE_AT_LEFT_EDGE = 1;
    public static final int IMAGE_AT_RIGHT_EDGE = 2;
    public static final int IMAGE_AT_TOP_EDGE = 4;
    public static final int IMAGE_AT_BOTTOM_EDGE = 8;

    // Special values for animation time.
    private static final long NO_ANIMATION = -1;
    private static final long LAST_ANIMATION = -2;

    private static final int ANIM_KIND_SCROLL = 0;
    private static final int ANIM_KIND_SCALE = 1;
    private static final int ANIM_KIND_SNAPBACK = 2;
    private static final int ANIM_KIND_SLIDE = 3;
    private static final int ANIM_KIND_ZOOM = 4;
    private static final int ANIM_KIND_OPENING = 5;
    private static final int ANIM_KIND_FLING = 6;
    private static final int ANIM_KIND_CAPTURE = 7;

    // Animation time in milliseconds. The order must match ANIM_KIND_* above.
    private static final int ANIM_TIME[] = {
        0,    // ANIM_KIND_SCROLL
        50,   // ANIM_KIND_SCALE
        600,  // ANIM_KIND_SNAPBACK
        400,  // ANIM_KIND_SLIDE
        300,  // ANIM_KIND_ZOOM
        600,  // ANIM_KIND_OPENING
        0,    // ANIM_KIND_FLING (the duration is calculated dynamically)
        800,  // ANIM_KIND_CAPTURE
    };

    // We try to scale up the image to fill the screen. But in order not to
    // scale too much for small icons, we limit the max up-scaling factor here.
    private static final float SCALE_LIMIT = 4;

    // For user's gestures, we give a temporary extra scaling range which goes
    // above or below the usual scaling limits.
    private static final float SCALE_MIN_EXTRA = 0.7f;
    private static final float SCALE_MAX_EXTRA = 1.4f;

    // Setting this true makes the extra scaling range permanent (until this is
    // set to false again).
    private boolean mExtraScalingRange = false;

    // Film Mode v.s. Page Mode: in film mode we show smaller pictures.
    private boolean mFilmMode = false;

    // These are the limits for width / height of the picture in film mode.
    private static final float FILM_MODE_PORTRAIT_HEIGHT = 0.48f;
    private static final float FILM_MODE_PORTRAIT_WIDTH = 0.7f;
    private static final float FILM_MODE_LANDSCAPE_HEIGHT = 0.7f;
    private static final float FILM_MODE_LANDSCAPE_WIDTH = 0.7f;

    // In addition to the focused box (index == 0). We also keep information
    // about this many boxes on each side.
    private static final int BOX_MAX = PhotoView.SCREEN_NAIL_MAX;

    private static final int IMAGE_GAP = GalleryUtils.dpToPixel(16);
    private static final int HORIZONTAL_SLACK = GalleryUtils.dpToPixel(12);

    private Listener mListener;
    private volatile Rect mOpenAnimationRect;
    private int mViewW = 640;
    private int mViewH = 480;;

    // A scaling guesture is in progress.
    private boolean mInScale;
    // The focus point of the scaling gesture, relative to the center of the
    // picture in bitmap pixels.
    private float mFocusX, mFocusY;

    // whether there is a previous/next picture.
    private boolean mHasPrev, mHasNext;

    // This is used by the fling animation (page mode).
    private FlingScroller mPageScroller;

    // This is used by the fling animation (film mode).
    private OverScroller mFilmScroller;

    // The bound of the stable region that the focused box can stay, see the
    // comments above calculateStableBound() for details.
    private int mBoundLeft, mBoundRight, mBoundTop, mBoundBottom;

    // Constrained frame is a rectangle that the focused box should fit into if
    // it is constrained. It has two effects:
    //
    // (1) In page mode, if the focused box is constrained, scaling for the
    // focused box is adjusted to fit into the constrained frame, instead of the
    // whole view.
    //
    // (2) In page mode, if the focused box is constrained, the mPlatform's
    // default center (mDefaultX/Y) is moved to the center of the constrained
    // frame, instead of the view center.
    //
    private Rect mConstrainedFrame = new Rect();

    // Whether the focused box is constrained.
    //
    // Our current program's first call to moveBox() sets constrained = true, so
    // we set the initial value of this variable to true, and we will not see
    // see unwanted transition animation.
    private boolean mConstrained = true;

    //
    //  ___________________________________________________________
    // |   _____       _____       _____       _____       _____   |
    // |  |     |     |     |     |     |     |     |     |     |  |
    // |  | Box |     | Box |     | Box*|     | Box |     | Box |  |
    // |  |_____|.....|_____|.....|_____|.....|_____|.....|_____|  |
    // |          Gap         Gap         Gap         Gap          |
    // |___________________________________________________________|
    //
    //                       <--  Platform  -->
    //
    // The focused box (Box*) centers at mPlatform's (mCurrentX, mCurrentY)

    private Platform mPlatform = new Platform();
    private RangeArray<Box> mBoxes = new RangeArray<Box>(-BOX_MAX, BOX_MAX);
    // The gap at the right of a Box i is at index i. The gap at the left of a
    // Box i is at index i - 1.
    private RangeArray<Gap> mGaps = new RangeArray<Gap>(-BOX_MAX, BOX_MAX - 1);

    // These are only used during moveBox().
    private RangeArray<Box> mTempBoxes = new RangeArray<Box>(-BOX_MAX, BOX_MAX);
    private RangeArray<Gap> mTempGaps =
        new RangeArray<Gap>(-BOX_MAX, BOX_MAX - 1);

    // The output of the PositionController. Available throught getPosition().
    private RangeArray<Rect> mRects = new RangeArray<Rect>(-BOX_MAX, BOX_MAX);

    public interface Listener {
        void invalidate();
        boolean isHolding();

        // EdgeView
        void onPull(int offset, int direction);
        void onRelease();
        void onAbsorb(int velocity, int direction);
    }

    public PositionController(Context context, Listener listener) {
        mListener = listener;
        mPageScroller = new FlingScroller();
        mFilmScroller = new OverScroller(context,
                null /* default interpolator */, false /* no flywheel */);

        // Initialize the areas.
        initPlatform();
        for (int i = -BOX_MAX; i <= BOX_MAX; i++) {
            mBoxes.put(i, new Box());
            initBox(i);
            mRects.put(i, new Rect());
        }
        for (int i = -BOX_MAX; i < BOX_MAX; i++) {
            mGaps.put(i, new Gap());
            initGap(i);
        }
    }

    public void setOpenAnimationRect(Rect r) {
        mOpenAnimationRect = r;
    }

    public void setViewSize(int viewW, int viewH) {
        if (viewW == mViewW && viewH == mViewH) return;

        mViewW = viewW;
        mViewH = viewH;
        initPlatform();

        for (int i = -BOX_MAX; i <= BOX_MAX; i++) {
            setBoxSize(i, viewW, viewH, true);
        }

        updateScaleAndGapLimit();
        snapAndRedraw();
    }

    public void setConstrainedFrame(Rect f) {
        if (mConstrainedFrame.equals(f)) return;
        mConstrainedFrame.set(f);
        mPlatform.updateDefaultXY();
        updateScaleAndGapLimit();
        snapAndRedraw();
    }

    public void setImageSize(int index, int width, int height, boolean force) {
        if (force) {
            Box b = mBoxes.get(index);
            b.mImageW = width;
            b.mImageH = height;
            return;
        }

        if (width == 0 || height == 0) {
            initBox(index);
        } else if (!setBoxSize(index, width, height, false)) {
            return;
        }

        updateScaleAndGapLimit();
        startOpeningAnimationIfNeeded();
        snapAndRedraw();
    }

    // Returns false if the box size doesn't change.
    private boolean setBoxSize(int i, int width, int height, boolean isViewSize) {
        Box b = mBoxes.get(i);
        boolean wasViewSize = b.mUseViewSize;

        // If we already have an image size, we don't want to use the view size.
        if (!wasViewSize && isViewSize) return false;

        b.mUseViewSize = isViewSize;

        if (width == b.mImageW && height == b.mImageH) {
            return false;
        }

        // The ratio of the old size and the new size.
        float ratio = Math.min(
                (float) b.mImageW / width, (float) b.mImageH / height);

        b.mImageW = width;
        b.mImageH = height;

        // If this is the first time we receive an image size, we change the
        // scale directly. Otherwise adjust the scales by a ratio, and snapback
        // will animate the scale into the min/max bounds if necessary.
        if (wasViewSize && !isViewSize) {
            b.mCurrentScale = getMinimalScale(b);
            b.mAnimationStartTime = NO_ANIMATION;
        } else {
            b.mCurrentScale *= ratio;
            b.mFromScale *= ratio;
            b.mToScale *= ratio;
        }

        if (i == 0) {
            mFocusX /= ratio;
            mFocusY /= ratio;
        }

        return true;
    }

    private void startOpeningAnimationIfNeeded() {
        if (mOpenAnimationRect == null) return;
        Box b = mBoxes.get(0);
        if (b.mUseViewSize) return;

        // Start animation from the saved rectangle if we have one.
        Rect r = mOpenAnimationRect;
        mOpenAnimationRect = null;
        mPlatform.mCurrentX = r.centerX() - mViewW / 2;
        b.mCurrentY = r.centerY() - mViewH / 2;
        b.mCurrentScale = Math.max(r.width() / (float) b.mImageW,
                r.height() / (float) b.mImageH);
        startAnimation(mPlatform.mDefaultX, 0, b.mScaleMin,
                ANIM_KIND_OPENING);
    }

    public void setFilmMode(boolean enabled) {
        if (enabled == mFilmMode) return;
        mFilmMode = enabled;

        mPlatform.updateDefaultXY();
        updateScaleAndGapLimit();
        stopAnimation();
        snapAndRedraw();
    }

    public void setExtraScalingRange(boolean enabled) {
        if (mExtraScalingRange == enabled) return;
        mExtraScalingRange = enabled;
        if (!enabled) {
            snapAndRedraw();
        }
    }

    // This should be called whenever the scale range of boxes or the default
    // gap size may change. Currently this can happen due to change of view
    // size, image size, mFilmMode, mConstrained, and mConstrainedFrame.
    private void updateScaleAndGapLimit() {
        for (int i = -BOX_MAX; i <= BOX_MAX; i++) {
            Box b = mBoxes.get(i);
            b.mScaleMin = getMinimalScale(b);
            b.mScaleMax = getMaximalScale(b);
        }

        for (int i = -BOX_MAX; i < BOX_MAX; i++) {
            Gap g = mGaps.get(i);
            g.mDefaultSize = getDefaultGapSize(i);
        }
    }

    // Returns the default gap size according the the size of the boxes around
    // the gap and the current mode.
    private int getDefaultGapSize(int i) {
        if (mFilmMode) return IMAGE_GAP;
        Box a = mBoxes.get(i);
        Box b = mBoxes.get(i + 1);
        return IMAGE_GAP + Math.max(gapToSide(a), gapToSide(b));
    }

    // Here is how we layout the boxes in the page mode.
    //
    //   previous             current             next
    //  ___________       ________________     __________
    // |  _______  |     |   __________   |   |  ______  |
    // | |       | |     |  |   right->|  |   | |      | |
    // | |       |<-------->|<--left   |  |   | |      | |
    // | |_______| |  |  |  |__________|  |   | |______| |
    // |___________|  |  |________________|   |__________|
    //                |  <--> gapToSide()
    //                |
    // IMAGE_GAP + MAX(gapToSide(previous), gapToSide(current))
    private int gapToSide(Box b) {
        return (int) ((mViewW - getMinimalScale(b) * b.mImageW) / 2 + 0.5f);
    }

    // Stop all animations at where they are now.
    public void stopAnimation() {
        mPlatform.mAnimationStartTime = NO_ANIMATION;
        for (int i = -BOX_MAX; i <= BOX_MAX; i++) {
            mBoxes.get(i).mAnimationStartTime = NO_ANIMATION;
        }
        for (int i = -BOX_MAX; i < BOX_MAX; i++) {
            mGaps.get(i).mAnimationStartTime = NO_ANIMATION;
        }
    }

    public void skipAnimation() {
        if (mPlatform.mAnimationStartTime != NO_ANIMATION) {
            mPlatform.mCurrentX = mPlatform.mToX;
            mPlatform.mCurrentY = mPlatform.mToY;
            mPlatform.mAnimationStartTime = NO_ANIMATION;
        }
        for (int i = -BOX_MAX; i <= BOX_MAX; i++) {
            Box b = mBoxes.get(i);
            if (b.mAnimationStartTime == NO_ANIMATION) continue;
            b.mCurrentY = b.mToY;
            b.mCurrentScale = b.mToScale;
            b.mAnimationStartTime = NO_ANIMATION;
        }
        for (int i = -BOX_MAX; i < BOX_MAX; i++) {
            Gap g = mGaps.get(i);
            if (g.mAnimationStartTime == NO_ANIMATION) continue;
            g.mCurrentGap = g.mToGap;
            g.mAnimationStartTime = NO_ANIMATION;
        }
        redraw();
    }

    public void snapback() {
        snapAndRedraw();
    }

    ////////////////////////////////////////////////////////////////////////////
    //  Start an animations for the focused box
    ////////////////////////////////////////////////////////////////////////////

    public void zoomIn(float tapX, float tapY, float targetScale) {
        tapX -= mViewW / 2;
        tapY -= mViewH / 2;
        Box b = mBoxes.get(0);

        // Convert the tap position to distance to center in bitmap coordinates
        float tempX = (tapX - mPlatform.mCurrentX) / b.mCurrentScale;
        float tempY = (tapY - b.mCurrentY) / b.mCurrentScale;

        int x = (int) (-tempX * targetScale + 0.5f);
        int y = (int) (-tempY * targetScale + 0.5f);

        calculateStableBound(targetScale);
        int targetX = Utils.clamp(x, mBoundLeft, mBoundRight);
        int targetY = Utils.clamp(y, mBoundTop, mBoundBottom);
        targetScale = Utils.clamp(targetScale, b.mScaleMin, b.mScaleMax);

        startAnimation(targetX, targetY, targetScale, ANIM_KIND_ZOOM);
    }

    public void resetToFullView() {
        Box b = mBoxes.get(0);
        startAnimation(mPlatform.mDefaultX, 0, b.mScaleMin, ANIM_KIND_ZOOM);
    }

    public void beginScale(float focusX, float focusY) {
        focusX -= mViewW / 2;
        focusY -= mViewH / 2;
        Box b = mBoxes.get(0);
        Platform p = mPlatform;
        mInScale = true;
        mFocusX = (int) ((focusX - p.mCurrentX) / b.mCurrentScale + 0.5f);
        mFocusY = (int) ((focusY - b.mCurrentY) / b.mCurrentScale + 0.5f);
    }

    // Scales the image by the given factor.
    // Returns an out-of-range indicator:
    //   1 if the intended scale is too large for the stable range.
    //   0 if the intended scale is in the stable range.
    //  -1 if the intended scale is too small for the stable range.
    public int scaleBy(float s, float focusX, float focusY) {
        focusX -= mViewW / 2;
        focusY -= mViewH / 2;
        Box b = mBoxes.get(0);
        Platform p = mPlatform;

        // We want to keep the focus point (on the bitmap) the same as when we
        // begin the scale guesture, that is,
        //
        // (focusX' - currentX') / scale' = (focusX - currentX) / scale
        //
        s *= getTargetScale(b);
        int x = mFilmMode ? p.mCurrentX : (int) (focusX - s * mFocusX + 0.5f);
        int y = mFilmMode ? b.mCurrentY : (int) (focusY - s * mFocusY + 0.5f);
        startAnimation(x, y, s, ANIM_KIND_SCALE);
        if (s < b.mScaleMin) return -1;
        if (s > b.mScaleMax) return 1;
        return 0;
    }

    public void endScale() {
        mInScale = false;
        snapAndRedraw();
    }

    // Slide the focused box to the center of the view.
    public void startHorizontalSlide() {
        Box b = mBoxes.get(0);
        startAnimation(mPlatform.mDefaultX, 0, b.mScaleMin, ANIM_KIND_SLIDE);
    }

    // Slide the focused box to the center of the view with the capture
    // animation. In addition to the sliding, the animation will also scale the
    // the focused box, the specified neighbor box, and the gap between the
    // two. The specified offset should be 1 or -1.
    public void startCaptureAnimationSlide(int offset) {
        Box b = mBoxes.get(0);
        Box n = mBoxes.get(offset);  // the neighbor box
        Gap g = mGaps.get(offset);  // the gap between the two boxes

        mPlatform.doAnimation(mPlatform.mDefaultX, mPlatform.mDefaultY,
                ANIM_KIND_CAPTURE);
        b.doAnimation(0, b.mScaleMin, ANIM_KIND_CAPTURE);
        n.doAnimation(0, n.mScaleMin, ANIM_KIND_CAPTURE);
        g.doAnimation(g.mDefaultSize, ANIM_KIND_CAPTURE);
        redraw();
    }

    public void startScroll(float dx, float dy) {
        Box b = mBoxes.get(0);
        Platform p = mPlatform;

        int x = getTargetX(p) + (int) (dx + 0.5f);
        int y = getTargetY(b) + (int) (dy + 0.5f);

        if (mFilmMode) {
            scrollToFilm(x, y);
        } else {
            scrollToPage(x, y);
        }
    }

    private void scrollToPage(int x, int y) {
        Box b = mBoxes.get(0);

        calculateStableBound(b.mCurrentScale);

        // Vertical direction: If we have space to move in the vertical
        // direction, we show the edge effect when scrolling reaches the edge.
        if (mBoundTop != mBoundBottom) {
            if (y < mBoundTop) {
                mListener.onPull(mBoundTop - y, EdgeView.BOTTOM);
            } else if (y > mBoundBottom) {
                mListener.onPull(y - mBoundBottom, EdgeView.TOP);
            }
        }

        y = Utils.clamp(y, mBoundTop, mBoundBottom);

        // Horizontal direction: we show the edge effect when the scrolling
        // tries to go left of the first image or go right of the last image.
        if (!mHasPrev && x > mBoundRight) {
            int pixels = x - mBoundRight;
            mListener.onPull(pixels, EdgeView.LEFT);
            x = mBoundRight;
        } else if (!mHasNext && x < mBoundLeft) {
            int pixels = mBoundLeft - x;
            mListener.onPull(pixels, EdgeView.RIGHT);
            x = mBoundLeft;
        }

        startAnimation(x, y, b.mCurrentScale, ANIM_KIND_SCROLL);
    }

    private void scrollToFilm(int x, int y) {
        Box b = mBoxes.get(0);

        // Horizontal direction: we show the edge effect when the scrolling
        // tries to go left of the first image or go right of the last image.
        x -= mPlatform.mDefaultX;
        if (!mHasPrev && x > 0) {
            mListener.onPull(x, EdgeView.LEFT);
            x = 0;
        } else if (!mHasNext && x < 0) {
            mListener.onPull(-x, EdgeView.RIGHT);
            x = 0;
        }
        x += mPlatform.mDefaultX;
        startAnimation(x, y, b.mCurrentScale, ANIM_KIND_SCROLL);
    }

    public boolean fling(float velocityX, float velocityY) {
        int vx = (int) (velocityX + 0.5f);
        int vy = (int) (velocityY + 0.5f);
        return mFilmMode ? flingFilm(vx, vy) : flingPage(vx, vy);
    }

    private boolean flingPage(int velocityX, int velocityY) {
        Box b = mBoxes.get(0);
        Platform p = mPlatform;

        // We only want to do fling when the picture is zoomed-in.
        if (viewWiderThanScaledImage(b.mCurrentScale) &&
            viewTallerThanScaledImage(b.mCurrentScale)) {
            return false;
        }

        // We only allow flinging in the directions where it won't go over the
        // picture.
        int edges = getImageAtEdges();
        if ((velocityX > 0 && (edges & IMAGE_AT_LEFT_EDGE) != 0) ||
            (velocityX < 0 && (edges & IMAGE_AT_RIGHT_EDGE) != 0)) {
            velocityX = 0;
        }
        if ((velocityY > 0 && (edges & IMAGE_AT_TOP_EDGE) != 0) ||
            (velocityY < 0 && (edges & IMAGE_AT_BOTTOM_EDGE) != 0)) {
            velocityY = 0;
        }

        if (velocityX == 0 && velocityY == 0) return false;

        mPageScroller.fling(p.mCurrentX, b.mCurrentY, velocityX, velocityY,
                mBoundLeft, mBoundRight, mBoundTop, mBoundBottom);
        int targetX = mPageScroller.getFinalX();
        int targetY = mPageScroller.getFinalY();
        ANIM_TIME[ANIM_KIND_FLING] = mPageScroller.getDuration();
        startAnimation(targetX, targetY, b.mCurrentScale, ANIM_KIND_FLING);
        return true;
    }

    private boolean flingFilm(int velocityX, int velocityY) {
        Box b = mBoxes.get(0);
        Platform p = mPlatform;

        // If we are already at the edge, don't start the fling.
        int defaultX = p.mDefaultX;
        if ((!mHasPrev && p.mCurrentX >= defaultX)
                || (!mHasNext && p.mCurrentX <= defaultX)) {
            return false;
        }

        if (velocityX == 0) return false;

        mFilmScroller.fling(p.mCurrentX, 0, velocityX, 0,
                Integer.MIN_VALUE, Integer.MAX_VALUE, 0, 0);
        int targetX = mFilmScroller.getFinalX();
        // This value doesn't matter because we use mFilmScroller.isFinished()
        // to decide when to stop. We set this to 0 so it's faster for
        // Animatable.advanceAnimation() to calculate the progress (always 1).
        ANIM_TIME[ANIM_KIND_FLING] = 0;
        startAnimation(targetX, b.mCurrentY, b.mCurrentScale, ANIM_KIND_FLING);
        return true;
    }

    ////////////////////////////////////////////////////////////////////////////
    //  Redraw
    //
    //  If a method changes box positions directly, redraw()
    //  should be called.
    //
    //  If a method may also cause a snapback to happen, snapAndRedraw() should
    //  be called.
    //
    //  If a method starts an animation to change the position of focused box,
    //  startAnimation() should be called.
    //
    //  If time advances to change the box position, advanceAnimation() should
    //  be called.
    ////////////////////////////////////////////////////////////////////////////
    private void redraw() {
        layoutAndSetPosition();
        mListener.invalidate();
    }

    private void snapAndRedraw() {
        mPlatform.startSnapback();
        for (int i = -BOX_MAX; i <= BOX_MAX; i++) {
            mBoxes.get(i).startSnapback();
        }
        for (int i = -BOX_MAX; i < BOX_MAX; i++) {
            mGaps.get(i).startSnapback();
        }
        redraw();
    }

    private void startAnimation(int targetX, int targetY, float targetScale,
            int kind) {
        boolean changed = false;
        changed |= mPlatform.doAnimation(targetX, mPlatform.mDefaultY, kind);
        changed |= mBoxes.get(0).doAnimation(targetY, targetScale, kind);
        if (changed) redraw();
    }

    public void advanceAnimation() {
        boolean changed = false;
        changed |= mPlatform.advanceAnimation();
        for (int i = -BOX_MAX; i <= BOX_MAX; i++) {
            changed |= mBoxes.get(i).advanceAnimation();
        }
        for (int i = -BOX_MAX; i < BOX_MAX; i++) {
            changed |= mGaps.get(i).advanceAnimation();
        }
        if (changed) redraw();
    }

    ////////////////////////////////////////////////////////////////////////////
    //  Layout
    ////////////////////////////////////////////////////////////////////////////

    // Returns the display width of this box.
    private int widthOf(Box b) {
        return (int) (b.mImageW * b.mCurrentScale + 0.5f);
    }

    // Returns the display height of this box.
    private int heightOf(Box b) {
        return (int) (b.mImageH * b.mCurrentScale + 0.5f);
    }

    // Returns the display width of this box, using the given scale.
    private int widthOf(Box b, float scale) {
        return (int) (b.mImageW * scale + 0.5f);
    }

    // Returns the display height of this box, using the given scale.
    private int heightOf(Box b, float scale) {
        return (int) (b.mImageH * scale + 0.5f);
    }

    // Convert the information in mPlatform and mBoxes to mRects, so the user
    // can get the position of each box by getPosition().
    //
    // Note the loop index goes from inside-out because each box's X coordinate
    // is relative to its anchor box (except the focused box).
    private void layoutAndSetPosition() {
        // layout box 0 (focused box)
        convertBoxToRect(0);
        for (int i = 1; i <= BOX_MAX; i++) {
            // layout box i and -i
            convertBoxToRect(i);
            convertBoxToRect(-i);
        }
        //dumpState();
    }

    private void dumpState() {
        for (int i = -BOX_MAX; i < BOX_MAX; i++) {
            Log.d(TAG, "Gap " + i + ": " + mGaps.get(i).mCurrentGap);
        }

        dumpRect(0);
        for (int i = 1; i <= BOX_MAX; i++) {
            dumpRect(i);
            dumpRect(-i);
        }

        for (int i = -BOX_MAX; i <= BOX_MAX; i++) {
            for (int j = i + 1; j <= BOX_MAX; j++) {
                if (Rect.intersects(mRects.get(i), mRects.get(j))) {
                    Log.d(TAG, "rect " + i + " and rect " + j + "intersects!");
                }
            }
        }
    }

    private void dumpRect(int i) {
        StringBuilder sb = new StringBuilder();
        Rect r = mRects.get(i);
        sb.append("Rect " + i + ":");
        sb.append("(");
        sb.append(r.centerX());
        sb.append(",");
        sb.append(r.centerY());
        sb.append(") [");
        sb.append(r.width());
        sb.append("x");
        sb.append(r.height());
        sb.append("]");
        Log.d(TAG, sb.toString());
    }

    private void convertBoxToRect(int i) {
        Box b = mBoxes.get(i);
        Rect r = mRects.get(i);
        int y = b.mCurrentY + mPlatform.mCurrentY + mViewH / 2;
        int w = widthOf(b);
        int h = heightOf(b);
        if (i == 0) {
            int x = mPlatform.mCurrentX + mViewW / 2;
            r.left = x - w / 2;
            r.right = r.left + w;
        } else if (i > 0) {
            Rect a = mRects.get(i - 1);
            Gap g = mGaps.get(i - 1);
            r.left = a.right + g.mCurrentGap;
            r.right = r.left + w;
        } else {  // i < 0
            Rect a = mRects.get(i + 1);
            Gap g = mGaps.get(i);
            r.right = a.left - g.mCurrentGap;
            r.left = r.right - w;
        }
        r.top = y - h / 2;
        r.bottom = r.top + h;
    }

    // Returns the position of a box.
    public Rect getPosition(int index) {
        return mRects.get(index);
    }

    ////////////////////////////////////////////////////////////////////////////
    //  Box management
    ////////////////////////////////////////////////////////////////////////////

    // Initialize the platform to be at the view center.
    private void initPlatform() {
        mPlatform.updateDefaultXY();
        mPlatform.mCurrentX = mPlatform.mDefaultX;
        mPlatform.mCurrentY = mPlatform.mDefaultY;
        mPlatform.mAnimationStartTime = NO_ANIMATION;
    }

    // Initialize a box to have the size of the view.
    private void initBox(int index) {
        Box b = mBoxes.get(index);
        b.mImageW = mViewW;
        b.mImageH = mViewH;
        b.mUseViewSize = true;
        b.mScaleMin = getMinimalScale(b);
        b.mScaleMax = getMaximalScale(b);
        b.mCurrentY = 0;
        b.mCurrentScale = b.mScaleMin;
        b.mAnimationStartTime = NO_ANIMATION;
    }

    // Initialize a gap. This can only be called after the boxes around the gap
    // has been initialized.
    private void initGap(int index) {
        Gap g = mGaps.get(index);
        g.mDefaultSize = getDefaultGapSize(index);
        g.mCurrentGap = g.mDefaultSize;
        g.mAnimationStartTime = NO_ANIMATION;
    }

    private void initGap(int index, int size) {
        Gap g = mGaps.get(index);
        g.mDefaultSize = getDefaultGapSize(index);
        g.mCurrentGap = size;
        g.mAnimationStartTime = NO_ANIMATION;
    }

    private void debugMoveBox(int fromIndex[]) {
        StringBuilder s = new StringBuilder("moveBox:");
        for (int i = 0; i < fromIndex.length; i++) {
            int j = fromIndex[i];
            if (j == Integer.MAX_VALUE) {
                s.append(" N");
            } else {
                s.append(" ");
                s.append(fromIndex[i]);
            }
        }
        Log.d(TAG, s.toString());
    }

    // Move the boxes: it may indicate focus change, box deleted, box appearing,
    // box reordered, etc.
    //
    // Each element in the fromIndex array indicates where each box was in the
    // old array. If the value is Integer.MAX_VALUE (pictured as N below), it
    // means the box is new.
    //
    // For example:
    // N N N N N N N -- all new boxes
    // -3 -2 -1 0 1 2 3 -- nothing changed
    // -2 -1 0 1 2 3 N -- focus goes to the next box
    // N -3 -2 -1 0 1 2 -- focuse goes to the previous box
    // -3 -2 -1 1 2 3 N -- the focused box was deleted.
    //
    // hasPrev/hasNext indicates if there are previous/next boxes for the
    // focused box. constrained indicates whether the focused box should be put
    // into the constrained frame.
    public void moveBox(int fromIndex[], boolean hasPrev, boolean hasNext,
            boolean constrained) {
        //debugMoveBox(fromIndex);
        mHasPrev = hasPrev;
        mHasNext = hasNext;

        RangeIntArray from = new RangeIntArray(fromIndex, -BOX_MAX, BOX_MAX);

        // 1. Get the absolute X coordiates for the boxes.
        layoutAndSetPosition();
        for (int i = -BOX_MAX; i <= BOX_MAX; i++) {
            Box b = mBoxes.get(i);
            Rect r = mRects.get(i);
            b.mAbsoluteX = r.centerX() - mViewW / 2;
        }

        // 2. copy boxes and gaps to temporary storage.
        for (int i = -BOX_MAX; i <= BOX_MAX; i++) {
            mTempBoxes.put(i, mBoxes.get(i));
            mBoxes.put(i, null);
        }
        for (int i = -BOX_MAX; i < BOX_MAX; i++) {
            mTempGaps.put(i, mGaps.get(i));
            mGaps.put(i, null);
        }

        // 3. move back boxes that are used in the new array.
        for (int i = -BOX_MAX; i <= BOX_MAX; i++) {
            int j = from.get(i);
            if (j == Integer.MAX_VALUE) continue;
            mBoxes.put(i, mTempBoxes.get(j));
            mTempBoxes.put(j, null);
        }

        // 4. move back gaps if both boxes around it are kept together.
        for (int i = -BOX_MAX; i < BOX_MAX; i++) {
            int j = from.get(i);
            if (j == Integer.MAX_VALUE) continue;
            int k = from.get(i + 1);
            if (k == Integer.MAX_VALUE) continue;
            if (j + 1 == k) {
                mGaps.put(i, mTempGaps.get(j));
                mTempGaps.put(j, null);
            }
        }

        // 5. recycle the boxes that are not used in the new array.
        int k = -BOX_MAX;
        for (int i = -BOX_MAX; i <= BOX_MAX; i++) {
            if (mBoxes.get(i) != null) continue;
            while (mTempBoxes.get(k) == null) {
                k++;
            }
            mBoxes.put(i, mTempBoxes.get(k++));
            initBox(i);
        }

        // 6. Now give the recycled box a reasonable absolute X position.
        //
        // First try to find the first and the last box which the absolute X
        // position is known.
        int first, last;
        for (first = -BOX_MAX; first <= BOX_MAX; first++) {
            if (from.get(first) != Integer.MAX_VALUE) break;
        }
        for (last = BOX_MAX; last >= -BOX_MAX; last--) {
            if (from.get(last) != Integer.MAX_VALUE) break;
        }
        // If there is no box has known X position at all, make the focused one
        // as known.
        if (first > BOX_MAX) {
            mBoxes.get(0).mAbsoluteX = mPlatform.mCurrentX;
            first = last = 0;
        }
        // Now for those boxes between first and last, just assign the same
        // position as the next box. (We can do better, but this should be
        // rare). For the boxes before first or after last, we will use a new
        // default gap size below.
        for (int i = last - 1; i > first; i--) {
            if (from.get(i) != Integer.MAX_VALUE) continue;
            mBoxes.get(i).mAbsoluteX = mBoxes.get(i + 1).mAbsoluteX;
        }

        // 7. recycle the gaps that are not used in the new array.
        k = -BOX_MAX;
        for (int i = -BOX_MAX; i < BOX_MAX; i++) {
            if (mGaps.get(i) != null) continue;
            while (mTempGaps.get(k) == null) {
                k++;
            }
            mGaps.put(i, mTempGaps.get(k++));
            Box a = mBoxes.get(i);
            Box b = mBoxes.get(i + 1);
            int wa = widthOf(a);
            int wb = widthOf(b);
            if (i >= first && i < last) {
                int g = b.mAbsoluteX - a.mAbsoluteX - wb / 2 - (wa - wa / 2);
                initGap(i, g);
            } else {
                initGap(i);
            }
        }

        // 8. offset the Platform position
        int dx = mBoxes.get(0).mAbsoluteX - mPlatform.mCurrentX;
        mPlatform.mCurrentX += dx;
        mPlatform.mFromX += dx;
        mPlatform.mToX += dx;
        mPlatform.mFlingOffset += dx;

        if (mConstrained != constrained) {
            mConstrained = constrained;
            mPlatform.updateDefaultXY();
            updateScaleAndGapLimit();
        }

        snapAndRedraw();
    }

    ////////////////////////////////////////////////////////////////////////////
    //  Public utilities
    ////////////////////////////////////////////////////////////////////////////

    public boolean isAtMinimalScale() {
        Box b = mBoxes.get(0);
        return isAlmostEqual(b.mCurrentScale, b.mScaleMin);
    }

    public boolean isCenter() {
        Box b = mBoxes.get(0);
        return mPlatform.mCurrentX == mPlatform.mDefaultX
            && b.mCurrentY == 0;
    }

    public int getImageWidth() {
        Box b = mBoxes.get(0);
        return b.mImageW;
    }

    public int getImageHeight() {
        Box b = mBoxes.get(0);
        return b.mImageH;
    }

    public float getImageScale() {
        Box b = mBoxes.get(0);
        return b.mCurrentScale;
    }

    public int getImageAtEdges() {
        Box b = mBoxes.get(0);
        Platform p = mPlatform;
        calculateStableBound(b.mCurrentScale);
        int edges = 0;
        if (p.mCurrentX <= mBoundLeft) {
            edges |= IMAGE_AT_RIGHT_EDGE;
        }
        if (p.mCurrentX >= mBoundRight) {
            edges |= IMAGE_AT_LEFT_EDGE;
        }
        if (b.mCurrentY <= mBoundTop) {
            edges |= IMAGE_AT_BOTTOM_EDGE;
        }
        if (b.mCurrentY >= mBoundBottom) {
            edges |= IMAGE_AT_TOP_EDGE;
        }
        return edges;
    }

    public boolean isScrolling() {
        return mPlatform.mAnimationStartTime != NO_ANIMATION
                && mPlatform.mCurrentX != mPlatform.mToX;
    }

    public void stopScrolling() {
        if (mPlatform.mAnimationStartTime == NO_ANIMATION) return;
        mPlatform.mFromX = mPlatform.mToX = mPlatform.mCurrentX;
    }

    ////////////////////////////////////////////////////////////////////////////
    //  Private utilities
    ////////////////////////////////////////////////////////////////////////////

    private float getMinimalScale(Box b) {
        float wFactor = 1.0f;
        float hFactor = 1.0f;
        int viewW, viewH;

        if (!mFilmMode && mConstrained && !mConstrainedFrame.isEmpty()
                && b == mBoxes.get(0)) {
            viewW = mConstrainedFrame.width();
            viewH = mConstrainedFrame.height();
        } else {
            viewW = mViewW;
            viewH = mViewH;
        }

        if (mFilmMode) {
            if (mViewH > mViewW) {  // portrait
                wFactor = FILM_MODE_PORTRAIT_WIDTH;
                hFactor = FILM_MODE_PORTRAIT_HEIGHT;
            } else {  // landscape
                wFactor = FILM_MODE_LANDSCAPE_WIDTH;
                hFactor = FILM_MODE_LANDSCAPE_HEIGHT;
            }
        }

        float s = Math.min(wFactor * viewW / b.mImageW,
                hFactor * viewH / b.mImageH);
        return Math.min(SCALE_LIMIT, s);
    }

    private float getMaximalScale(Box b) {
        return mFilmMode ? getMinimalScale(b) : SCALE_LIMIT;
    }

    private static boolean isAlmostEqual(float a, float b) {
        float diff = a - b;
        return (diff < 0 ? -diff : diff) < 0.02f;
    }

    // Calculates the stable region of mPlatform.mCurrentX and
    // mBoxes.get(0).mCurrentY, where "stable" means
    //
    // (1) If the dimension of scaled image >= view dimension, we will not
    // see black region outside the image (at that dimension).
    // (2) If the dimension of scaled image < view dimension, we will center
    // the scaled image.
    //
    // We might temporarily go out of this stable during user interaction,
    // but will "snap back" after user stops interaction.
    //
    // The results are stored in mBound{Left/Right/Top/Bottom}.
    //
    // An extra parameter "horizontalSlack" (which has the value of 0 usually)
    // is used to extend the stable region by some pixels on each side
    // horizontally.
    private void calculateStableBound(float scale, int horizontalSlack) {
        Box b = mBoxes.get(0);

        // The width and height of the box in number of view pixels
        int w = widthOf(b, scale);
        int h = heightOf(b, scale);

        // When the edge of the view is aligned with the edge of the box
        mBoundLeft = (mViewW + 1) / 2 - (w + 1) / 2 - horizontalSlack;
        mBoundRight = w / 2 - mViewW / 2 + horizontalSlack;
        mBoundTop = (mViewH + 1) / 2 - (h + 1) / 2;
        mBoundBottom = h / 2 - mViewH / 2;

        // If the scaled height is smaller than the view height,
        // force it to be in the center.
        if (viewTallerThanScaledImage(scale)) {
            mBoundTop = mBoundBottom = 0;
        }

        // Same for width
        if (viewWiderThanScaledImage(scale)) {
            mBoundLeft = mBoundRight = mPlatform.mDefaultX;
        }
    }

    private void calculateStableBound(float scale) {
        calculateStableBound(scale, 0);
    }

    private boolean viewTallerThanScaledImage(float scale) {
        return mViewH >= heightOf(mBoxes.get(0), scale);
    }

    private boolean viewWiderThanScaledImage(float scale) {
        return mViewW >= widthOf(mBoxes.get(0), scale);
    }

    private float getTargetScale(Box b) {
        return useCurrentValueAsTarget(b) ? b.mCurrentScale : b.mToScale;
    }

    private int getTargetX(Platform p) {
        return useCurrentValueAsTarget(p) ? p.mCurrentX : p.mToX;
    }

    private int getTargetY(Box b) {
        return useCurrentValueAsTarget(b) ? b.mCurrentY : b.mToY;
    }

    private boolean useCurrentValueAsTarget(Animatable a) {
        return a.mAnimationStartTime == NO_ANIMATION ||
                a.mAnimationKind == ANIM_KIND_SNAPBACK ||
                a.mAnimationKind == ANIM_KIND_FLING;
    }

    ////////////////////////////////////////////////////////////////////////////
    //  Animatable: an thing which can do animation.
    ////////////////////////////////////////////////////////////////////////////
    private abstract static class Animatable {
        public long mAnimationStartTime;
        public int mAnimationKind;
        public int mAnimationDuration;

        // This should be overidden in subclass to change the animation values
        // give the progress value in [0, 1].
        protected abstract boolean interpolate(float progress);
        public abstract boolean startSnapback();

        // Returns true if the animation values changes, so things need to be
        // redrawn.
        public boolean advanceAnimation() {
            if (mAnimationStartTime == NO_ANIMATION) {
                return false;
            }
            if (mAnimationStartTime == LAST_ANIMATION) {
                mAnimationStartTime = NO_ANIMATION;
                return startSnapback();
            }

            float progress;
            if (mAnimationDuration == 0) {
                progress = 1;
            } else {
                long now = AnimationTime.get();
                progress =
                    (float) (now - mAnimationStartTime) / mAnimationDuration;
            }

            if (progress >= 1) {
                progress = 1;
            } else {
                progress = applyInterpolationCurve(mAnimationKind, progress);
            }

            boolean done = interpolate(progress);

            if (done) {
                mAnimationStartTime = LAST_ANIMATION;
            }

            return true;
        }

        private static float applyInterpolationCurve(int kind, float progress) {
            float f = 1 - progress;
            switch (kind) {
                case ANIM_KIND_SCROLL:
                case ANIM_KIND_FLING:
                case ANIM_KIND_CAPTURE:
                    progress = 1 - f;  // linear
                    break;
                case ANIM_KIND_SCALE:
                    progress = 1 - f * f;  // quadratic
                    break;
                case ANIM_KIND_SNAPBACK:
                case ANIM_KIND_ZOOM:
                case ANIM_KIND_SLIDE:
                case ANIM_KIND_OPENING:
                    progress = 1 - f * f * f * f * f; // x^5
                    break;
            }
            return progress;
        }
    }

    ////////////////////////////////////////////////////////////////////////////
    //  Platform: captures the global X/Y movement.
    ////////////////////////////////////////////////////////////////////////////
    private class Platform extends Animatable {
        public int mCurrentX, mFromX, mToX, mDefaultX;
        public int mCurrentY, mFromY, mToY, mDefaultY;
        public int mFlingOffset;

        @Override
        public boolean startSnapback() {
            if (mAnimationStartTime != NO_ANIMATION) return false;
            if (mAnimationKind == ANIM_KIND_SCROLL
                    && mListener.isHolding()) return false;

            Box b = mBoxes.get(0);
            float scaleMin = mExtraScalingRange ?
                b.mScaleMin * SCALE_MIN_EXTRA : b.mScaleMin;
            float scaleMax = mExtraScalingRange ?
                b.mScaleMax * SCALE_MAX_EXTRA : b.mScaleMax;
            float scale = Utils.clamp(b.mCurrentScale, scaleMin, scaleMax);
            int x = mCurrentX;
            int y = mDefaultY;
            if (mFilmMode) {
                int defaultX = mDefaultX;
                if (!mHasNext) x = Math.max(x, defaultX);
                if (!mHasPrev) x = Math.min(x, defaultX);
            } else {
                calculateStableBound(scale, HORIZONTAL_SLACK);
                x = Utils.clamp(x, mBoundLeft, mBoundRight);
            }
            if (mCurrentX != x || mCurrentY != y) {
                return doAnimation(x, y, ANIM_KIND_SNAPBACK);
            }
            return false;
        }

        // The updateDefaultXY() should be called whenever these variables
        // changes: (1) mConstrained (2) mConstrainedFrame (3) mViewW/H (4)
        // mFilmMode
        public void updateDefaultXY() {
            // We don't check mFilmMode and return 0 for mDefaultX. Because
            // otherwise if we decide to leave film mode because we are
            // centered, we will immediately back into film mode because we find
            // we are not centered.
            if (mConstrained && !mConstrainedFrame.isEmpty()) {
                mDefaultX = mConstrainedFrame.centerX() - mViewW / 2;
                mDefaultY = mFilmMode ? 0 :
                        mConstrainedFrame.centerY() - mViewH / 2;
            } else {
                mDefaultX = 0;
                mDefaultY = 0;
            }
        }

        // Starts an animation for the platform.
        private boolean doAnimation(int targetX, int targetY, int kind) {
            if (mCurrentX == targetX && mCurrentY == targetY) return false;
            mAnimationKind = kind;
            mFromX = mCurrentX;
            mFromY = mCurrentY;
            mToX = targetX;
            mToY = targetY;
            mAnimationStartTime = AnimationTime.startTime();
            mAnimationDuration = ANIM_TIME[kind];
            mFlingOffset = 0;
            advanceAnimation();
            return true;
        }

        @Override
        protected boolean interpolate(float progress) {
            if (mAnimationKind == ANIM_KIND_FLING) {
                return mFilmMode
                        ? interpolateFlingFilm(progress)
                        : interpolateFlingPage(progress);
            } else {
                return interpolateLinear(progress);
            }
        }

        private boolean interpolateFlingFilm(float progress) {
            mFilmScroller.computeScrollOffset();
            mCurrentX = mFilmScroller.getCurrX() + mFlingOffset;

            int dir = EdgeView.INVALID_DIRECTION;
            if (mCurrentX < mDefaultX) {
                if (!mHasNext) {
                    dir = EdgeView.RIGHT;
                }
            } else if (mCurrentX > mDefaultX) {
                if (!mHasPrev) {
                    dir = EdgeView.LEFT;
                }
            }
            if (dir != EdgeView.INVALID_DIRECTION) {
                int v = (int) (mFilmScroller.getCurrVelocity() + 0.5f);
                mListener.onAbsorb(v, dir);
                mFilmScroller.forceFinished(true);
                mCurrentX = mDefaultX;
            }
            return mFilmScroller.isFinished();
        }

        private boolean interpolateFlingPage(float progress) {
            mPageScroller.computeScrollOffset(progress);
            Box b = mBoxes.get(0);
            calculateStableBound(b.mCurrentScale);

            int oldX = mCurrentX;
            mCurrentX = mPageScroller.getCurrX();

            // Check if we hit the edges; show edge effects if we do.
            if (oldX > mBoundLeft && mCurrentX == mBoundLeft) {
                int v = (int) (-mPageScroller.getCurrVelocityX() + 0.5f);
                mListener.onAbsorb(v, EdgeView.RIGHT);
            } else if (oldX < mBoundRight && mCurrentX == mBoundRight) {
                int v = (int) (mPageScroller.getCurrVelocityX() + 0.5f);
                mListener.onAbsorb(v, EdgeView.LEFT);
            }

            return progress >= 1;
        }

        private boolean interpolateLinear(float progress) {
            // Other animations
            if (progress >= 1) {
                mCurrentX = mToX;
                mCurrentY = mToY;
                return true;
            } else {
                if (mAnimationKind == ANIM_KIND_CAPTURE) {
                    progress = CaptureAnimation.calculateSlide(progress);
                }
                mCurrentX = (int) (mFromX + progress * (mToX - mFromX));
                mCurrentY = (int) (mFromY + progress * (mToY - mFromY));
                if (mAnimationKind == ANIM_KIND_CAPTURE) {
                    return false;
                } else {
                    return (mCurrentX == mToX && mCurrentY == mToY);
                }
            }
        }
    }

    ////////////////////////////////////////////////////////////////////////////
    //  Box: represents a rectangular area which shows a picture.
    ////////////////////////////////////////////////////////////////////////////
    private class Box extends Animatable {
        // Size of the bitmap
        public int mImageW, mImageH;

        // This is true if we assume the image size is the same as view size
        // until we know the actual size of image. This is also used to
        // determine if there is an image ready to show.
        public boolean mUseViewSize;

        // The minimum and maximum scale we allow for this box.
        public float mScaleMin, mScaleMax;

        // The X/Y value indicates where the center of the box is on the view
        // coordinate. We always keep the mCurrent{X,Y,Scale} sync with the
        // actual values used currently. Note that the X values are implicitly
        // defined by Platform and Gaps.
        public int mCurrentY, mFromY, mToY;
        public float mCurrentScale, mFromScale, mToScale;

        // The absolute X coordinate of the center of the box. This is only used
        // during moveBox().
        public int mAbsoluteX;

        @Override
        public boolean startSnapback() {
            if (mAnimationStartTime != NO_ANIMATION) return false;
            if (mAnimationKind == ANIM_KIND_SCROLL
                    && mListener.isHolding()) return false;
            if (mInScale && this == mBoxes.get(0)) return false;

            int y;
            float scale;

            if (this == mBoxes.get(0)) {
                float scaleMin = mExtraScalingRange ?
                    mScaleMin * SCALE_MIN_EXTRA : mScaleMin;
                float scaleMax = mExtraScalingRange ?
                    mScaleMax * SCALE_MAX_EXTRA : mScaleMax;
                scale = Utils.clamp(mCurrentScale, scaleMin, scaleMax);
                if (mFilmMode) {
                    y = 0;
                } else {
                    calculateStableBound(scale, HORIZONTAL_SLACK);
                    y = Utils.clamp(mCurrentY, mBoundTop, mBoundBottom);
                }
            } else {
                y = 0;
                scale = mScaleMin;
            }

            if (mCurrentY != y || mCurrentScale != scale) {
                return doAnimation(y, scale, ANIM_KIND_SNAPBACK);
            }
            return false;
        }

        private boolean doAnimation(int targetY, float targetScale, int kind) {
            targetScale = Utils.clamp(targetScale,
                    SCALE_MIN_EXTRA * mScaleMin,
                    SCALE_MAX_EXTRA * mScaleMax);

            // If the scaled height is smaller than the view height, force it to be
            // in the center.  (We do this for height only, not width, because the
            // user may want to scroll to the previous/next image.)
            if (!mInScale && viewTallerThanScaledImage(targetScale)) {
                targetY = 0;
            }

            if (mCurrentY == targetY && mCurrentScale == targetScale
                    && kind != ANIM_KIND_CAPTURE) {
                return false;
            }

            // Now starts an animation for the box.
            mAnimationKind = kind;
            mFromY = mCurrentY;
            mFromScale = mCurrentScale;
            mToY = targetY;
            mToScale = targetScale;
            mAnimationStartTime = AnimationTime.startTime();
            mAnimationDuration = ANIM_TIME[kind];
            advanceAnimation();
            return true;
        }

        @Override
        protected boolean interpolate(float progress) {
            if (mAnimationKind == ANIM_KIND_FLING) {
                // Currently a Box can only be flung in page mode.
                return interpolateFlingPage(progress);
            } else {
                return interpolateLinear(progress);
            }
        }

        private boolean interpolateFlingPage(float progress) {
            mPageScroller.computeScrollOffset(progress);
            calculateStableBound(mCurrentScale);

            int oldY = mCurrentY;
            mCurrentY = mPageScroller.getCurrY();

            // Check if we hit the edges; show edge effects if we do.
            if (oldY > mBoundTop && mCurrentY == mBoundTop) {
                int v = (int) (-mPageScroller.getCurrVelocityY() + 0.5f);
                mListener.onAbsorb(v, EdgeView.BOTTOM);
            } else if (oldY < mBoundBottom && mCurrentY == mBoundBottom) {
                int v = (int) (mPageScroller.getCurrVelocityY() + 0.5f);
                mListener.onAbsorb(v, EdgeView.TOP);
            }

            return progress >= 1;
        }

        private boolean interpolateLinear(float progress) {
            if (progress >= 1) {
                mCurrentY = mToY;
                mCurrentScale = mToScale;
                return true;
            } else {
                mCurrentY = (int) (mFromY + progress * (mToY - mFromY));
                mCurrentScale = mFromScale + progress * (mToScale - mFromScale);
                if (mAnimationKind == ANIM_KIND_CAPTURE) {
                    float f = CaptureAnimation.calculateScale(progress);
                    mCurrentScale *= f;
                    return false;
                } else {
                    return (mCurrentY == mToY && mCurrentScale == mToScale);
                }
            }
        }
    }

    ////////////////////////////////////////////////////////////////////////////
    //  Gap: represents a rectangular area which is between two boxes.
    ////////////////////////////////////////////////////////////////////////////
    private class Gap extends Animatable {
        // The default gap size between two boxes. The value may vary for
        // different image size of the boxes and for different modes (page or
        // film).
        public int mDefaultSize;

        // The gap size between the two boxes.
        public int mCurrentGap, mFromGap, mToGap;

        @Override
        public boolean startSnapback() {
            if (mAnimationStartTime != NO_ANIMATION) return false;
            return doAnimation(mDefaultSize, ANIM_KIND_SNAPBACK);
        }

        // Starts an animation for a gap.
        public boolean doAnimation(int targetSize, int kind) {
            if (mCurrentGap == targetSize && kind != ANIM_KIND_CAPTURE) {
                return false;
            }
            mAnimationKind = kind;
            mFromGap = mCurrentGap;
            mToGap = targetSize;
            mAnimationStartTime = AnimationTime.startTime();
            mAnimationDuration = ANIM_TIME[mAnimationKind];
            advanceAnimation();
            return true;
        }

        @Override
        protected boolean interpolate(float progress) {
            if (progress >= 1) {
                mCurrentGap = mToGap;
                return true;
            } else {
                mCurrentGap = (int) (mFromGap + progress * (mToGap - mFromGap));
                if (mAnimationKind == ANIM_KIND_CAPTURE) {
                    float f = CaptureAnimation.calculateScale(progress);
                    mCurrentGap = (int) (mCurrentGap * f);
                    return false;
                } else {
                    return (mCurrentGap == mToGap);
                }
            }
        }
    }
}
