package com.example.dandanplaydownloader;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String API_BASE = "https://api.dandanplay.net";
    private static final String PREFS = "dandanplay_downloader";
    private static final int REQUEST_FOLDER = 1001;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<JSONObject> animeResults = new ArrayList<>();
    private final List<CheckBox> episodeChecks = new ArrayList<>();

    private EditText appIdInput;
    private EditText appSecretInput;
    private EditText keywordInput;
    private EditText intervalInput;
    private EditText chConvertInput;
    private CheckBox credentialModeCheck;
    private CheckBox withRelatedCheck;
    private LinearLayout resultsBox;
    private LinearLayout episodesBox;
    private TextView detailText;
    private TextView folderText;
    private TextView logText;
    private Button searchButton;
    private Button downloadButton;
    private Uri folderUri;
    private JSONObject currentBangumi;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        loadPrefs();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_FOLDER && resultCode == RESULT_OK && data != null && data.getData() != null) {
            folderUri = data.getData();
            int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            getContentResolver().takePersistableUriPermission(folderUri, flags);
            folderText.setText("保存目录：" + DocumentsContract.getTreeDocumentId(folderUri));
            savePrefs();
        }
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(24));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("弹弹play字幕下载");
        title.setTextSize(24);
        title.setTextColor(Color.rgb(24, 34, 48));
        title.setGravity(Gravity.START);
        root.addView(title, matchWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText("手机搜索番剧，选择剧集后保存 JSON 和 Bilibili XML。");
        subtitle.setTextSize(14);
        subtitle.setTextColor(Color.rgb(102, 112, 133));
        subtitle.setPadding(0, dp(4), 0, dp(12));
        root.addView(subtitle, matchWrap());

        appIdInput = addInput(root, "AppId", false);
        appSecretInput = addInput(root, "AppSecret", true);
        credentialModeCheck = new CheckBox(this);
        credentialModeCheck.setText("凭据模式（默认关闭时使用签名模式）");
        root.addView(credentialModeCheck, matchWrap());

        LinearLayout configActions = row();
        Button saveButton = new Button(this);
        saveButton.setText("保存配置");
        saveButton.setOnClickListener(v -> {
            savePrefs();
            toast("配置已保存");
        });
        configActions.addView(saveButton, weightWrap(1));

        Button folderButton = new Button(this);
        folderButton.setText("选择保存目录");
        folderButton.setOnClickListener(v -> chooseFolder());
        configActions.addView(folderButton, weightWrap(1));
        root.addView(configActions, matchWrap());

        folderText = sectionText("保存目录：未选择");
        root.addView(folderText, matchWrap());

        LinearLayout searchRow = row();
        keywordInput = new EditText(this);
        keywordInput.setHint("番剧关键词");
        keywordInput.setSingleLine(true);
        keywordInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        keywordInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchAnime();
                return true;
            }
            return false;
        });
        searchRow.addView(keywordInput, weightWrap(1));
        searchButton = new Button(this);
        searchButton.setText("搜索");
        searchButton.setOnClickListener(v -> searchAnime());
        searchRow.addView(searchButton, wrapWrap());
        root.addView(searchRow, matchWrap());

        resultsBox = section(root, "搜索结果");
        detailText = sectionText("搜索并选择一个番剧。");
        root.addView(detailText, matchWrap());

        LinearLayout episodeActions = row();
        Button selectAll = new Button(this);
        selectAll.setText("全选");
        selectAll.setOnClickListener(v -> setAllEpisodes(true));
        episodeActions.addView(selectAll, weightWrap(1));
        Button selectNone = new Button(this);
        selectNone.setText("全不选");
        selectNone.setOnClickListener(v -> setAllEpisodes(false));
        episodeActions.addView(selectNone, weightWrap(1));
        root.addView(episodeActions, matchWrap());

        episodesBox = section(root, "剧集");

        withRelatedCheck = new CheckBox(this);
        withRelatedCheck.setText("包含关联弹幕");
        withRelatedCheck.setChecked(true);
        root.addView(withRelatedCheck, matchWrap());

        LinearLayout options = row();
        intervalInput = new EditText(this);
        intervalInput.setHint("间隔秒");
        intervalInput.setSingleLine(true);
        intervalInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        options.addView(intervalInput, weightWrap(1));
        chConvertInput = new EditText(this);
        chConvertInput.setHint("繁简转换");
        chConvertInput.setSingleLine(true);
        chConvertInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        options.addView(chConvertInput, weightWrap(1));
        root.addView(options, matchWrap());

        downloadButton = new Button(this);
        downloadButton.setText("下载 JSON + XML");
        downloadButton.setOnClickListener(v -> startDownload());
        root.addView(downloadButton, matchWrap());

        logText = sectionText("");
        logText.setTextIsSelectable(true);
        root.addView(logText, matchWrap());

        setContentView(scroll);
    }

    private void loadPrefs() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        appIdInput.setText(prefs.getString("app_id", ""));
        appSecretInput.setText(prefs.getString("app_secret", ""));
        credentialModeCheck.setChecked("credential".equals(prefs.getString("auth_mode", "signature")));
        intervalInput.setText(prefs.getString("interval_seconds", "1.5"));
        chConvertInput.setText(prefs.getString("ch_convert", "1"));
        withRelatedCheck.setChecked(prefs.getBoolean("with_related", true));
        String folder = prefs.getString("folder_uri", "");
        if (!folder.isEmpty()) {
            folderUri = Uri.parse(folder);
            folderText.setText("保存目录：" + DocumentsContract.getTreeDocumentId(folderUri));
        }
    }

    private void savePrefs() {
        SharedPreferences.Editor editor = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        editor.putString("app_id", appIdInput.getText().toString().trim());
        editor.putString("app_secret", appSecretInput.getText().toString().trim());
        editor.putString("auth_mode", credentialModeCheck.isChecked() ? "credential" : "signature");
        editor.putString("interval_seconds", intervalInput.getText().toString().trim());
        editor.putString("ch_convert", chConvertInput.getText().toString().trim());
        editor.putBoolean("with_related", withRelatedCheck.isChecked());
        editor.putString("folder_uri", folderUri == null ? "" : folderUri.toString());
        editor.apply();
    }

    private void chooseFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_FOLDER);
    }

    private void searchAnime() {
        savePrefs();
        String keyword = keywordInput.getText().toString().trim();
        if (keyword.isEmpty()) {
            toast("请输入关键词");
            return;
        }
        setBusy(true);
        log("搜索：" + keyword);
        executor.execute(() -> {
            try {
                JSONArray animes = client().searchAnime(keyword);
                mainHandler.post(() -> showSearchResults(animes));
                log("搜索完成：" + animes.length() + " 个结果");
            } catch (Exception exc) {
                showError("搜索失败：" + exc.getMessage());
            } finally {
                mainHandler.post(() -> setBusy(false));
            }
        });
    }

    private void showSearchResults(JSONArray animes) {
        animeResults.clear();
        resultsBox.removeAllViews();
        clearEpisodes();
        for (int i = 0; i < animes.length(); i++) {
            JSONObject item = animes.optJSONObject(i);
            if (item == null) {
                continue;
            }
            animeResults.add(item);
            Button button = new Button(this);
            button.setAllCaps(false);
            String title = optString(item, "animeTitle", "(无标题)");
            String type = optString(item, "typeDescription", "");
            int count = item.optInt("episodeCount", 0);
            double rating = item.optDouble("rating", 0);
            String extra = "";
            if (count > 0) {
                extra += count + "集";
            }
            if (rating > 0) {
                extra += (extra.isEmpty() ? "" : " / ") + rating + "分";
            }
            if (!type.isEmpty()) {
                extra += (extra.isEmpty() ? "" : " / ") + type;
            }
            button.setText(extra.isEmpty() ? title : title + "\n" + extra);
            int index = animeResults.size() - 1;
            button.setOnClickListener(v -> loadBangumi(index));
            resultsBox.addView(button, matchWrap());
        }
        if (animeResults.isEmpty()) {
            resultsBox.addView(sectionText("没有搜索结果。"), matchWrap());
        }
    }

    private void loadBangumi(int index) {
        if (index < 0 || index >= animeResults.size()) {
            return;
        }
        JSONObject item = animeResults.get(index);
        int bangumiId = item.optInt("bangumiId", item.optInt("animeId", 0));
        if (bangumiId <= 0) {
            toast("这个结果没有番剧 ID");
            return;
        }
        setBusy(true);
        log("获取番剧详情：" + optString(item, "animeTitle", ""));
        executor.execute(() -> {
            try {
                JSONObject bangumi = client().bangumiDetails(bangumiId);
                mainHandler.post(() -> showBangumi(bangumi));
                log("番剧详情加载完成");
            } catch (Exception exc) {
                showError("详情加载失败：" + exc.getMessage());
            } finally {
                mainHandler.post(() -> setBusy(false));
            }
        });
    }

    private void clearEpisodes() {
        currentBangumi = null;
        episodeChecks.clear();
        episodesBox.removeAllViews();
        detailText.setText("搜索并选择一个番剧。");
    }

    private void showBangumi(JSONObject bangumi) {
        currentBangumi = bangumi;
        episodeChecks.clear();
        episodesBox.removeAllViews();
        String title = optString(bangumi, "animeTitle", "(无标题)");
        String intro = optString(bangumi, "intro", optString(bangumi, "summary", ""));
        intro = intro.replace('\n', ' ');
        if (intro.length() > 160) {
            intro = intro.substring(0, 160) + "...";
        }
        detailText.setText(title + "\n评分：" + optString(bangumi, "rating", "-") + "\n" + intro);

        JSONArray episodes = bangumi.optJSONArray("episodes");
        if (episodes == null || episodes.length() == 0) {
            episodesBox.addView(sectionText("没有剧集。"), matchWrap());
            return;
        }
        for (int i = 0; i < episodes.length(); i++) {
            JSONObject episode = episodes.optJSONObject(i);
            if (episode == null) {
                continue;
            }
            CheckBox box = new CheckBox(this);
            box.setChecked(true);
            String number = optString(episode, "episodeNumber", String.valueOf(i + 1));
            String epTitle = optString(episode, "episodeTitle", "第 " + number + " 集");
            box.setText(number + "  " + epTitle + "  ID:" + episode.optInt("episodeId", 0));
            box.setTag(episode);
            episodeChecks.add(box);
            episodesBox.addView(box, matchWrap());
        }
    }

    private void setAllEpisodes(boolean checked) {
        for (CheckBox box : episodeChecks) {
            box.setChecked(checked);
        }
    }

    private void startDownload() {
        savePrefs();
        if (currentBangumi == null) {
            toast("请先选择番剧");
            return;
        }
        if (folderUri == null) {
            toast("请先选择保存目录");
            return;
        }
        List<JSONObject> episodes = selectedEpisodes();
        if (episodes.isEmpty()) {
            toast("请至少选择一集");
            return;
        }
        double interval = Math.max(0.5, parseDouble(intervalInput.getText().toString(), 1.5));
        boolean withRelated = withRelatedCheck.isChecked();
        int chConvert = (int) parseDouble(chConvertInput.getText().toString(), 1);
        setBusy(true);
        executor.execute(() -> {
            int ok = 0;
            int failed = 0;
            String bangumiTitle = safeFilename(optString(currentBangumi, "animeTitle", "番剧"));
            try {
                Uri targetDir = createOrFindChild(rootDocumentUri(folderUri),
                        DocumentsContract.Document.MIME_TYPE_DIR, bangumiTitle);
                log("开始下载：" + bangumiTitle + "，共 " + episodes.size() + " 集。");
                DandanplayClient api = client();

                for (int i = 0; i < episodes.size(); i++) {
                    JSONObject episode = episodes.get(i);
                    int episodeId = episode.optInt("episodeId", 0);
                    String number = safeFilename(optString(episode, "episodeNumber", String.format(Locale.US, "%02d", i + 1)));
                    String epTitle = safeFilename(optString(episode, "episodeTitle", "第" + number + "集"));
                    String baseName = String.format(Locale.US, "%02d - %s - %s", i + 1, number, epTitle);
                    String jsonName = baseName + ".json";
                    String xmlName = baseName + ".xml";

                    try {
                        Uri jsonUri = findChild(targetDir, jsonName, null);
                        Uri xmlUri = findChild(targetDir, xmlName, null);
                        if (jsonUri != null && xmlUri != null) {
                            log("[" + (i + 1) + "/" + episodes.size() + "] 已存在，跳过：" + epTitle);
                            ok++;
                            continue;
                        }

                        JSONObject data = null;
                        Exception lastError = null;
                        log("[" + (i + 1) + "/" + episodes.size() + "] 下载弹幕：" + epTitle);
                        for (int attempt = 1; attempt <= 3; attempt++) {
                            try {
                                data = api.comments(episodeId, withRelated, chConvert);
                                break;
                            } catch (Exception exc) {
                                lastError = exc;
                                log("  第 " + attempt + " 次失败：" + exc.getMessage());
                                Thread.sleep(Math.min(2L * attempt, 5L) * 1000L);
                            }
                        }
                        if (data == null) {
                            throw lastError == null ? new IOException("下载失败") : lastError;
                        }
                        JSONArray comments = data.optJSONArray("comments");
                        if (comments == null) {
                            comments = new JSONArray();
                        }
                        jsonUri = replaceFile(targetDir, jsonName, "application/json");
                        xmlUri = replaceFile(targetDir, xmlName, "text/xml");
                        writeText(jsonUri, data.toString(2));
                        writeText(xmlUri, commentsToBilibiliXml(comments));
                        log("  保存完成：" + comments.length() + " 条 -> " + xmlName);
                        ok++;
                    } catch (Exception exc) {
                        failed++;
                        log("  失败：" + epTitle + "，原因：" + exc.getMessage());
                    }

                    if (i + 1 < episodes.size()) {
                        Thread.sleep((long) (interval * 1000));
                    }
                }
            } catch (Exception exc) {
                showError("下载失败：" + exc.getMessage());
                mainHandler.post(() -> setBusy(false));
                return;
            }
            int finalOk = ok;
            int finalFailed = failed;
            log("下载结束：成功 " + finalOk + "，失败 " + finalFailed + "。");
            mainHandler.post(() -> {
                setBusy(false);
                toast("下载结束：成功 " + finalOk + "，失败 " + finalFailed);
            });
        });
    }

    private List<JSONObject> selectedEpisodes() {
        List<JSONObject> selected = new ArrayList<>();
        for (CheckBox box : episodeChecks) {
            if (box.isChecked() && box.getTag() instanceof JSONObject) {
                selected.add((JSONObject) box.getTag());
            }
        }
        return selected;
    }

    private DandanplayClient client() {
        String appId = appIdInput.getText().toString().trim();
        String appSecret = appSecretInput.getText().toString().trim();
        String authMode = credentialModeCheck.isChecked() ? "credential" : "signature";
        return new DandanplayClient(appId, appSecret, authMode);
    }

    private void setBusy(boolean busy) {
        searchButton.setEnabled(!busy);
        downloadButton.setEnabled(!busy);
    }

    private void log(String message) {
        mainHandler.post(() -> {
            String current = logText.getText().toString();
            logText.setText(current + (current.isEmpty() ? "" : "\n") + message);
        });
    }

    private void showError(String message) {
        log(message);
        mainHandler.post(() -> {
            setBusy(false);
            toast(message);
        });
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private EditText addInput(LinearLayout root, String hint, boolean password) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setSingleLine(true);
        if (password) {
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        root.addView(input, matchWrap());
        return input;
    }

    private LinearLayout section(LinearLayout root, String title) {
        TextView label = sectionText(title);
        label.setTextSize(16);
        label.setTextColor(Color.rgb(24, 34, 48));
        label.setPadding(0, dp(14), 0, dp(4));
        root.addView(label, matchWrap());
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        root.addView(box, matchWrap());
        return box;
    }

    private TextView sectionText(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(14);
        view.setTextColor(Color.rgb(71, 84, 103));
        view.setPadding(0, dp(6), 0, dp(6));
        return view;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weightWrap(float weight) {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private Uri rootDocumentUri(Uri treeUri) {
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri));
    }

    private Uri createOrFindChild(Uri parentUri, String mimeType, String displayName) throws IOException {
        Uri existing = findChild(parentUri, displayName, mimeType);
        if (existing != null) {
            return existing;
        }
        try {
            Uri created = DocumentsContract.createDocument(getContentResolver(), parentUri, mimeType, displayName);
            if (created == null) {
                throw new IOException("无法创建：" + displayName);
            }
            return created;
        } catch (Exception exc) {
            throw new IOException("无法创建：" + displayName, exc);
        }
    }

    private Uri findChild(Uri parentUri, String displayName, String mimeType) {
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parentUri, DocumentsContract.getDocumentId(parentUri));
        String[] columns = {
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_DOCUMENT_ID
        };
        try (Cursor cursor = getContentResolver().query(childrenUri, columns, null, null, null)) {
            if (cursor == null) {
                return null;
            }
            while (cursor.moveToNext()) {
                String name = cursor.getString(0);
                String childMime = cursor.getString(1);
                String docId = cursor.getString(2);
                boolean mimeMatches = mimeType == null || mimeType.equals(childMime);
                if (displayName.equals(name) && mimeMatches) {
                    return DocumentsContract.buildDocumentUriUsingTree(parentUri, docId);
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private Uri replaceFile(Uri parentUri, String displayName, String mimeType) throws IOException {
        Uri existing = findChild(parentUri, displayName, null);
        if (existing != null) {
            try {
                DocumentsContract.deleteDocument(getContentResolver(), existing);
            } catch (Exception exc) {
                throw new IOException("无法覆盖：" + displayName, exc);
            }
        }
        return createOrFindChild(parentUri, mimeType, displayName);
    }

    private void writeText(Uri uri, String text) throws IOException {
        try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
            if (output == null) {
                throw new IOException("无法写入文件");
            }
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String commentsToBilibiliXml(JSONArray comments) throws JSONException {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<i>\n");
        xml.append("  <chatserver>api.dandanplay.net</chatserver>\n");
        xml.append("  <chatid>0</chatid>\n");
        xml.append("  <mission>0</mission>\n");
        xml.append("  <maxlimit>").append(comments.length()).append("</maxlimit>\n");
        xml.append("  <state>0</state>\n");
        xml.append("  <real_name>0</real_name>\n");
        xml.append("  <source>k-v</source>\n");
        for (int i = 0; i < comments.length(); i++) {
            JSONObject item = comments.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String[] p = optString(item, "p", "0,1,16777215,0").split(",");
            String appearTime = p.length > 0 && !p[0].isEmpty() ? p[0] : "0";
            String mode = p.length > 1 && !p[1].isEmpty() ? p[1] : "1";
            String color = p.length > 2 && !p[2].isEmpty() ? p[2] : "16777215";
            String userId = p.length > 3 && !p[3].isEmpty() ? p[3] : "0";
            String cid = optString(item, "cid", "0");
            xml.append("  <d p=\"")
                    .append(escapeXml(appearTime)).append(',')
                    .append(escapeXml(mode)).append(",25,")
                    .append(escapeXml(color)).append(",0,0,")
                    .append(escapeXml(userId)).append(',')
                    .append(escapeXml(cid)).append("\">")
                    .append(escapeXml(optString(item, "m", "")))
                    .append("</d>\n");
        }
        xml.append("</i>\n");
        return xml.toString();
    }

    private static String safeFilename(String value) {
        String cleaned = value == null ? "" : value.trim();
        cleaned = cleaned.replaceAll("[<>:\"/\\\\|?*\\x00-\\x1f]", "_");
        cleaned = cleaned.replaceAll("\\s+", " ");
        if (cleaned.length() > 120) {
            cleaned = cleaned.substring(0, 120);
        }
        return cleaned.isEmpty() ? "untitled" : cleaned;
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static String optString(JSONObject object, String key, String fallback) {
        Object value = object.opt(key);
        if (value == null || JSONObject.NULL.equals(value)) {
            return fallback;
        }
        String text = String.valueOf(value);
        return text.isEmpty() ? fallback : text;
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return Double.parseDouble(value.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static class DandanplayClient {
        private final String appId;
        private final String appSecret;
        private final String authMode;

        DandanplayClient(String appId, String appSecret, String authMode) {
            this.appId = appId;
            this.appSecret = appSecret;
            this.authMode = authMode;
        }

        JSONArray searchAnime(String keyword) throws IOException, JSONException {
            JSONObject data = getJson("/api/v2/search/anime", "keyword=" + encode(keyword));
            JSONArray animes = data.optJSONArray("animes");
            return animes == null ? new JSONArray() : animes;
        }

        JSONObject bangumiDetails(int bangumiId) throws IOException, JSONException {
            JSONObject data = getJson("/api/v2/bangumi/" + encode(String.valueOf(bangumiId)), "");
            JSONObject bangumi = data.optJSONObject("bangumi");
            if (bangumi == null) {
                throw new IOException("没有获取到番剧详情。");
            }
            return bangumi;
        }

        JSONObject comments(int episodeId, boolean withRelated, int chConvert) throws IOException, JSONException {
            String query = "withRelated=" + (withRelated ? "true" : "false") + "&chConvert=" + chConvert;
            return getJson("/api/v2/comment/" + episodeId, query);
        }

        private JSONObject getJson(String path, String query) throws IOException, JSONException {
            if (appId.isEmpty() || appSecret.isEmpty()) {
                throw new IOException("请先填写 AppId 和 AppSecret。");
            }
            URL url = new URL(API_BASE + path + (query == null || query.isEmpty() ? "" : "?" + query));
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(25000);
            connection.setReadTimeout(25000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "DanDanPlaySubtitleDownloaderAndroid/0.1");
            connection.setRequestProperty("X-AppId", appId);
            if ("credential".equals(authMode)) {
                connection.setRequestProperty("X-AppSecret", appSecret);
            } else {
                String timestamp = String.valueOf(System.currentTimeMillis() / 1000L);
                connection.setRequestProperty("X-Timestamp", timestamp);
                connection.setRequestProperty("X-Signature", signature(appId, timestamp, path, appSecret));
            }

            int code = connection.getResponseCode();
            InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String body = readAll(stream);
            if (code >= 400) {
                String error = connection.getHeaderField("X-Error-Message");
                throw new IOException("HTTP " + code + ": " + (error == null ? body : error));
            }
            JSONObject data = new JSONObject(body);
            if (data.optBoolean("success", true) == false) {
                throw new IOException(optString(data, "errorMessage", "接口返回失败。"));
            }
            return data;
        }

        private static String signature(String appId, String timestamp, String path, String appSecret) throws IOException {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                String raw = appId + timestamp + path.toLowerCase(Locale.ROOT) + appSecret;
                byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
                return Base64.encodeToString(hash, Base64.NO_WRAP);
            } catch (NoSuchAlgorithmException exc) {
                throw new IOException("系统不支持 SHA-256", exc);
            }
        }

        private static String encode(String value) throws IOException {
            return URLEncoder.encode(value, "UTF-8");
        }

        private static String readAll(InputStream stream) throws IOException {
            if (stream == null) {
                return "";
            }
            StringBuilder builder = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    builder.append(line);
                }
            }
            return builder.toString();
        }
    }
}
