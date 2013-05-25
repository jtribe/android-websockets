package com.codebutler.android_websockets;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;

import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.CookieStore;
import org.apache.http.client.HttpResponseException;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.protocol.ClientContext;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.cookie.BasicClientCookie;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.protocol.BasicHttpContext;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.net.http.AndroidHttpClient;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

public class SocketIOClient {
    public static interface Handler {
        public void onConnect();
        public void on(String event, JSONArray arguments);
        public void onDisconnect(int code, String reason);
        public void onHandshakeError(HttpResponseException error);
        public void onError(Exception error);
    }

    URI mURI;
    Handler mHandler;
    String mSession;
    int mHeartbeat;
    int mClosingTimeout;
    WebSocketClient mClient;
    private static CookieStore mCookieStore;
    private String mQuery;

    public SocketIOClient(URI uri, Handler handler) {
        mURI = uri;
        mHandler = handler;
        mCookieStore = new BasicCookieStore();
    }
    
    public void setCookie(Map<String, Map<String, String>> data){
        Iterator<String> it = data.keySet().iterator();
        while (it.hasNext()) {
        	
            String key = (String) it.next();
            BasicClientCookie cookie = new BasicClientCookie(key, data.get(key).get("value"));
            cookie.setDomain(data.get(key).get("host"));
            cookie.setPath(data.get(key).get("path"));
            mCookieStore.addCookie(cookie);
        }
    }
    
    public void setQuery(Map<String, String> data){
        ArrayList<NameValuePair> nameValuePair = new ArrayList<NameValuePair>();
        Iterator<String> it = data.keySet().iterator();
        while (it.hasNext()) {
            String key = it.next();
            nameValuePair.add(new BasicNameValuePair(key, data.get(key)));
        }
        mQuery = URLEncodedUtils.format(nameValuePair, "UTF-8");
    }

    private static String downloadUriAsString(final HttpUriRequest req) throws IOException, HttpResponseException {
        BasicHttpContext httpContext = new BasicHttpContext();
        if (mCookieStore != null) {
            httpContext.setAttribute(ClientContext.COOKIE_STORE, mCookieStore);
        }
        AndroidHttpClient client = AndroidHttpClient.newInstance("android-websockets");
        try {
            HttpResponse res = client.execute(req, httpContext);
            if (res.getStatusLine().getStatusCode() >= 400) {
                throw new HttpResponseException(res.getStatusLine().getStatusCode(), res.getStatusLine().getReasonPhrase());
            }
            return readToEnd(res.getEntity().getContent());
        }
        finally {
            client.close();
        }
    }

    private static byte[] readToEndAsArray(InputStream input) throws IOException {
        DataInputStream dis = new DataInputStream(input);
        byte[] stuff = new byte[1024];
        ByteArrayOutputStream buff = new ByteArrayOutputStream();
        int read = 0;
        while ((read = dis.read(stuff)) != -1) {
            buff.write(stuff, 0, read);
        }

        return buff.toByteArray();
    }

    private static String readToEnd(InputStream input) throws IOException {
        return new String(readToEndAsArray(input));
    }

    android.os.Handler mSendHandler;
    Looper mSendLooper;

    public void emit(String name, JSONArray args) throws JSONException, UnknownHostException {
        final JSONObject event = new JSONObject();
        event.put("name", name);
        event.put("args", args);
        System.out.println(event.toString());
        if (mSendHandler != null) {
            mSendHandler.post(new Runnable() {
                @Override
                public void run() {
                    mClient.send(String.format("5:::%s", event.toString()));
                }
            });
        }
        else {
            throw new UnknownHostException("Out of service.");
        }
    }

    private void connectSession() throws URISyntaxException {
    	// switch https session over to the wss if required
    	if(mURI.getScheme().equals("https")){
    		mURI = new URI("wss", mURI.getHost(), mURI.getPath(), mURI.getFragment());
    	}

        mClient = new WebSocketClient(new URI(mURI.toString() + "/socket.io/1/websocket/" + mSession), new WebSocketClient.Handler() {
            @Override
            public void onMessage(byte[] data) {
                cleanup();
                mHandler.onError(new Exception("Unexpected binary data"));
            }

            
            @Override
            public void onMessage(String message) {
                try {
                    String[] parts = message.split(":", 4);
                    int code = Integer.parseInt(parts[0]);
                    switch (code) {
                    case 1:
                        onConnect();
                        mHandler.onConnect();
                        break;
                    case 2:
                        // heartbeat
                        break;
                    case 3:
                        // message
                    case 4:
                        // json message
                        throw new Exception("message type not supported");
                    case 5: {
                        final String messageId = parts[1];
                        final String dataString = parts[3];
                        JSONObject data = new JSONObject(dataString);
                        String event = data.getString("name");
                        JSONArray args = data.getJSONArray("args");
                        if (!"".equals(messageId)) {
                            mSendHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    mClient.send(String.format("6:::%s", messageId));
                                }
                            });
                        }
                        mHandler.on(event, args);
                        break;
                    }
                    case 6:
                        // ACK
                        break;
                    case 7:
                        // error
                        throw new Exception(message);
                    case 8:
                        // noop
                        break;
                    default:
                        throw new Exception("unknown code");
                    }
                }
                catch (Exception ex) {
                    cleanup();
                    onError(ex);
                }
            }

            @Override
            public void onError(Exception error) {
                cleanup();
                mHandler.onError(error);
            }

            @Override
            public void onDisconnect(int code, String reason) {
                cleanup();
                // attempt reconnect with same session?
                mHandler.onDisconnect(code, reason);
            }

            @Override
            public void onConnect() {
                mSendHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        mSendHandler.postDelayed(this, mHeartbeat);
                        mClient.send("2:::");
                    }
                }, mHeartbeat);
            }
        }, null);
        mClient.connect();
    }

    public boolean isConnected() {
        return mClient.isConnected();
    }

    public boolean isClosed() {
        return mClient.isClosed();
    }

    public void disconnect() throws IOException {
        cleanup();
    }

    private void cleanup() {
        try {
            if (mClient != null)
                mClient.disconnect();
            mClient = null;
        }
        catch (IOException e) {
        }
        if (mSendLooper != null)
            mSendLooper.quit();
        mSendLooper = null;
        mSendHandler = null;
    }

    public void connect() {
        if (mClient != null)
            return;
        new Thread() {
            public void run() {
                String uri = mURI.toString() + "/socket.io/1/";
                if (!TextUtils.isEmpty(mQuery)) {
                    uri = uri + "?" + mQuery;
                }
                HttpPost post = new HttpPost(uri);
                try {
                    String line = downloadUriAsString(post);
                    Log.e("line", line);
                    String[] parts = line.split(":");
                    mSession = parts[0];
                    String heartbeat = parts[1];
                    if (!"".equals(heartbeat))
                        mHeartbeat = Integer.parseInt(heartbeat) / 2 * 1000;
                    String transportsLine = parts[3];
                    String[] transports = transportsLine.split(",");
                    HashSet<String> set = new HashSet<String>(Arrays.asList(transports));
                    if (!set.contains("websocket"))
                        throw new Exception("websocket not supported");

                    Looper.prepare();
                    mSendLooper = Looper.myLooper();
                    mSendHandler = new android.os.Handler();

                    connectSession();

                    Looper.loop();
                }
                catch (HttpResponseException e) {
                    mHandler.onHandshakeError(e);
                }
                catch (Exception e) {
                    mHandler.onError(e);
                }
            };
        }.start();
    }
}
