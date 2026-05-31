package be.zvz.sony.launchersearchenhancer.autofolder;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Fragment;
import android.app.FragmentTransaction;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

import be.zvz.sony.launchersearchenhancer.R;
import be.zvz.sony.launchersearchenhancer.autofolder.SemanticAppGrouper.AppCandidate;
import be.zvz.sony.launchersearchenhancer.autofolder.SemanticAppGrouper.Group;
import be.zvz.sony.launchersearchenhancer.reranker.SemanticReranker;
import io.github.libxposed.api.XposedModule;

public final class AutoFolderController {

    private static final int MENU_ID_AUTO_FOLDER = 0x5A1A1001;
    private static final int FALLBACK_ID_SORT = 0x5A1A1002;
    private static final int FALLBACK_ID_REARRANGE = 0x5A1A1003;
    private static final int FALLBACK_ID_EXIT = 0x5A1A1004;
    private static final int MENU_ID_OPENROUTER_AUTO_FOLDER = 0x5A1A1005;
    private static final String MODULE_PACKAGE = "be.zvz.sony.launchersearchenhancer";
    private static final String OPENROUTER_PREFS = "xlauncher_openrouter_auto_folder";
    private static final String PREF_OPENROUTER_API_KEY = "api_key";
    private static final String PREF_OPENROUTER_MODEL = "model";
    private static final String PREF_OPENROUTER_PROMPT = "prompt";
    private static final String DEFAULT_OPENROUTER_MODEL = "openrouter/auto";
    private static final String TAG = "AutoFolderController";

    private final SemanticAppGrouper grouper;
    private final OpenRouterAppGrouper openRouterGrouper = new OpenRouterAppGrouper();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "XLS-AutoFolder");
            thread.setDaemon(true);
            return thread;
        }
    });

    public AutoFolderController(SemanticReranker reranker) {
        this.grouper = new SemanticAppGrouper(reranker);
    }

    public void install(Object appsView, XposedModule module) {
        if (appsView == null) return;
        View button = getActiveMenuButton(appsView);
        if (button == null) return;

        button.setOnClickListener(view -> showEnhancedMenu(appsView, view, module));
    }

    private void showEnhancedMenu(Object appsView, View anchor, XposedModule module) {
        try {
            Context context = anchor.getContext();
            MenuIds ids = MenuIds.from(context);
            Context menuContext = ids.launcherTheme == 0
                    ? context
                    : new ContextThemeWrapper(context, ids.launcherTheme);

            PopupMenu popupMenu = new PopupMenu(menuContext, anchor);
            setFieldNoThrow(appsView, "mPopupMenu", popupMenu);

            Menu menu = popupMenu.getMenu();
            Object adapter = invokeNoThrow(appsView, "getAdapter");
            boolean inEditMode = invokeBoolean(appsView, "isInOwnOrderEditMode", false);
            boolean inSearchMode = invokeBoolean(appsView, "isInSearchMode", false);
            boolean ownOrder = adapter != null && invokeBoolean(adapter, "isOwnOrder", false);

            menu.add(Menu.NONE, ids.sort, 0, text(context, "all_apps_options_menu_sort_apps", "Sort apps"))
                    .setVisible(!inEditMode && !inSearchMode);
            menu.add(Menu.NONE, ids.rearrange, 1, text(context, "all_apps_options_menu_rearrange", "Rearrange"))
                    .setVisible(ownOrder && !inEditMode);
            menu.add(Menu.NONE, ids.exit, 2, text(context, "all_apps_options_menu_rearrange_exit", "Exit rearrange"))
                    .setVisible(inEditMode);
            menu.add(Menu.NONE, MENU_ID_AUTO_FOLDER, 3,
                            moduleString(context, module, R.string.auto_folder_menu_title,
                                    "AI Auto Folder (Local)"))
                    .setVisible(!inEditMode && !inSearchMode);
            menu.add(Menu.NONE, MENU_ID_OPENROUTER_AUTO_FOLDER, 4,
                            moduleString(context, module, R.string.auto_folder_openrouter_menu_title,
                                    "AI Auto Folder (OpenRouter)"))
                    .setVisible(!inEditMode && !inSearchMode);

            popupMenu.setOnMenuItemClickListener(item ->
                    handleMenuItem(appsView, anchor, adapter, ids, item, module));
            popupMenu.show();
        } catch (Throwable t) {
            log(module, "Failed to show enhanced all apps menu", t);
        }
    }

    private boolean handleMenuItem(
            Object appsView,
            View anchor,
            Object adapter,
            MenuIds ids,
            MenuItem item,
            XposedModule module
    ) {
        int itemId = item.getItemId();
        try {
            if (itemId == ids.rearrange) {
                hideKeyboard(appsView);
                invokeNoThrow(appsView, "setOwnOrderEditMode", new Class<?>[]{boolean.class}, true);
                return true;
            }
            if (itemId == ids.exit) {
                invokeNoThrow(appsView, "setOwnOrderEditMode", new Class<?>[]{boolean.class}, false);
                return true;
            }
            if (itemId == ids.sort) {
                showSortDialog(appsView, adapter);
                return true;
            }
            if (itemId == MENU_ID_AUTO_FOLDER) {
                confirmAndStart(appsView, anchor, module);
                return true;
            }
            if (itemId == MENU_ID_OPENROUTER_AUTO_FOLDER) {
                showOpenRouterDialog(appsView, anchor, module);
                return true;
            }
        } catch (Throwable t) {
            log(module, "All apps menu action failed", t);
            toast(anchor.getContext(), module, R.string.auto_folder_action_failed,
                    "Couldn't run that action.");
            return true;
        }
        return false;
    }

    private void showSortDialog(Object appsView, Object adapter) throws Exception {
        if (adapter == null) return;

        Activity activity = activityFor(appsView);
        if (activity == null) return;

        ClassLoader cl = appsView.getClass().getClassLoader();
        Class<?> sortModeClass = cl.loadClass("com.sonymobile.launcher.allapps.SortMode");
        Object sortMode = invokeNoThrow(adapter, "getSortMode");
        if (sortMode == null) {
            sortMode = enumValue(sortModeClass, "ALPHABETICAL");
        }

        Class<?> fragmentClass = cl.loadClass("com.sonymobile.launcher.allapps.SortModeDialogFragment");
        Method newInstance = findMethod(fragmentClass, "newInstance", sortModeClass);
        Object fragment = newInstance.invoke(null, sortMode);

        FragmentTransaction transaction = activity.getFragmentManager().beginTransaction();
        transaction.add((Fragment) fragment, "sort-apps-dialog");
        transaction.commitAllowingStateLoss();
    }

    private void confirmAndStart(Object appsView, View anchor, XposedModule module) {
        if (running.get()) {
            toast(anchor.getContext(), module, R.string.auto_folder_already_running,
                    "Auto foldering is already running.");
            return;
        }

        Snapshot snapshot;
        try {
            snapshot = collectSnapshot(appsView);
        } catch (Throwable t) {
            log(module, "Failed to collect app tray snapshot", t);
            toast(anchor.getContext(), module, R.string.auto_folder_read_failed,
                    "Couldn't read the app list.");
            return;
        }

        if (snapshot.candidates.size() < 2) {
            toast(snapshot.context, module, R.string.auto_folder_not_enough_apps,
                    "There aren't enough loose apps to folder.");
            return;
        }

        Activity activity = activityFor(appsView);
        if (activity == null) {
            toast(snapshot.context, module, R.string.auto_folder_launcher_unavailable,
                    "Couldn't find the Launcher screen.");
            return;
        }

        Context moduleContext = moduleContext(snapshot.context, module);
        String message = moduleString(snapshot.context, moduleContext,
                R.string.auto_folder_confirm_message,
                snapshot.candidates.size()
                        + " loose apps will be analyzed locally and grouped with similar apps.\n\n"
                        + "Existing folders and their contents will be kept unchanged.",
                snapshot.candidates.size());

        new AlertDialog.Builder(activity)
                .setTitle(moduleString(snapshot.context, moduleContext,
                        R.string.auto_folder_menu_title, "AI Auto Folder (Local)"))
                .setMessage(message)
                .setPositiveButton(moduleString(snapshot.context, moduleContext,
                                R.string.auto_folder_confirm_positive, "Run"),
                        (dialog, which) -> startAutoFolder(appsView, snapshot, module, moduleContext))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showOpenRouterDialog(Object appsView, View anchor, XposedModule module) {
        if (running.get()) {
            toast(anchor.getContext(), module, R.string.auto_folder_already_running,
                    "Auto foldering is already running.");
            return;
        }

        Snapshot snapshot;
        try {
            snapshot = collectSnapshot(appsView);
        } catch (Throwable t) {
            log(module, "Failed to collect app tray snapshot for OpenRouter", t);
            toast(anchor.getContext(), module, R.string.auto_folder_read_failed,
                    "Couldn't read the app list.");
            return;
        }

        if (snapshot.candidates.size() < 2) {
            toast(snapshot.context, module, R.string.auto_folder_not_enough_apps,
                    "There aren't enough loose apps to folder.");
            return;
        }

        Activity activity = activityFor(appsView);
        if (activity == null) {
            toast(snapshot.context, module, R.string.auto_folder_launcher_unavailable,
                    "Couldn't find the Launcher screen.");
            return;
        }

        Context moduleContext = moduleContext(snapshot.context, module);
        SharedPreferences prefs = openRouterPrefs(snapshot.context);

        EditText apiKeyInput = new EditText(activity);
        apiKeyInput.setSingleLine(true);
        apiKeyInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        apiKeyInput.setText(prefs.getString(PREF_OPENROUTER_API_KEY, ""));
        apiKeyInput.setHint(moduleString(snapshot.context, moduleContext,
                R.string.auto_folder_openrouter_api_key_hint,
                "sk-or-v1-..."));

        EditText modelInput = new EditText(activity);
        modelInput.setSingleLine(true);
        modelInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_URI);
        modelInput.setText(prefs.getString(PREF_OPENROUTER_MODEL, DEFAULT_OPENROUTER_MODEL));
        modelInput.setHint(DEFAULT_OPENROUTER_MODEL);

        EditText promptInput = new EditText(activity);
        promptInput.setMinLines(5);
        promptInput.setMaxLines(10);
        promptInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        String defaultPrompt = moduleString(snapshot.context, moduleContext,
                R.string.auto_folder_openrouter_default_prompt,
                "Group similar personal apps into concise launcher folders. "
                        + "Use the launcher language for labels. "
                        + "Do not group apps unless the relationship is clear.");
        promptInput.setText(prefs.getString(PREF_OPENROUTER_PROMPT, defaultPrompt));

        int padding = dp(activity, 20);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(padding, padding / 2, padding, 0);

        addDialogText(content, activity, moduleString(snapshot.context, moduleContext,
                R.string.auto_folder_openrouter_warning,
                "%1$d loose apps will be sent to OpenRouter as app names and package names. "
                        + "Existing folders are not sent and will remain unchanged.",
                snapshot.candidates.size()));
        addDialogField(content, activity, moduleString(snapshot.context, moduleContext,
                R.string.auto_folder_openrouter_api_key_label, "API key"), apiKeyInput);
        addDialogField(content, activity, moduleString(snapshot.context, moduleContext,
                R.string.auto_folder_openrouter_model_label, "Model"), modelInput);
        addDialogField(content, activity, moduleString(snapshot.context, moduleContext,
                R.string.auto_folder_openrouter_prompt_label, "Custom conditions"), promptInput);

        ScrollView scrollView = new ScrollView(activity);
        scrollView.addView(content);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(moduleString(snapshot.context, moduleContext,
                        R.string.auto_folder_openrouter_menu_title,
                        "AI Auto Folder (OpenRouter)"))
                .setView(scrollView)
                .setPositiveButton(moduleString(snapshot.context, moduleContext,
                        R.string.auto_folder_confirm_positive, "Run"), null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        dialog.setOnShowListener(dialogInterface ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                    String apiKey = apiKeyInput.getText().toString().trim();
                    String model = modelInput.getText().toString().trim();
                    String prompt = promptInput.getText().toString().trim();

                    if (TextUtils.isEmpty(apiKey)) {
                        apiKeyInput.setError(moduleString(snapshot.context, moduleContext,
                                R.string.auto_folder_openrouter_api_key_required,
                                "API key is required."));
                        return;
                    }
                    if (TextUtils.isEmpty(model)) {
                        modelInput.setError(moduleString(snapshot.context, moduleContext,
                                R.string.auto_folder_openrouter_model_required,
                                "Model is required."));
                        return;
                    }
                    if (TextUtils.isEmpty(prompt)) {
                        prompt = defaultPrompt;
                    }

                    prefs.edit()
                            .putString(PREF_OPENROUTER_API_KEY, apiKey)
                            .putString(PREF_OPENROUTER_MODEL, model)
                            .putString(PREF_OPENROUTER_PROMPT, prompt)
                            .apply();
                    dialog.dismiss();

                    startOpenRouterAutoFolder(appsView, snapshot, module, moduleContext,
                            new OpenRouterAppGrouper.Config(apiKey, model, prompt));
                }));
        dialog.show();
    }

    private void startOpenRouterAutoFolder(
            Object appsView,
            Snapshot snapshot,
            XposedModule module,
            Context moduleContext,
            OpenRouterAppGrouper.Config config
    ) {
        if (!running.compareAndSet(false, true)) {
            toast(snapshot.context, moduleContext, R.string.auto_folder_already_running,
                    "Auto foldering is already running.");
            return;
        }

        toast(snapshot.context, moduleContext, R.string.auto_folder_openrouter_analyzing,
                "OpenRouter is analyzing your apps...");
        worker.execute(() -> {
            List<Group> groups;
            try {
                groups = openRouterGrouper.group(snapshot.context, snapshot.candidates, config);
            } catch (Throwable t) {
                log(module, "OpenRouter app grouping failed", t);
                mainHandler.post(() -> {
                    running.set(false);
                    toast(snapshot.context, moduleContext,
                            R.string.auto_folder_openrouter_analysis_failed,
                            "OpenRouter analysis couldn't be completed.");
                });
                return;
            }

            mainHandler.post(() -> {
                try {
                    ApplyResult result = applyGroups(appsView, groups);
                    if (result.folderCount == 0) {
                        toast(snapshot.context, moduleContext, R.string.auto_folder_no_new_folders,
                                "No new folders can be created.");
                    } else {
                        toast(snapshot.context, resultText(snapshot.context, moduleContext, result));
                    }
                } catch (Throwable t) {
                    log(module, "Failed to apply OpenRouter auto folders", t);
                    toast(snapshot.context, moduleContext, R.string.auto_folder_save_failed,
                            "Couldn't save the auto folders.");
                } finally {
                    running.set(false);
                }
            });
        });
    }

    private void startAutoFolder(
            Object appsView,
            Snapshot snapshot,
            XposedModule module,
            Context moduleContext
    ) {
        if (!running.compareAndSet(false, true)) {
            toast(snapshot.context, moduleContext, R.string.auto_folder_already_running,
                    "Auto foldering is already running.");
            return;
        }

        toast(snapshot.context, moduleContext, R.string.auto_folder_analyzing,
                "AI is analyzing your apps...");
        worker.execute(() -> {
            List<Group> groups;
            try {
                groups = grouper.group(snapshot.context, snapshot.candidates, moduleContext);
            } catch (Throwable t) {
                log(module, "Semantic app grouping failed", t);
                mainHandler.post(() -> {
                    running.set(false);
                    toast(snapshot.context, moduleContext, R.string.auto_folder_analysis_failed,
                            "AI analysis couldn't be completed.");
                });
                return;
            }

            mainHandler.post(() -> {
                try {
                    ApplyResult result = applyGroups(appsView, groups);
                    if (result.folderCount == 0) {
                        toast(snapshot.context, moduleContext, R.string.auto_folder_no_new_folders,
                                "No new folders can be created.");
                    } else {
                        toast(snapshot.context, resultText(snapshot.context, moduleContext, result));
                    }
                } catch (Throwable t) {
                    log(module, "Failed to apply auto folders", t);
                    toast(snapshot.context, moduleContext, R.string.auto_folder_save_failed,
                            "Couldn't save the auto folders.");
                } finally {
                    running.set(false);
                }
            });
        });
    }

    private Snapshot collectSnapshot(Object appsView) throws Exception {
        Context context = ((View) appsView).getContext();
        Object personalAppList = invokeRequired(appsView, "getPersonalAppList");
        ArrayList<?> ownOrderApps = ownOrderApps(personalAppList);

        ClassLoader cl = appsView.getClass().getClassLoader();
        Class<?> appInfoClass = cl.loadClass("com.android.launcher3.model.data.AppInfo");
        Class<?> folderInfoClass = cl.loadClass("com.android.launcher3.model.data.FolderInfo");

        ArrayList<AppCandidate> candidates = new ArrayList<>();

        for (int i = 0; i < ownOrderApps.size(); i++) {
            Object item = ownOrderApps.get(i);
            if (!folderInfoClass.isInstance(item) && appInfoClass.isInstance(item)) {
                String key = componentKey(item);
                if (TextUtils.isEmpty(key)) continue;
                candidates.add(new AppCandidate(item, key, titleOf(item), packageNameOf(item), i));
            }
        }

        return new Snapshot(context, candidates);
    }

    private ApplyResult applyGroups(Object appsView, List<Group> semanticGroups) throws Exception {
        if (semanticGroups == null || semanticGroups.isEmpty()) return new ApplyResult(0, 0);

        Object personalAppList = invokeRequired(appsView, "getPersonalAppList");
        @SuppressWarnings("unchecked")
        ArrayList<Object> ownOrderApps = (ArrayList<Object>) ownOrderApps(personalAppList);

        ClassLoader cl = appsView.getClass().getClassLoader();
        Class<?> appInfoClass = cl.loadClass("com.android.launcher3.model.data.AppInfo");
        Class<?> folderInfoClass = cl.loadClass("com.android.launcher3.model.data.FolderInfo");
        Class<?> itemInfoClass = cl.loadClass("com.android.launcher3.model.data.ItemInfo");
        Class<?> launcherClass = cl.loadClass("com.android.launcher3.Launcher");
        Class<?> modelWriterClass = cl.loadClass("com.android.launcher3.model.ModelWriter");
        Class<?> sortModeClass = cl.loadClass("com.sonymobile.launcher.allapps.SortMode");

        Object launcher = activityContextFor(appsView);
        if (launcher == null || !launcherClass.isInstance(launcher)) {
            throw new IllegalStateException("Launcher activity context is unavailable");
        }
        Object modelWriter = invokeRequired(launcher, "getModelWriter");

        Method addItemToDatabaseSync = findMethod(modelWriterClass, "addItemToDatabaseSync",
                itemInfoClass, int.class, int.class, int.class, int.class);
        Method moveItemInDatabase = findMethod(modelWriterClass, "moveItemInDatabase",
                itemInfoClass, int.class, int.class, int.class, int.class);
        Method moveAllAppsItemsInDatabase = findMethod(modelWriterClass, "moveAllAppsItemsInDatabase",
                ArrayList.class);
        Method updateFolderItems = findMethod(folderInfoClass, "updateItemLocationsInDatabaseBatch",
                launcherClass);
        Method addToFolder = findMethod(folderInfoClass, "add", itemInfoClass);
        Method setFolderTitle = findMethod(folderInfoClass, "setTitle",
                CharSequence.class, modelWriterClass);
        Method setSortMode = findMethod(appsView.getClass(), "setSortMode", sortModeClass);
        Method updateAdapterItems = findMethod(personalAppList.getClass(), "updateAdapterItems",
                boolean.class);

        CurrentApps current = collectCurrentApps(ownOrderApps, appInfoClass, folderInfoClass);
        ArrayList<ApplyGroup> applyGroups = buildApplyGroups(semanticGroups, current, folderInfoClass,
                addToFolder, setFolderTitle);
        if (applyGroups.isEmpty()) return new ApplyResult(0, 0);

        Map<String, ApplyGroup> appToGroup = new HashMap<>();
        int appCount = 0;
        for (ApplyGroup group : applyGroups) {
            appCount += group.apps.size();
            for (Object app : group.apps) {
                appToGroup.put(componentKey(app), group);
            }
        }

        ArrayList<Object> newOrder = new ArrayList<>(ownOrderApps.size());
        Set<ApplyGroup> insertedGroups = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object item : ownOrderApps) {
            if (appInfoClass.isInstance(item)) {
                ApplyGroup group = appToGroup.get(componentKey(item));
                if (group != null) {
                    if (insertedGroups.add(group)) {
                        newOrder.add(group.folder);
                    }
                    continue;
                }
            }
            newOrder.add(item);
        }

        updateTopLevelRanks(newOrder);
        ownOrderApps.clear();
        ownOrderApps.addAll(newOrder);

        for (ApplyGroup group : applyGroups) {
            int screenId = intField(group.folder, "screenId", 0);
            addItemToDatabaseSync.invoke(modelWriter, group.folder, -102, screenId, -1, -1);
            int folderId = intField(group.folder, "id", -1);
            for (Object app : group.apps) {
                moveItemInDatabase.invoke(modelWriter, app, folderId, 0,
                        intField(app, "cellX", -1), intField(app, "cellY", -1));
            }
            updateFolderItems.invoke(group.folder, launcher);
        }

        moveAllAppsItemsInDatabase.invoke(modelWriter, ownOrderApps);
        setSortMode.invoke(appsView, enumValue(sortModeClass, "OWN_ORDER"));
        updateAdapterItems.invoke(personalAppList, true);

        return new ApplyResult(applyGroups.size(), appCount);
    }

    private CurrentApps collectCurrentApps(
            ArrayList<Object> ownOrderApps,
            Class<?> appInfoClass,
            Class<?> folderInfoClass
    ) {
        CurrentApps current = new CurrentApps();
        for (int i = 0; i < ownOrderApps.size(); i++) {
            Object item = ownOrderApps.get(i);
            if (folderInfoClass.isInstance(item)) {
                String label = titleOf(item);
                if (!TextUtils.isEmpty(label)) current.existingLabels.add(label);
            } else if (appInfoClass.isInstance(item)) {
                String key = componentKey(item);
                if (!TextUtils.isEmpty(key) && !current.looseAppsByKey.containsKey(key)) {
                    current.looseAppsByKey.put(key, item);
                    current.indexByKey.put(key, i);
                }
            }
        }
        return current;
    }

    private ArrayList<ApplyGroup> buildApplyGroups(
            List<Group> semanticGroups,
            CurrentApps current,
            Class<?> folderInfoClass,
            Method addToFolder,
            Method setFolderTitle
    ) throws Exception {
        ArrayList<ApplyGroup> groups = new ArrayList<>();
        Set<String> claimedKeys = new HashSet<>();

        for (Group semanticGroup : semanticGroups) {
            ArrayList<Object> apps = new ArrayList<>();
            int firstIndex = Integer.MAX_VALUE;

            for (AppCandidate candidate : semanticGroup.apps) {
                if (claimedKeys.contains(candidate.key)) continue;
                Object app = current.looseAppsByKey.get(candidate.key);
                Integer index = current.indexByKey.get(candidate.key);
                if (app != null && index != null) {
                    apps.add(app);
                    firstIndex = Math.min(firstIndex, index);
                }
            }

            if (apps.size() < 2) continue;

            String label = uniqueLabel(semanticGroup.label, current.existingLabels);
            Object folder = newFolder(folderInfoClass, label, setFolderTitle);
            for (Object app : apps) {
                addToFolder.invoke(folder, app);
                claimedKeys.add(componentKey(app));
            }
            groups.add(new ApplyGroup(label, folder, apps, firstIndex));
        }

        groups.sort((a, b) -> Integer.compare(a.firstIndex, b.firstIndex));
        return groups;
    }

    private Object newFolder(Class<?> folderInfoClass, String label, Method setFolderTitle)
            throws Exception {
        Constructor<?> constructor = folderInfoClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object folder = constructor.newInstance();
        setFolderTitle.invoke(folder, label, null);
        return folder;
    }

    private String uniqueLabel(String base, Set<String> usedLabels) {
        String label = TextUtils.isEmpty(base) ? "AI" : base;
        if (usedLabels.add(label)) return label;

        String aiLabel = label + " AI";
        if (usedLabels.add(aiLabel)) return aiLabel;

        int suffix = 2;
        while (true) {
            String candidate = aiLabel + " " + suffix;
            if (usedLabels.add(candidate)) return candidate;
            suffix++;
        }
    }

    private void updateTopLevelRanks(ArrayList<Object> items) throws Exception {
        for (int i = 0; i < items.size(); i++) {
            Object item = items.get(i);
            setIntField(item, "rank", i);
            setIntField(item, "screenId", i);
        }
    }

    private View getActiveMenuButton(Object appsView) {
        if (invokeBoolean(appsView, "isInOwnOrderEditMode", false)) {
            Object header = invokeNoThrow(appsView, "getHeaderMenuButtonView");
            if (header instanceof View) return (View) header;
        }
        Object main = invokeNoThrow(appsView, "getMenuButtonView");
        return main instanceof View ? (View) main : null;
    }

    private void hideKeyboard(Object appsView) {
        invokeNoThrow(appsView, "hideKeyboard");
    }

    private static SharedPreferences openRouterPrefs(Context context) {
        return context.getSharedPreferences(OPENROUTER_PREFS, Context.MODE_PRIVATE);
    }

    private static void addDialogText(LinearLayout parent, Context context, String text) {
        TextView textView = new TextView(context);
        textView.setText(text);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(context, 12));
        parent.addView(textView, params);
    }

    private static void addDialogField(
            LinearLayout parent,
            Context context,
            String label,
            EditText input
    ) {
        TextView labelView = new TextView(context);
        labelView.setText(label);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.setMargins(0, dp(context, 8), 0, 0);
        parent.addView(labelView, labelParams);

        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        inputParams.setMargins(0, dp(context, 4), 0, 0);
        parent.addView(input, inputParams);
    }

    private static int dp(Context context, int value) {
        float density = context.getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    private Activity activityFor(Object appsView) {
        Object activityContext = activityContextFor(appsView);
        if (activityContext instanceof Activity) return (Activity) activityContext;
        if (appsView instanceof View) {
            Context context = ((View) appsView).getContext();
            if (context instanceof Activity) return (Activity) context;
        }
        return null;
    }

    private Object activityContextFor(Object appsView) {
        try {
            Field field = findFieldInHierarchy(appsView.getClass(), "mActivityContext");
            return field.get(appsView);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String text(Context context, String name, String fallback) {
        int id = context.getResources().getIdentifier(name, "string", context.getPackageName());
        return id == 0 ? fallback : context.getString(id);
    }

    private static Context moduleContext(Context hostContext, XposedModule module) {
        if (hostContext == null) return null;
        try {
            return hostContext.createPackageContext(
                    MODULE_PACKAGE,
                    Context.CONTEXT_IGNORE_SECURITY
            );
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String moduleString(
            Context hostContext,
            XposedModule module,
            int resId,
            String fallback,
            Object... args
    ) {
        return moduleString(hostContext, moduleContext(hostContext, module), resId, fallback, args);
    }

    private static String moduleString(
            Context hostContext,
            Context moduleContext,
            int resId,
            String fallback,
            Object... args
    ) {
        try {
            if (moduleContext != null) {
                return args == null || args.length == 0
                        ? moduleContext.getString(resId)
                        : moduleContext.getString(resId, args);
            }
        } catch (Throwable ignored) {
        }
        return args == null || args.length == 0 ? fallback : String.format(fallback, args);
    }

    private static String resultText(Context hostContext, Context moduleContext, ApplyResult result) {
        try {
            if (moduleContext != null) {
                return moduleContext.getResources().getQuantityString(
                        R.plurals.auto_folder_result,
                        result.folderCount,
                        result.folderCount,
                        result.appCount
                );
            }
        } catch (Throwable ignored) {
        }
        return result.folderCount + " folders organized " + result.appCount + " apps.";
    }

    private static int id(Context context, String name, String type, int fallback) {
        int id = context.getResources().getIdentifier(name, type, context.getPackageName());
        return id == 0 ? fallback : id;
    }

    private static void toast(Context context, String message) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }

    private static void toast(
            Context context,
            XposedModule module,
            int resId,
            String fallback,
            Object... args
    ) {
        toast(context, moduleString(context, module, resId, fallback, args));
    }

    private static void toast(
            Context context,
            Context moduleContext,
            int resId,
            String fallback,
            Object... args
    ) {
        toast(context, moduleString(context, moduleContext, resId, fallback, args));
    }

    private static void log(XposedModule module, String message, Throwable t) {
        if (module != null) {
            module.log(TAG + ": " + message, t);
        } else {
            Log.e(TAG, message, t);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object enumValue(Class<?> enumClass, String name) {
        return Enum.valueOf((Class<Enum>) enumClass.asSubclass(Enum.class), name);
    }

    private static ArrayList<?> ownOrderApps(Object personalAppList) throws Exception {
        Object value = findFieldInHierarchy(personalAppList.getClass(), "mOwnOrderApps")
                .get(personalAppList);
        if (!(value instanceof ArrayList<?>)) {
            throw new IllegalStateException("mOwnOrderApps is unavailable");
        }
        return (ArrayList<?>) value;
    }

    private static String titleOf(Object item) {
        try {
            Object title = findFieldInHierarchy(item.getClass(), "title").get(item);
            return title == null ? "" : String.valueOf(title).trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String packageNameOf(Object item) {
        Object component = targetComponent(item);
        if (component instanceof ComponentName) {
            return ((ComponentName) component).getPackageName();
        }
        return "";
    }

    private static String componentKey(Object item) {
        Object component = targetComponent(item);
        if (component instanceof ComponentName) {
            return ((ComponentName) component).flattenToString();
        }
        return component == null ? "" : String.valueOf(component);
    }

    private static Object targetComponent(Object item) {
        try {
            Method method = findMethod(item.getClass(), "getTargetComponent");
            Object component = method.invoke(item);
            if (component != null) return component;
        } catch (Throwable ignored) {
        }
        try {
            return findFieldInHierarchy(item.getClass(), "componentName").get(item);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int intField(Object target, String fieldName, int fallback) {
        try {
            return findFieldInHierarchy(target.getClass(), fieldName).getInt(target);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static void setIntField(Object target, String fieldName, int value) throws Exception {
        findFieldInHierarchy(target.getClass(), fieldName).setInt(target, value);
    }

    private static Object invokeRequired(Object target, String methodName) throws Exception {
        Method method = findMethod(target.getClass(), methodName);
        return method.invoke(target);
    }

    private static Object invokeNoThrow(Object target, String methodName) {
        try {
            return invokeRequired(target, methodName);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object invokeNoThrow(
            Object target,
            String methodName,
            Class<?>[] parameterTypes,
            Object... args
    ) {
        try {
            Method method = findMethod(target.getClass(), methodName, parameterTypes);
            return method.invoke(target, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean invokeBoolean(Object target, String methodName, boolean fallback) {
        Object value = invokeNoThrow(target, methodName);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    private static void setFieldNoThrow(Object target, String fieldName, Object value) {
        try {
            findFieldInHierarchy(target.getClass(), fieldName).set(target, value);
        } catch (Throwable ignored) {
        }
    }

    private static Method findMethod(Class<?> startClass, String name, Class<?>... params)
            throws NoSuchMethodException {
        Class<?> c = startClass;
        while (c != null) {
            try {
                Method method = c.getDeclaredMethod(name, params);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static Field findFieldInHierarchy(Class<?> startClass, String fieldName)
            throws NoSuchFieldException {
        Class<?> c = startClass;
        while (c != null) {
            try {
                Field field = c.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private static final class MenuIds {
        final int sort;
        final int rearrange;
        final int exit;
        final int launcherTheme;

        private MenuIds(int sort, int rearrange, int exit, int launcherTheme) {
            this.sort = sort;
            this.rearrange = rearrange;
            this.exit = exit;
            this.launcherTheme = launcherTheme;
        }

        static MenuIds from(Context context) {
            return new MenuIds(
                    id(context, "all_apps_menu_sort", "id", FALLBACK_ID_SORT),
                    id(context, "all_apps_menu_rearrange", "id", FALLBACK_ID_REARRANGE),
                    id(context, "all_apps_menu_exit", "id", FALLBACK_ID_EXIT),
                    id(context, "BaseLauncherTheme", "style", 0)
            );
        }
    }

    private static final class Snapshot {
        final Context context;
        final List<AppCandidate> candidates;

        Snapshot(Context context, List<AppCandidate> candidates) {
            this.context = context;
            this.candidates = candidates;
        }
    }

    private static final class CurrentApps {
        final Map<String, Object> looseAppsByKey = new HashMap<>();
        final Map<String, Integer> indexByKey = new HashMap<>();
        final Set<String> existingLabels = new LinkedHashSet<>();
    }

    private static final class ApplyGroup {
        @SuppressWarnings("unused")
        final String label;
        final Object folder;
        final ArrayList<Object> apps;
        final int firstIndex;

        ApplyGroup(String label, Object folder, ArrayList<Object> apps, int firstIndex) {
            this.label = label;
            this.folder = folder;
            this.apps = apps;
            this.firstIndex = firstIndex;
        }
    }

    private record ApplyResult(int folderCount, int appCount) {
    }
}
