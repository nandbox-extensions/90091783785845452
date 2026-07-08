package com.nandbox.extension;

import com.nandbox.bots.api.Nandbox;
import com.nandbox.bots.api.NandboxClient;
import com.nandbox.bots.api.inmessages.IncomingMessage;
import com.nandbox.bots.api.util.DatabaseService;
import com.nandbox.bots.api.util.Utils;
import com.nandbox.extension.ExtensionAdapter;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

import net.minidev.json.JSONObject;

public class ExtensionCustomLogic extends ExtensionAdapter {

    private Nandbox.Api api;

    private static final String TABLE_NAME = "user_submissions";
    private static final String[] ADMIN_USER_IDS = new String[] { "1" };

    private static final String CMD_ALL = "/submits";
    private static final String CMD_ONE = "/submit";

    private final DatabaseService databaseService = DatabaseService.getInstance();

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

    // In this runtime, MenuCallback/ExtensionDocResponse are not available (per compiler diagnostics).
    // So menu submissions are captured via the raw JSONObject callback.
    @Override
    public void onReceive(JSONObject jsonObject) {
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

        String userId = getNestedString(jsonObject, "from", "id");
        if (userId == null || userId.trim().length() == 0) {
            return;
        }

        JSONObject doc = new JSONObject();
        doc.put("userId", userId);
        doc.put("ts", new Long(System.currentTimeMillis()));
        doc.put("payload", jsonObject);

        databaseService.set(api, doc, TABLE_NAME, userId, Utils.getUniqueId());
    }

    @Override
    public void onReceive(IncomingMessage incomingMsg) {
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

        String t = text.trim();
        if (t.length() == 0) {
            return;
        }

        if (!isAdmin(userId)) {
            return;
        }

        if (equalsIgnoreCaseOrNoSlash(t, CMD_ALL)) {
            // list-all request (response callback class is missing in this runtime)
            databaseService.set(api, new JSONObject(), TABLE_NAME, "__list__", Utils.getUniqueId());
            sendText(chatId, "List requested.", userId, chatSettings, appId);
            return;
        }

        if (startsWithIgnoreCase(t, CMD_ONE + " ") || startsWithIgnoreCase(t, "submit ")) {
            String[] parts = splitBySpace(t);
            if (parts.length < 2) {
                sendText(chatId, "Usage: /submit <userId>", userId, chatSettings, appId);
                return;
            }
            String targetUserId = parts[1];
            if (targetUserId == null || targetUserId.trim().length() == 0) {
                sendText(chatId, "Usage: /submit <userId>", userId, chatSettings, appId);
                return;
            }
            databaseService.get(api, targetUserId.trim(), TABLE_NAME, Utils.getUniqueId());
            sendText(chatId, "Get requested for userId=" + targetUserId.trim() + ".", userId, chatSettings, appId);
        }
    }

    private void sendText(String chatId, String text, String toUserId, Integer chatSettings, String appId) {
        if (api == null || chatId == null || text == null) {
            return;
        }
        api.sendText(chatId, text, Utils.getUniqueId(), null, toUserId, new Integer(0), Boolean.FALSE, chatSettings, null, null, null, appId);
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

    private boolean equalsIgnoreCaseOrNoSlash(String t, String cmdWithSlash) {
        if (t == null || cmdWithSlash == null) {
            return false;
        }
        if (t.equalsIgnoreCase(cmdWithSlash)) {
            return true;
        }
        if (cmdWithSlash.startsWith("/") && t.equalsIgnoreCase(cmdWithSlash.substring(1))) {
            return true;
        }
        return false;
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
            return String.valueOf(v);
        }
        return null;
    }
}
