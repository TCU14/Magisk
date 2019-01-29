package com.topjohnwu.magisk.fragments;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.topjohnwu.core.Config;
import com.topjohnwu.core.tasks.CheckUpdates;
import com.topjohnwu.core.tasks.SafetyNet;
import com.topjohnwu.core.utils.ISafetyNetHelper;
import com.topjohnwu.core.utils.Topic;
import com.topjohnwu.magisk.BuildConfig;
import com.topjohnwu.magisk.MainActivity;
import com.topjohnwu.magisk.R;
import com.topjohnwu.magisk.components.BaseActivity;
import com.topjohnwu.magisk.components.BaseFragment;
import com.topjohnwu.magisk.components.CustomAlertDialog;
import com.topjohnwu.magisk.components.EnvFixDialog;
import com.topjohnwu.magisk.components.ExpandableViewHolder;
import com.topjohnwu.magisk.components.MagiskInstallDialog;
import com.topjohnwu.magisk.components.ManagerInstallDialog;
import com.topjohnwu.magisk.components.UninstallDialog;
import com.topjohnwu.magisk.components.UpdateCardHolder;
import com.topjohnwu.net.Networking;
import com.topjohnwu.superuser.Shell;
import com.topjohnwu.superuser.ShellUtils;

import java.util.Locale;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.cardview.widget.CardView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.transition.ChangeBounds;
import androidx.transition.Fade;
import androidx.transition.Transition;
import androidx.transition.TransitionManager;
import androidx.transition.TransitionSet;
import butterknife.BindColor;
import butterknife.BindView;
import butterknife.OnClick;

public class MagiskFragment extends BaseFragment
        implements SwipeRefreshLayout.OnRefreshListener, Topic.Subscriber {

    private static boolean shownDialog = false;

    @BindView(R.id.swipeRefreshLayout) SwipeRefreshLayout mSwipeRefreshLayout;
    @BindView(R.id.linearLayout) LinearLayout root;

    @BindView(R.id.core_only_notice) CardView coreOnlyNotice;

    @BindView(R.id.safetyNet_card) CardView safetyNetCard;
    @BindView(R.id.safetyNet_refresh) ImageView safetyNetRefreshIcon;
    @BindView(R.id.safetyNet_status) TextView safetyNetStatusText;
    @BindView(R.id.safetyNet_check_progress) ProgressBar safetyNetProgress;
    @BindView(R.id.expand_layout) LinearLayout expandLayout;
    @BindView(R.id.cts_status_icon) ImageView ctsStatusIcon;
    @BindView(R.id.cts_status) TextView ctsStatusText;
    @BindView(R.id.basic_status_icon) ImageView basicStatusIcon;
    @BindView(R.id.basic_status) TextView basicStatusText;

    @BindView(R.id.install_option_card) CardView installOptionCard;
    @BindView(R.id.keep_force_enc) CheckBox keepEncChkbox;
    @BindView(R.id.keep_verity) CheckBox keepVerityChkbox;
    @BindView(R.id.uninstall_button) CardView uninstallButton;

    @BindColor(R.color.red500) int colorBad;
    @BindColor(R.color.green500) int colorOK;
    @BindColor(R.color.yellow500) int colorWarn;
    @BindColor(R.color.green500) int colorNeutral;
    @BindColor(R.color.blue500) int colorInfo;

    private UpdateCardHolder magisk;
    private UpdateCardHolder manager;
    private ExpandableViewHolder snExpandableHolder;
    private Transition transition;

    @OnClick(R.id.safetyNet_title)
    void safetyNet() {
        Runnable task = () -> {
            safetyNetProgress.setVisibility(View.VISIBLE);
            safetyNetRefreshIcon.setVisibility(View.GONE);
            safetyNetStatusText.setText(R.string.checking_safetyNet_status);
            SafetyNet.check(requireActivity());
            snExpandableHolder.collapse();
        };
        if (!SafetyNet.EXT_APK.exists()) {
            // Show dialog
            new CustomAlertDialog(requireActivity())
                    .setTitle(R.string.proprietary_title)
                    .setMessage(R.string.proprietary_notice)
                    .setCancelable(true)
                    .setPositiveButton(R.string.yes, (d, i) -> task.run())
                    .setNegativeButton(R.string.no_thanks, null)
                    .show();
        } else {
            task.run();
        }
    }

    private void magiskInstall(View v) {
        // Show Manager update first
        if (Config.remoteManagerVersionCode > BuildConfig.VERSION_CODE) {
            new ManagerInstallDialog(requireActivity()).show();
            return;
        }
        new MagiskInstallDialog((BaseActivity) requireActivity()).show();
    }

    private void managerInstall(View v) {
        new ManagerInstallDialog(requireActivity()).show();
    }

    @OnClick(R.id.uninstall_button)
    void uninstall() {
        new UninstallDialog(requireActivity()).show();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_magisk, container, false);
        unbinder = new MagiskFragment_ViewBinding(this, v);
        requireActivity().setTitle(R.string.magisk);

        snExpandableHolder = new ExpandableViewHolder(expandLayout);

        magisk = new UpdateCardHolder(inflater, root);
        manager = new UpdateCardHolder(inflater, root);
        magisk.install.setOnClickListener(this::magiskInstall);
        manager.install.setOnClickListener(this::managerInstall);
        root.addView(magisk.itemView, 0);
        root.addView(manager.itemView, 1);

        keepVerityChkbox.setChecked(Config.keepVerity);
        keepVerityChkbox.setOnCheckedChangeListener((view, checked) -> Config.keepVerity = checked);
        keepEncChkbox.setChecked(Config.keepEnc);
        keepEncChkbox.setOnCheckedChangeListener((view, checked) -> Config.keepEnc = checked);

        mSwipeRefreshLayout.setOnRefreshListener(this);

        coreOnlyNotice.setVisibility(Config.get(Config.Key.COREONLY) ? View.VISIBLE : View.GONE);
        safetyNetCard.setVisibility(hasGms() && Networking.checkNetworkStatus(app) ?
                View.VISIBLE : View.GONE);

        transition = new TransitionSet()
                .setOrdering(TransitionSet.ORDERING_TOGETHER)
                .addTransition(new Fade(Fade.OUT))
                .addTransition(new ChangeBounds())
                .addTransition(new Fade(Fade.IN));

        updateUI();
        return v;
    }

    @Override
    public void onRefresh() {
        safetyNetStatusText.setText(R.string.safetyNet_check_text);
        snExpandableHolder.setExpanded(false);

        mSwipeRefreshLayout.setRefreshing(false);
        TransitionManager.beginDelayedTransition(root, transition);
        magisk.reset();
        manager.reset();

        Config.loadMagiskInfo();
        updateUI();

        Topic.reset(getSubscribedTopics());
        Config.remoteMagiskVersionString = null;
        Config.remoteMagiskVersionCode = -1;

        shownDialog = false;

        // Trigger state check
        if (Networking.checkNetworkStatus(app)) {
            CheckUpdates.check();
        }
    }

    @Override
    public int[] getSubscribedTopics() {
        return new int[] {Topic.SNET_CHECK_DONE, Topic.UPDATE_CHECK_DONE};
    }

    @Override
    public void onPublish(int topic, Object[] result) {
        switch (topic) {
            case Topic.SNET_CHECK_DONE:
                updateSafetyNetUI((int) result[0]);
                break;
            case Topic.UPDATE_CHECK_DONE:
                updateCheckUI();
                break;
        }
    }

    private boolean hasGms() {
        PackageManager pm = app.getPackageManager();
        PackageInfo info;
        try {
            info = pm.getPackageInfo("com.google.android.gms", 0);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
        return info.applicationInfo.enabled;
    }

    private void updateUI() {
        ((MainActivity) requireActivity()).checkHideSection();
        int image, color;
        String status;
        if (Config.magiskVersionCode < 0) {
            color = colorBad;
            image = R.drawable.ic_cancel;
            status = getString(R.string.magisk_version_error);
            magisk.status.setText(status);
            magisk.currentVersion.setVisibility(View.GONE);
        } else {
            color = colorOK;
            image = R.drawable.ic_check_circle;
            status = getString(R.string.magisk);
            magisk.currentVersion.setText(getString(R.string.current_installed,
                    String.format(Locale.US, "v%s (%d)",
                            Config.magiskVersionString, Config.magiskVersionCode)));
        }
        magisk.statusIcon.setColorFilter(color);
        magisk.statusIcon.setImageResource(image);

        manager.statusIcon.setColorFilter(colorOK);
        manager.statusIcon.setImageResource(R.drawable.ic_check_circle);
        manager.currentVersion.setText(getString(R.string.current_installed,
                String.format(Locale.US, "v%s (%d)",
                        BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)));

        if (!Networking.checkNetworkStatus(app)) {
            // No network, updateCheckUI will not be triggered
            magisk.status.setText(status);
            manager.status.setText(R.string.app_name);
            magisk.setValid(false);
            manager.setValid(false);
        }
    }

    private void updateCheckUI() {
        int image, color;
        String status;

        if (Config.remoteMagiskVersionCode < 0) {
            color = colorNeutral;
            image = R.drawable.ic_help;
            status = getString(R.string.invalid_update_channel);
        } else {
            magisk.latestVersion.setText(getString(R.string.latest_version,
                    String.format(Locale.US, "v%s (%d)",
                            Config.remoteMagiskVersionString, Config.remoteMagiskVersionCode)));
            if (Config.remoteMagiskVersionCode > Config.magiskVersionCode) {
                color = colorInfo;
                image = R.drawable.ic_update;
                status = getString(R.string.magisk_update_title);
                magisk.install.setText(R.string.update);
            } else {
                color = colorOK;
                image = R.drawable.ic_check_circle;
                status = getString(R.string.magisk_up_to_date);
                magisk.install.setText(R.string.install);
            }
        }
        if (Config.magiskVersionCode > 0) {
            // Only override status if Magisk is installed
            magisk.statusIcon.setImageResource(image);
            magisk.statusIcon.setColorFilter(color);
            magisk.status.setText(status);
        }

        if (Config.remoteManagerVersionCode < 0) {
            color = colorNeutral;
            image = R.drawable.ic_help;
            status = getString(R.string.invalid_update_channel);
        } else {
            manager.latestVersion.setText(getString(R.string.latest_version,
                    String.format(Locale.US, "v%s (%d)",
                            Config.remoteManagerVersionString, Config.remoteManagerVersionCode)));
            if (Config.remoteManagerVersionCode > BuildConfig.VERSION_CODE) {
                color = colorInfo;
                image = R.drawable.ic_update;
                status = getString(R.string.manager_update_title);
                manager.install.setText(R.string.update);
            } else {
                color = colorOK;
                image = R.drawable.ic_check_circle;
                status = getString(R.string.manager_up_to_date);
                manager.install.setText(R.string.install);
            }
        }
        manager.statusIcon.setImageResource(image);
        manager.statusIcon.setColorFilter(color);
        manager.status.setText(status);

        magisk.setValid(Config.remoteMagiskVersionCode > 0);
        manager.setValid(Config.remoteManagerVersionCode > 0);

        TransitionManager.beginDelayedTransition(root, transition);

        if (Config.remoteMagiskVersionCode < 0) {
            // Hide install related components
            installOptionCard.setVisibility(View.GONE);
            uninstallButton.setVisibility(View.GONE);
        } else {
            // Show install related components
            installOptionCard.setVisibility(View.VISIBLE);
            uninstallButton.setVisibility(Shell.rootAccess() ? View.VISIBLE : View.GONE);
        }

        if (!shownDialog && !ShellUtils.fastCmdResult("env_check")) {
            shownDialog = true;
            new EnvFixDialog(requireActivity()).show();
        }
    }

    private void updateSafetyNetUI(int response) {
        safetyNetProgress.setVisibility(View.GONE);
        safetyNetRefreshIcon.setVisibility(View.VISIBLE);
        if ((response & 0x0F) == 0) {
            safetyNetStatusText.setText(R.string.safetyNet_check_success);

            boolean b;
            b = (response & ISafetyNetHelper.CTS_PASS) != 0;
            ctsStatusText.setText("ctsProfile: " + b);
            ctsStatusIcon.setImageResource(b ? R.drawable.ic_check_circle : R.drawable.ic_cancel);
            ctsStatusIcon.setColorFilter(b ? colorOK : colorBad);

            b = (response & ISafetyNetHelper.BASIC_PASS) != 0;
            basicStatusText.setText("basicIntegrity: " + b);
            basicStatusIcon.setImageResource(b ? R.drawable.ic_check_circle : R.drawable.ic_cancel);
            basicStatusIcon.setColorFilter(b ? colorOK : colorBad);

            snExpandableHolder.expand();
        } else {
            @StringRes int resid;
            switch (response) {
                case ISafetyNetHelper.RESPONSE_ERR:
                    resid = R.string.safetyNet_res_invalid;
                    break;
                case ISafetyNetHelper.CONNECTION_FAIL:
                default:
                    resid = R.string.safetyNet_api_error;
                    break;
            }
            safetyNetStatusText.setText(resid);
        }
    }
}

