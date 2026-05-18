package androidx.media3.ui.danmaku;

public final class DanmakuConfig {

    public static final int STYLE_NONE = 0;
    public static final int STYLE_SHADOW = 1;
    public static final int STYLE_STROKE = 2;
    public static final int STYLE_PROJECTION = 3;
    public static final int COLOR_MODE_DEFAULT = 0;
    public static final int COLOR_MODE_COLORFUL = 1;
    public static final int COLOR_MODE_GRADIENT = 2;

    public static final DanmakuConfig DEFAULT = new Builder().build();

    public final float textScale;
    public final float transparency;
    public final boolean textBold;
    public final int styleMode;
    public final float shadowTransparency;
    public final float strokeWidthMultiplier;
    public final float projectionOffsetXMultiplier;
    public final float projectionOffsetYMultiplier;
    public final float projectionTransparency;
    public final int colorMode;
    public final long durationMs;
    public final long fixedDurationMs;
    public final long timeOffsetMs;
    public final int maxOnScreen;
    public final float scrollAreaRatio;
    public final float scrollGapRatio;
    public final float lineSpacing;
    public final int maxScrollLines;
    public final int maxTopLines;
    public final int maxBottomLines;
    public final boolean showScroll;
    public final boolean showTop;
    public final boolean showBottom;
    public final boolean showReverse;
    public final boolean showPositioned;
    public final boolean showSubtitle;
    public final boolean showSpecial;

    private DanmakuConfig(Builder builder) {
        textScale = builder.textScale;
        transparency = builder.transparency;
        textBold = builder.textBold;
        styleMode = builder.styleMode;
        shadowTransparency = builder.shadowTransparency;
        strokeWidthMultiplier = builder.strokeWidthMultiplier;
        projectionOffsetXMultiplier = builder.projectionOffsetXMultiplier;
        projectionOffsetYMultiplier = builder.projectionOffsetYMultiplier;
        projectionTransparency = builder.projectionTransparency;
        colorMode = builder.colorMode;
        durationMs = builder.durationMs;
        fixedDurationMs = builder.fixedDurationMs;
        timeOffsetMs = builder.timeOffsetMs;
        maxOnScreen = builder.maxOnScreen;
        scrollAreaRatio = builder.scrollAreaRatio;
        scrollGapRatio = builder.scrollGapRatio;
        lineSpacing = builder.lineSpacing;
        maxScrollLines = builder.maxScrollLines;
        maxTopLines = builder.maxTopLines;
        maxBottomLines = builder.maxBottomLines;
        showScroll = builder.showScroll;
        showTop = builder.showTop;
        showBottom = builder.showBottom;
        showReverse = builder.showReverse;
        showPositioned = builder.showPositioned;
        showSubtitle = builder.showSubtitle;
        showSpecial = builder.showSpecial;
    }

    public static final class Builder {

        private float textScale = 1f;
        private float transparency = 0f;
        private boolean textBold = false;
        private int styleMode = STYLE_STROKE;
        private float shadowTransparency = 0.1f;
        private float strokeWidthMultiplier = 0.12f;
        private float projectionOffsetXMultiplier = 0.08f;
        private float projectionOffsetYMultiplier = 0.08f;
        private float projectionTransparency = 0.2f;
        private int colorMode = COLOR_MODE_DEFAULT;
        private long durationMs = 8000L;
        private long fixedDurationMs = 5000L;
        private long timeOffsetMs = 0L;
        private int maxOnScreen = 150;
        private float scrollAreaRatio = 0.5f;
        private float scrollGapRatio = 0f;
        private float lineSpacing = 1.4f;
        private int maxScrollLines = 0;
        private int maxTopLines = 0;
        private int maxBottomLines = 0;
        private boolean showScroll = true;
        private boolean showTop = true;
        private boolean showBottom = true;
        private boolean showReverse = true;
        private boolean showPositioned = true;
        private boolean showSubtitle = true;
        private boolean showSpecial = true;

        public Builder setTextScale(float value) {
            textScale = value;
            return this;
        }

        public Builder setTransparency(float value) {
            transparency = value;
            return this;
        }

        public Builder setTextBold(boolean value) {
            textBold = value;
            return this;
        }

        public Builder setStyleMode(int value) {
            styleMode = value;
            return this;
        }

        public Builder setShadowTransparency(float value) {
            shadowTransparency = value;
            return this;
        }

        public Builder setStrokeWidthMultiplier(float value) {
            strokeWidthMultiplier = value;
            return this;
        }

        public Builder setProjectionOffsetXMultiplier(float value) {
            projectionOffsetXMultiplier = value;
            return this;
        }

        public Builder setProjectionOffsetYMultiplier(float value) {
            projectionOffsetYMultiplier = value;
            return this;
        }

        public Builder setProjectionTransparency(float value) {
            projectionTransparency = value;
            return this;
        }

        public Builder setColorMode(int value) {
            colorMode = value;
            return this;
        }

        public Builder setDurationMs(long value) {
            durationMs = value;
            return this;
        }

        public Builder setFixedDurationMs(long value) {
            fixedDurationMs = value;
            return this;
        }

        public Builder setTimeOffsetMs(long value) {
            timeOffsetMs = value;
            return this;
        }

        public Builder setMaxOnScreen(int value) {
            maxOnScreen = value;
            return this;
        }

        public Builder setScrollAreaRatio(float value) {
            scrollAreaRatio = value;
            return this;
        }

        public Builder setScrollGapRatio(float value) {
            scrollGapRatio = value;
            return this;
        }

        public Builder setLineSpacing(float value) {
            lineSpacing = value;
            return this;
        }

        public Builder setMaxScrollLines(int value) {
            maxScrollLines = value;
            return this;
        }

        public Builder setMaxTopLines(int value) {
            maxTopLines = value;
            return this;
        }

        public Builder setMaxBottomLines(int value) {
            maxBottomLines = value;
            return this;
        }

        public Builder setShowScroll(boolean value) {
            showScroll = value;
            return this;
        }

        public Builder setShowTop(boolean value) {
            showTop = value;
            return this;
        }

        public Builder setShowBottom(boolean value) {
            showBottom = value;
            return this;
        }

        public Builder setShowReverse(boolean value) {
            showReverse = value;
            return this;
        }

        public Builder setShowPositioned(boolean value) {
            showPositioned = value;
            return this;
        }

        public Builder setShowSubtitle(boolean value) {
            showSubtitle = value;
            return this;
        }

        public Builder setShowSpecial(boolean value) {
            showSpecial = value;
            return this;
        }

        public DanmakuConfig build() {
            return new DanmakuConfig(this);
        }
    }
}
