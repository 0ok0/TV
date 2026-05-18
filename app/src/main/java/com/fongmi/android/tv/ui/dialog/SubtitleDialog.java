package com.fongmi.android.tv.ui.dialog;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.media3.ui.SubtitleView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.databinding.DialogSubtitleBinding;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;
import com.github.bassaer.library.MDColor;

public final class SubtitleDialog extends BaseBottomSheetDialog {

    private static final float DEFAULT_TEXT_SIZE = 0.0533f;
    private static final float DEFAULT_BOTTOM_PADDING = 0.08f;
    private static final float TEXT_SIZE_STEP = 0.006f;
    private static final float POSITION_STEP = 0.03f;

    private DialogSubtitleBinding binding;
    private SubtitleView subtitleView;

    public static SubtitleDialog create() {
        return new SubtitleDialog();
    }

    public SubtitleDialog view(SubtitleView subtitleView) {
        this.subtitleView = subtitleView;
        return this;
    }

    public void show(FragmentActivity activity) {
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) if (f instanceof SubtitleDialog) return;
        show(activity.getSupportFragmentManager(), null);
    }

    private boolean isFull() {
        return Util.isFullscreen(getActivity());
    }

    @Override
    protected boolean transparent() {
        return isFull();
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogSubtitleBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        int count = binding.getRoot().getChildCount();
        if (isFull()) for (int i = 0; i < count; i++) ((ImageView) binding.getRoot().getChildAt(i)).getDrawable().setTint(MDColor.WHITE);
    }

    @Override
    protected void initEvent() {
        binding.large.setOnClickListener(this::onLarge);
        binding.small.setOnClickListener(this::onSmall);
        binding.up.setOnClickListener(this::onUp);
        binding.down.setOnClickListener(this::onDown);
        binding.reset.setOnClickListener(this::onReset);
    }

    private void onLarge(View view) {
        setTextSize(clamp(textSize() + TEXT_SIZE_STEP, 0.035f, 0.12f));
    }

    private void onSmall(View view) {
        setTextSize(clamp(textSize() - TEXT_SIZE_STEP, 0.035f, 0.12f));
    }

    private void onUp(View view) {
        setPosition(clamp(position() + POSITION_STEP, 0.0f, 0.45f));
    }

    private void onDown(View view) {
        setPosition(clamp(position() - POSITION_STEP, 0.0f, 0.45f));
    }

    private void onReset(View view) {
        PlayerSetting.putSubtitleTextSize(0.0f);
        PlayerSetting.putSubtitlePosition(0.0f);
        subtitleView.setBottomPaddingFraction(DEFAULT_BOTTOM_PADDING);
        subtitleView.setUserDefaultTextSize();
    }

    private void setTextSize(float value) {
        PlayerSetting.putSubtitleTextSize(value);
        subtitleView.setFractionalTextSize(value);
    }

    private void setPosition(float value) {
        PlayerSetting.putSubtitlePosition(value);
        subtitleView.setBottomPaddingFraction(value);
    }

    private float textSize() {
        float value = PlayerSetting.getSubtitleTextSize();
        return value == 0 ? DEFAULT_TEXT_SIZE : value;
    }

    private float position() {
        float value = PlayerSetting.getSubtitlePosition();
        return value == 0 ? DEFAULT_BOTTOM_PADDING : value;
    }

    private float clamp(float value, float min, float max) {
        return Math.min(Math.max(value, min), max);
    }

    @Override
    public void onResume() {
        super.onResume();
        getDialog().getWindow().setLayout(ResUtil.dp2px(isFull() ? 232 : 216), -1);
    }
}
