package com.topjohnwu.magisk.adapters;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;
import androidx.collection.ArraySet;
import androidx.recyclerview.widget.RecyclerView;

import com.topjohnwu.magisk.App;
import com.topjohnwu.magisk.Config;
import com.topjohnwu.magisk.R;
import com.topjohnwu.magisk.utils.Topic;
import com.topjohnwu.magisk.utils.Utils;
import com.topjohnwu.superuser.Shell;
import com.topjohnwu.superuser.internal.UiThreadHandler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import butterknife.BindView;
import java9.util.Comparators;
import java9.util.stream.Collectors;
import java9.util.stream.Stream;
import java9.util.stream.StreamSupport;

public class ApplicationAdapter extends RecyclerView.Adapter<ApplicationAdapter.ViewHolder> {

    /* A list of apps that should not be shown as hide-able */
    private static final List<String> HIDE_BLACKLIST =  Arrays.asList(
            App.self.getPackageName(),
            "android",
            "com.android.chrome",
            "com.google.android.webview"
    );
    private static final String SAFETYNET_PROCESS = "com.google.android.gms.unstable";
    private static final String GMS_PACKAGE = "com.google.android.gms";

    private List<HideAppInfo> fullList, showList;
    private List<HideTarget> hideList;
    private PackageManager pm;
    private boolean showSystem;

    public ApplicationAdapter(Context context) {
        showList = Collections.emptyList();
        hideList = Collections.emptyList();
        fullList = new ArrayList<>();
        pm = context.getPackageManager();
        showSystem = Config.get(Config.Key.SHOW_SYSTEM_APP);
        AsyncTask.SERIAL_EXECUTOR.execute(this::loadApps);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.list_item_app, parent, false);
        return new ViewHolder(v);
    }

    private void addProcesses(Set<String> set, ComponentInfo[] infos) {
        if (infos != null)
            for (ComponentInfo info : infos)
                set.add(info.processName);
    }

    private PackageInfo getPackageInfo(String pkg) {
        // Try super hard to get as much info as possible
        try {
            return pm.getPackageInfo(pkg,
                    PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES |
                    PackageManager.GET_RECEIVERS | PackageManager.GET_PROVIDERS);
        } catch (Exception e1) {
            try {
                PackageInfo info = pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES);
                info.services = pm.getPackageInfo(pkg, PackageManager.GET_SERVICES).services;
                info.receivers = pm.getPackageInfo(pkg, PackageManager.GET_RECEIVERS).receivers;
                info.providers = pm.getPackageInfo(pkg, PackageManager.GET_PROVIDERS).providers;
                return info;
            } catch (Exception e2) {
                return null;
            }
        }
    }

    @WorkerThread
    private void loadApps() {
        List<ApplicationInfo> installed = pm.getInstalledApplications(0);

        hideList = StreamSupport.stream(Shell.su("magiskhide --ls").exec().getOut())
                .map(HideTarget::new).collect(Collectors.toList());

        fullList.clear();

        for (ApplicationInfo info : installed) {
            // Do not show black-listed and disabled apps
            if (!HIDE_BLACKLIST.contains(info.packageName) && info.enabled) {
                Set<String> set = new ArraySet<>();
                PackageInfo pkg = getPackageInfo(info.packageName);
                if (pkg != null) {
                    addProcesses(set, pkg.activities);
                    addProcesses(set, pkg.services);
                    addProcesses(set, pkg.receivers);
                    addProcesses(set, pkg.providers);
                } else {
                    set.add(info.packageName);
                }
                fullList.addAll(StreamSupport.stream(set)
                        .map(process -> new HideAppInfo(info, process))
                        .collect(Collectors.toList()));
            }
        }

        Collections.sort(fullList);
        Topic.publish(false, Topic.MAGISK_HIDE_DONE);
    }

    public void setShowSystem(boolean b) {
        showSystem = b;
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        HideAppInfo target = showList.get(position);

        holder.appIcon.setImageDrawable(target.info.loadIcon(pm));
        holder.appName.setText(target.name);
        holder.process.setText(target.process);
        if (!target.info.packageName.equals(target.process)) {
            holder.appPackage.setVisibility(View.VISIBLE);
            holder.appPackage.setText("(" + target.info.packageName + ")");
        } else {
            holder.appPackage.setVisibility(View.GONE);
        }

        holder.checkBox.setOnCheckedChangeListener(null);
        holder.checkBox.setChecked(target.hidden);
        if (target.process.equals(SAFETYNET_PROCESS)) {
            // Do not allow user to not hide SafetyNet
            holder.checkBox.setOnCheckedChangeListener((v, c) -> holder.checkBox.setChecked(true));
        } else {
            holder.checkBox.setOnCheckedChangeListener((v, isChecked) -> {
                String pair = Utils.fmt("%s %s", target.info.packageName, target.process);
                if (isChecked) {
                    Shell.su("magiskhide --add " + pair).submit();
                    target.hidden = true;
                } else {
                    Shell.su("magiskhide --rm " + pair).submit();
                    target.hidden = false;
                }
            });
        }
    }

    @Override
    public int getItemCount() {
        return showList.size();
    }

    // True if not system app and have launch intent, or user already hidden it
    private boolean systemFilter(HideAppInfo target) {
        return showSystem || target.hidden ||
                ((target.info.flags & ApplicationInfo.FLAG_SYSTEM) == 0 &&
                pm.getLaunchIntentForPackage(target.info.packageName) != null);
    }

    private boolean contains(String s, String filter) {
        return s.toLowerCase().contains(filter);
    }

    private boolean nameFilter(HideAppInfo target, String filter) {
        if (filter == null || filter.isEmpty())
            return true;
        filter = filter.toLowerCase();
        return contains(target.name, filter) ||
                contains(target.process, filter) ||
                contains(target.info.packageName, filter);
    }

    public void filter(String constraint) {
        AsyncTask.SERIAL_EXECUTOR.execute(() -> {
            Stream<HideAppInfo> s = StreamSupport.stream(fullList)
                    .filter(this::systemFilter)
                    .filter(t -> nameFilter(t, constraint));
            UiThreadHandler.run(() -> {
                showList = s.collect(Collectors.toList());
                notifyDataSetChanged();
            });
        });
    }

    public void refresh() {
        AsyncTask.SERIAL_EXECUTOR.execute(this::loadApps);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {

        @BindView(R.id.app_icon) ImageView appIcon;
        @BindView(R.id.app_name) TextView appName;
        @BindView(R.id.process) TextView process;
        @BindView(R.id.package_name) TextView appPackage;
        @BindView(R.id.checkbox) CheckBox checkBox;

        ViewHolder(View itemView) {
            super(itemView);
            new ApplicationAdapter$ViewHolder_ViewBinding(this, itemView);
        }
    }

    class HideAppInfo implements Comparable<HideAppInfo> {
        String process;
        String name;
        ApplicationInfo info;
        boolean hidden;

        HideAppInfo(ApplicationInfo info, String process) {
            this.process = process;
            this.info = info;
            name = Utils.getAppLabel(info, pm);
            for (HideTarget tgt : hideList) {
                if (tgt.process.equals(process)) {
                    hidden = true;
                    break;
                }
            }
        }

        @Override
        public int compareTo(HideAppInfo o) {
            Comparator<HideAppInfo> c;
            c = Comparators.comparing((HideAppInfo t) -> t.hidden);
            c = Comparators.reversed(c);
            c = Comparators.thenComparing(c, t -> t.name, String::compareToIgnoreCase);
            c = Comparators.thenComparing(c, t -> t.info.packageName);
            c = Comparators.thenComparing(c, t -> t.process);
            return c.compare(this, o);
        }
    }

    class HideTarget {
        String pkg;
        String process;

        HideTarget(String line) {
            String[] split = line.split("\\|");
            pkg = split[0];
            if (split.length >= 2) {
                process = split[1];
            } else {
                // Backwards compatibility
                process = pkg.equals(GMS_PACKAGE) ? SAFETYNET_PROCESS : pkg;
            }
        }
    }
}
