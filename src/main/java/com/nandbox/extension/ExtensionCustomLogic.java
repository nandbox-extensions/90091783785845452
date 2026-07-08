package com.nandbox.extension;

import com.nandbox.bots.api.Nandbox;
import com.nandbox.bots.api.NandboxClient;
import com.nandbox.bots.api.inmessages.IncomingMessage;
import com.nandbox.bots.api.util.Utils;
import com.nandbox.extension.ExtensionAdapter;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Hashtable;
import java.util.Properties;

import net.minidev.json.JSONObject;

public class ExtensionCustomLogic extends ExtensionAdapter {

    private Nandbox.Api api;

    private static final String TABLE_NAME = "user_submissions";

    // Set your admin user id(s) here.
    private static final String[] ADMIN_USER_IDS = new String[] { "1" };

    // In-memory store fallback.
    // Key: userId, Value: JSONObject doc
    private final Hashtable submissions = new Hashtable();

    public static void main(String[] args) throws Exception {
        String TOKEN = "";
        Properties properties = new Properties();
        FileInputStream input = null;

        try {
            input = new FileInputStream("config.properties");
            properties.load(input);
            TOKEN = properties.getProperty("Token");
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException e) {
                }
            }
        }

        NandboxClient client = NandboxClient.get();
        client.connect(TOKEN, new ExtensionCustomLogic());
    }

    @Override
    public void onConnect(Nandbox.Api api) {
        this.api = api;
    }

    // Menu/form submissions should be handled here, but MenuCallback class is not available in this build.
    // Therefore we accept raw JSON fallback using onReceive(JSONObject), which is supported by ExtensionAdapter.
    public void onMenuCallback(Object menuCallback) {
        if (menuCallback == null) {
            return;
        }
        String raw = safeToString(menuCallback);
        if (raw == null) {
            return;
        }

        String userId = extractBetween(raw, "\"userId\":\"", "\"");
        if (userId == null) {
            userId = extractBetween(raw, "\"from\":{\"id\":\"", "\"");
        }
        if (userId == null) {
            userId = extractBetween(raw, "\"id\":\"", "\"");
        }
        if (userId == null || userId.trim().length() == 0) {
            return;
        }

        JSONObject doc = new JSONObject();
        doc.put("userId", userId);
        doc.put("table", TABLE_NAME);
        doc.put("ts", new Long(System.currentTimeMillis()));
        doc.put("menuCallback", raw);
        submissions.put(userId, doc);
    }

    @Override
    public void onReceive(JSONObject jsonObject) {
        // Fallback raw receiver to capture menu submissions.
        if (jsonObject == null) {
            return;
        }
        Object methodObj = jsonObject.get("method");
        if (methodObj == null) {
            return;
        }
        String method = String.valueOf(methodObj);
        if (!"menuCallback".equals(method)) {
            return;
        }

        // Store as a document keyed by userId.
        String userId = getNestedString(jsonObject, "from", "id");
        if (userId == null || userId.trim().length() == 0) {
            return;
        }

        JSONObject doc = new JSONObject();
        doc.put("userId", userId);
        doc.put("table", TABLE_NAME);
        doc.put("ts", new Long(System.currentTimeMillis()));
        doc.put("payload", jsonObject);
        submissions.put(userId, doc);
    }

    @Override
    public void onReceive(IncomingMessage incomingMsg) {
        // Admin queries via chat message.
        if (incomingMsg == null) {
            return;
        }
        if (incomingMsg.getChat() == null || incomingMsg.getFrom() == null) {
            return;
        }

        String chatId = incomingMsg.getChat().getId();
        String userId = incomingMsg.getFrom().getId();
        String appId = incomingMsg.getAppId();
        Integer chatSettings = incomingMsg.getChatSettings();
        String text = incomingMsg.getText();

        if (text == null) {
            return;
        }

        String trimmed = text.trim();
        if (trimmed.length() == 0) {
            return;
        }

        if (!isAdmin(userId)) {
            return;
        }

        if ("/submits".equalsIgnoreCase(trimmed) || "/submissions".equalsIgnoreCase(trimmed) || "submits".equalsIgnoreCase(trimmed)) {
            sendText(chatId, listAllSubmissions(), userId, chatSettings, appId);
            return;
        }

        if (startsWithIgnoreCase(trimmed, "/submit ") || startsWithIgnoreCase(trimmed, "/submission ") || startsWithIgnoreCase(trimmed, "submit ")) {
            String[] parts = splitBySpace(trimmed);
            if (parts.length < 2) {
                sendText(chatId, "Usage: /submit <userId>", userId, chatSettings, appId);
                return;
            }
            String targetUserId = parts[1];
            if (targetUserId == null || targetUserId.trim().length() == 0) {
                sendText(chatId, "Usage: /submit <userId>", userId, chatSettings, appId);
                return;
            }
            String res = getSubmissionForUser(targetUserId.trim());
            sendText(chatId, res, userId, chatSettings, appId);
            return;
        }

        if ("/help".equalsIgnoreCase(trimmed) || "help".equalsIgnoreCase(trimmed)) {
            sendText(chatId, "Admin commands:\n/submits  - list all submissions\n/submit <userId> - get a user's submission", userId, chatSettings, appId);
            return;
        }
    }

    private String listAllSubmissions() {
        if (submissions.size() == 0) {
            return "No submissions found.";
        }
        StringBuffer sb = new StringBuffer();
        sb.append("All submissions (count=");
        sb.append(submissions.size());
        sb.append("):\n");

        java.util.Enumeration en = submissions.keys();
        int count = 0;
        while (en.hasMoreElements()) {
            Object k = en.nextElement();
            if (k != null) {
                String uid = String.valueOf(k);
                Object v = submissions.get(uid);
                String tsStr = "";
                if (v instanceof JSONObject) {
                    Object ts = ((JSONObject) v).get("ts");
                    if (ts != null) {
                        tsStr = String.valueOf(ts);
                    }
                }
                sb.append("- userId=");
                sb.append(uid);
                if (tsStr.length() > 0) {
                    sb.append(" ts=");
                    sb.append(tsStr);
                }
                sb.append("\n");
                count++;
                if (count >= 200 || sb.length() > 3500) {
                    sb.append("...\n");
                    break;
                }
            }
        }
        return sb.toString();
    }

    private String getSubmissionForUser(String targetUserId) {
        Object v = submissions.get(targetUserId);
        if (!(v instanceof JSONObject)) {
            return "No submission found for that user.";
        }

        JSONObject doc = (JSONObject) v;
        String uid = safeToString(doc.get("userId"));
        Object tsObj = doc.get("ts");
        String tsStr = (tsObj == null) ? "" : safeToString(tsObj);

        String msg = "Submission for userId=" + uid;
        if (tsStr != null && tsStr.length() > 0) {
            msg = msg + "\nTimestamp(ms): " + tsStr;
        }

        Object payload = doc.get("payload");
        if (payload == null) {
            payload = doc.get("menuCallback");
        }

        if (payload != null) {
            String payloadStr = safeToString(payload);
            if (payloadStr != null && payloadStr.length() > 3500) {
                payloadStr = payloadStr.substring(0, 3500) + "...";
            }
            msg = msg + "\n\nPayload:\n" + payloadStr;
        }

        return msg;
    }

    private boolean isAdmin(String userId) {
        if (userId == null) {
            return false;
        }
        int i = 0;
        while (i < ADMIN_USER_IDS.length) {
            if (userId.equals(ADMIN_USER_IDS[i])) {
                return true;
            }
            i++;
        }
        return false;
    }

    private void sendText(String chatId, String text, String toUserId, Integer chatSettings, String appId) {
        String reference = Utils.getUniqueId();
        api.sendText(chatId, text, reference, null, toUserId, new Integer(0), Boolean.FALSE, chatSettings, null, null, null, appId);
    }

    private String getNestedString(JSONObject obj, String parentKey, String childKey) {
        if (obj == null) {
            return null;
        }
        Object p = obj.get(parentKey);
        if (p instanceof JSONObject) {
            Object v = ((JSONObject) p).get(childKey);
            if (v == null) {
                return null;
            }
            return safeToString(v);
        }
        return null;
    }

    private String safeToString(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return String.valueOf(o);
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean startsWithIgnoreCase(String s, String prefix) {
        if (s == null || prefix == null) {
            return false;
        }
        if (s.length() < prefix.length()) {
            return false;
        }
        return s.substring(0, prefix.length()).equalsIgnoreCase(prefix);
    }

    private String[] splitBySpace(String s) {
        if (s == null) {
            return new String[0];
        }
        java.util.ArrayList list = new java.util.ArrayList();
        int len = s.length();
        int i = 0;
        while (i < len) {
            while (i < len && s.charAt(i) == ' ') {
                i++;
            }
            if (i >= len) {
                break;
            }
            int j = i;
            while (j < len && s.charAt(j) != ' ') {
                j++;
            }
            list.add(s.substring(i, j));
            i = j;
        }
        String[] arr = new String[list.size()];
        int k = 0;
        while (k < list.size()) {
            arr[k] = (String) list.get(k);
            k++;
        }
        return arr;
    }

    private String extractBetween(String s, String start, String end) {
        if (s == null || start == null || end == null) {
            return null;
        }
        int i = s.indexOf(start);
        if (i < 0) {
            return null;
        }
        i = i + start.length();
        int j = s.indexOf(end, i);
        if (j < 0) {
            return null;
        }
        return s.substring(i, j);
    }
}
